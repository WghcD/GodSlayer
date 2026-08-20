package com.godslayer.power;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.godslayer.GodSlayerNative;
import com.godslayer.core.EarlyNativeBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Unsafe 防护器 v3 —— 完全内联方案
 *
 * 与 v2 的区别：
 *   1. 不需要 GuardHook 类、不需要部署 bootstrap classpath、不需要改 build.gradle
 *   2. 检查字节码直接生成到被修改的 JDK 类自身方法体内
 *      （检查逻辑只引用 Throwable/StackTraceElement/String/SecurityException，
 *       全部在 bootstrap classpath，无任何可见性问题）
 *   3. 【绝不置空 theUnsafe】—— 上次崩溃 的根源，
 *      JDK 内部（如 WindowsWatchService）大量读取该字段
 *
 * 工作原理（栈帧白名单）：
 *   生成的检查代码遍历当前调用栈：
 *     - 跳过所有 JDK 帧（java./javax./sun./jdk./com.sun. 开头）
 *     - 遇到的第一个"业务帧"如果在可信前缀列表中 → 放行
 *     - 否则 → 抛 SecurityException（消息中带敌方类名，方便定位）
 *   这样：JDK 内部调用全是 JDK 帧 → 放行；netty/forge 初始化 → 白名单放行；
 *        敌方 mod（flashfur.omnimobs 等）→ 拦截
 */
public final class UnsafeGuard {

    // ==================== 白名单配置 ====================
    // JDK 帧前缀：这些帧直接跳过（java.lang.reflect.* 帧也以此跳过）
    private static final String[] JDK_PREFIXES = {
            "java.", "javax.", "sun.", "jdk.", "com.sun."
    };

    // 可信业务前缀：第一个非 JDK 帧命中任意一个即放行
    // 按你的环境增删，必须覆盖启动期所有会触碰反射/Unsafe 的生态库！
    private static final String[] TRUSTED_PREFIXES = {
            "net.minecraft.", "com.mojang.",
            "net.minecraftforge.", "cpw.mods.",
            "org.spongepowered.",                    // Mixin
            "io.netty.", "org.lwjgl.",
            "com.google.", "org.slf4j.", "org.apache.",
            "it.unimi.dsi.",                         // fastutil
            "com.electronwill.",                     // nightconfig (Forge配置)
            "org.to2mbn.",                           // authlib-injector
            "oshi.",                                 // 系统信息库
            "com.godslayer."                         // 我们自己
    };

    private static Instrumentation inst = EarlyNativeBridge.inst;

    private static final AtomicBoolean installed = new AtomicBoolean(false);

    /** 已完成注入的类（诊断用） */
    private static final Set<String> injected = ConcurrentHashMap.newKeySet();

    // ==================== 主入口 ====================

    public static void disableAllUnsafeAccess() {
        if (!installed.compareAndSet(false, true)) {
            log("已安装，跳过");
            return;
        }

        log("════════ Unsafe 防护 v3（纯内联方案）════════");
        log("白名单前缀数: " + TRUSTED_PREFIXES.length);

        if (inst == null) {
            if(GodSlayerNative.inst!=null){inst=GodSlayerNative.inst;}else{
                if(EarlyNativeBridge.inst!=null){inst=EarlyNativeBridge.inst;}else{
                    logErr("[FATAL] Instrumentation 不可用（inst 全部为 null）");

                    return;
                }
            }

        }

        // 步骤1: 注册 C++ 层类名过滤器（性能关键：只有这4个类会被转发到Java层）
        //        前提：C++ 端已实现 addTransformFilter（上一轮修复的 native 方法）
        try {
            GodSlayerNative.addTransformFilter("sun/misc/Unsafe");
            GodSlayerNative.addTransformFilter("jdk/internal/misc/Unsafe");
            GodSlayerNative.addTransformFilter("java/lang/reflect/AccessibleObject");
            GodSlayerNative.addTransformFilter("java/lang/reflect/Field");
            log("✓ C++ 层过滤器已注册 (4 个类)");
        } catch (Throwable t) {
            logErr("✗ addTransformFilter 调用失败: " + t);
            logErr("  请确认 C++ 端已编译进最新 DLL 并包含该 native 方法");
        }

        // 步骤2: 注册 ASM Transformer
        inst.addTransformer(new InlineGuardTransformer(), true);
        log("✓ Transformer 注册成功 (canRetransform=true)");

        // 步骤3: 触发目标类加载 + retransform
        //        Class.forName 时若类未加载，加载路径也会经过我们的 transformer
        retransform("sun.misc.Unsafe");
        retransform("jdk.internal.misc.Unsafe");
        retransform("java.lang.reflect.AccessibleObject");
        retransform("java.lang.reflect.Field");

        // 步骤4: 验证
        verify();

        log("════════ 安装完成，注入统计: " + injected + " ════════");
    }

    // ==================== 内部实现 ====================

    private static void retransform(String className) {
        try {
            // loader=null 表示用 bootstrap classloader 查找（java.base 的类都在这里）
            Class<?> clazz = Class.forName(className, false, null);
            if (!inst.isModifiableClass(clazz)) {
                logErr("✗ 不可修改: " + className);
                return;
            }
            inst.retransformClasses(clazz);
            String internal = className.replace('.', '/');
            log("retransform " + className + " → "
                    + (injected.contains(internal) ? "✓ 已注入" : "✗ Transformer未触发(C++链路问题?)"));
        } catch (Throwable t) {
            logErr("retransform " + className + " 失败: " + t);
        }
    }

    private static void verify() {
        log("──── 防护验证 ────");

        // 验证1: 敌方路径 —— 反射构造 Unsafe（"Made a new Unsafe instance!" 那条路）
        try {
            java.lang.reflect.Constructor<?> ctor =
                    sun.misc.Unsafe.class.getDeclaredConstructor();
            ctor.setAccessible(true); // 应在此抛 SecurityException
            Object u = ctor.newInstance();
            logErr("✗ 验证1失败: 构造出了新实例 " + u);
        } catch (SecurityException e) {
            log("✓ 验证1: 反射构造被拦截 — " + e.getMessage());
        } catch (Exception e) {
            Throwable c = e.getCause();
            log("✓ 验证1: 被拦截 (" +
                    (c != null ? c.getClass().getSimpleName() : e.getClass().getSimpleName()) + ")");
        }

        // 验证2: 敌方 fallback 路径 —— 反射读 theUnsafe 字段
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true); // 应在此抛 SecurityException
            Object u = f.get(null);
            logErr("✗ 验证2失败: 读到了 theUnsafe = " + u);
        } catch (SecurityException e) {
            log("✓ 验证2: theUnsafe 读取被拦截 — " + e.getMessage());
        } catch (Exception e) {
            log("✓ 验证2: 被拦截 (" + e.getClass().getSimpleName() + ")");
        }

        // 验证3: 正常生态不受损 —— 模拟上次崩溃点
        //        WindowsWatchService 内部走 jdk.internal.misc.Unsafe.getUnsafe()（全JDK栈帧）
        try {
            java.nio.file.FileSystems.getDefault().newWatchService().close();
            log("✓ 验证3: WatchService 正常（JDK 内部 Unsafe 访问未被误伤）");
        } catch (Throwable t) {
            logErr("✗ 验证3失败: WatchService 异常 — " + t);
        }
    }

    // ==================== Transformer：全部内联注入 ====================

    private static final class InlineGuardTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain pd,
                                byte[] classfileBuffer) throws IllegalClassFormatException {

            if (className == null) return null;

            boolean isSunUnsafe      = "sun/misc/Unsafe".equals(className);
            boolean isJdkUnsafe      = "jdk/internal/misc/Unsafe".equals(className);
            boolean isAccessibleObj  = "java/lang/reflect/AccessibleObject".equals(className);
            boolean isField          = "java/lang/reflect/Field".equals(className);

            if (!isSunUnsafe && !isJdkUnsafe && !isAccessibleObj && !isField) return null;

            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                // COMPUTE_FRAMES: 插入代码后指令偏移全变，必须重算 StackMapTable
                ClassWriter cw = new ClassWriter(cr,
                        ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name,
                                                     String desc, String signature, String[] exceptions) {

                        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

                        // ── 拦截点1: Unsafe 私有构造函数 → 无条件完整栈检查 ──
                        //    JDK 内部 new Unsafe() 全是JDK帧→放行；敌方 newInstance→拦截
                        if ((isSunUnsafe || isJdkUnsafe) && "<init>".equals(name)) {
                            log("注入: " + className + ".<init> → 完整栈检查");
                            return new InlineCheckAdvice(mv, access, name, desc,
                                    Mode.UNCONDITIONAL, null);
                        }

                        // ── 拦截点2: Unsafe.getUnsafe() → 无条件完整栈检查 ──
                        //    jdk.internal 版本无CallerSensitive保护，必须拦！
                        //    sun.misc 版本有天然保护，双保险
                        if ((isSunUnsafe || isJdkUnsafe) && "getUnsafe".equals(name)) {
                            log("注入: " + className + ".getUnsafe → 完整栈检查");
                            return new InlineCheckAdvice(mv, access, name, desc,
                                    Mode.UNCONDITIONAL, null);
                        }

                        // ── 拦截点3: setAccessible 家族 → 目标是Unsafe成员才检查 ──
                        //    敌方反射必经之路；普通类的 setAccessible 零开销放行
                        if (isAccessibleObj &&
                                ("setAccessible0".equals(name)
                                        || ("setAccessible".equals(name) && "(Z)V".equals(desc))
                                        || "trySetAccessible".equals(name))) {
                            log("注入: AccessibleObject." + name + " → 条件检查(仅Unsafe成员)");
                            return new InlineCheckAdvice(mv, access, name, desc,
                                    Mode.CHECK_ACCESSIBLE_TARGET, null);
                        }

                        // ── 拦截点4: Field.get → 仅 theUnsafe 字段才检查 ──
                        if (isField && "get".equals(name)
                                && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(desc)) {
                            log("注入: Field.get → 条件检查(仅theUnsafe)");
                            return new InlineCheckAdvice(mv, access, name, desc,
                                    Mode.CHECK_FIELD_THEUNSAFE, null);
                        }

                        return mv;
                    }
                };

                cr.accept(cv, 0);
                byte[] result = cw.toByteArray();
                injected.add(className);
                return result;

            } catch (Throwable t) {
                logErr("转换 " + className + " 失败(返回原字节码保平安): " + t);
                return null;
            }
        }
    }

    // ==================== 检查模式 ====================

    private enum Mode {
        /** 无条件做完整栈检查（Unsafe构造函数 / getUnsafe） */
        UNCONDITIONAL,
        /** 先判断 this 的 declaringClass 是否为 Unsafe 类，是才检查（setAccessible 家族） */
        CHECK_ACCESSIBLE_TARGET,
        /** 先判断 this 是否为 Unsafe.theUnsafe 字段，是才检查（Field.get） */
        CHECK_FIELD_THEUNSAFE
    }

    // ==================== ASM Advice：内联生成检查字节码 ====================

    /**
     * 核心思想：所有检查代码直接生成到目标方法体内，
     * 引用的 Throwable/StackTraceElement/String/SecurityException 全在 bootstrap 上。
     */
    private static final class InlineCheckAdvice extends AdviceAdapter {

        private final Mode mode;

        InlineCheckAdvice(MethodVisitor mv, int access, String name,
                          String desc, Mode mode, Void unused) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.mode = mode;
        }

        @Override
        protected void onMethodEnter() {
            switch (mode) {
                case UNCONDITIONAL:
                    emitTrustedStackCheck();
                    break;

                case CHECK_ACCESSIBLE_TARGET:
                    emitAccessibleTargetCheck();
                    break;

                case CHECK_FIELD_THEUNSAFE:
                    emitFieldTheUnsafeCheck();
                    break;
            }
        }

        // ──────────────────────────────────────────────
        // 模式A: 无条件完整栈检查
        // ──────────────────────────────────────────────

        /**
         * 生成等价于以下Java代码的字节码：
         *
         * StackTraceElement[] st = new Throwable().getStackTrace();
         * for (StackTraceElement e : st) {
         *     String cn = e.getClassName();
         *     if (cn.startsWith("java.") || ... JDK前缀 ...) continue; // 跳过JDK帧
         *     // 第一个业务帧
         *     if (cn.startsWith("net.minecraft.") || ... 白名单 ...) return; // 放行
         *     throw new SecurityException("[Guard] Unsafe访问被拦截: " + cn);
         * }
         * // 全是JDK帧 → 放行（fall through 到原方法体）
         */
        private void emitTrustedStackCheck() {
            int stLocal = newLocal(Type.getType("[Ljava/lang/StackTraceElement;"));
            int iLocal  = newLocal(Type.INT_TYPE);
            int cnLocal = newLocal(Type.getType("Ljava/lang/String;"));

            Label loopStart = new Label();
            Label loopEnd   = new Label(); // 遍历完 → 放行
            Label pass      = new Label(); // 白名单命中 → 放行
            Label nextIter  = new Label();

            // st = new Throwable().getStackTrace();
            visitTypeInsn(Opcodes.NEW, "java/lang/Throwable");
            visitInsn(Opcodes.DUP);
            visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "java/lang/Throwable", "<init>", "()V", false);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Throwable", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false);
            visitVarInsn(Opcodes.ASTORE, stLocal);

            // i = 0
            visitInsn(Opcodes.ICONST_0);
            visitVarInsn(Opcodes.ISTORE, iLocal);

            // loopStart:
            visitLabel(loopStart);
            // if (i >= st.length) goto loopEnd(放行)
            visitVarInsn(Opcodes.ILOAD, iLocal);
            visitVarInsn(Opcodes.ALOAD, stLocal);
            visitInsn(Opcodes.ARRAYLENGTH);
            visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

            // cn = st[i].getClassName();
            visitVarInsn(Opcodes.ALOAD, stLocal);
            visitVarInsn(Opcodes.ILOAD, iLocal);
            visitInsn(Opcodes.AALOAD);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StackTraceElement", "getClassName", "()Ljava/lang/String;", false);
            visitVarInsn(Opcodes.ASTORE, cnLocal);

            // JDK 帧跳过（java.lang.reflect.* 帧也因此被跳过）
            for (String p : JDK_PREFIXES) {
                emitStartsWithJump(cnLocal, p, nextIter);
            }

            // 白名单命中 → pass
            for (String p : TRUSTED_PREFIXES) {
                emitStartsWithJump(cnLocal, p, pass);
            }

            // 不在白名单 → 抛异常（消息带敌方类名）
            visitTypeInsn(Opcodes.NEW, "java/lang/SecurityException");
            visitInsn(Opcodes.DUP);
            visitLdcInsn("[AgentGuard] Unsafe 访问被拦截: ");
            visitVarInsn(Opcodes.ALOAD, cnLocal);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
            visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "java/lang/SecurityException", "<init>", "(Ljava/lang/String;)V", false);
            visitInsn(Opcodes.ATHROW);

            // nextIter: i++; goto loopStart
            visitLabel(nextIter);
            visitIincInsn(iLocal, 1);
            visitJumpInsn(Opcodes.GOTO, loopStart);

            // loopEnd / pass: 放行，fall through 到原方法体
            visitLabel(loopEnd);
            visitLabel(pass);
            // 不生成任何指令，自然落入原方法体
        }

        /** 生成: if (cn.startsWith(prefix)) goto target */
        private void emitStartsWithJump(int cnLocal, String prefix, Label target) {
            visitVarInsn(Opcodes.ALOAD, cnLocal);
            visitLdcInsn(prefix);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
            visitJumpInsn(Opcodes.IFNE, target);
        }

        // ──────────────────────────────────────────────
        // 模式B: setAccessible 家族的条件检查
        // ──────────────────────────────────────────────

        /**
         * 生成等价于：
         * if (this instanceof Field && declaringIsUnsafe(this)
         *  || this instanceof Method && declaringIsUnsafe(this)
         *  || this instanceof Constructor && declaringIsUnsafe(this)) {
         *     完整栈检查;
         * }
         * // 否则零开销放行
         */
        private void emitAccessibleTargetCheck() {
            Label isUnsafe = new Label(); // 是Unsafe成员 → 做完整检查
            Label done     = new Label(); // 放行

            Label notField        = new Label();
            Label notMethod       = new Label();
            Label notConstructor  = new Label();

            // ── Field 分支 ──
            visitVarInsn(Opcodes.ALOAD, 0);
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/reflect/Field");
            visitJumpInsn(Opcodes.IFEQ, notField);
            emitDeclaringIsUnsafeJump("java/lang/reflect/Field", isUnsafe);
            visitJumpInsn(Opcodes.GOTO, done); // 是Field但非Unsafe → done
            visitLabel(notField);

            // ── Method 分支 ──
            visitVarInsn(Opcodes.ALOAD, 0);
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/reflect/Method");
            visitJumpInsn(Opcodes.IFEQ, notMethod);
            emitDeclaringIsUnsafeJump("java/lang/reflect/Method", isUnsafe);
            visitJumpInsn(Opcodes.GOTO, done);
            visitLabel(notMethod);

            // ── Constructor 分支 ──
            visitVarInsn(Opcodes.ALOAD, 0);
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/reflect/Constructor");
            visitJumpInsn(Opcodes.IFEQ, notConstructor);
            emitDeclaringIsUnsafeJump("java/lang/reflect/Constructor", isUnsafe);
            visitJumpInsn(Opcodes.GOTO, done);
            visitLabel(notConstructor);
            visitJumpInsn(Opcodes.GOTO, done);

            // ── 是 Unsafe 成员 → 完整栈检查 ──
            visitLabel(isUnsafe);
            emitTrustedStackCheck();

            visitLabel(done);
            // fall through 到原方法体
        }

        /**
         * 生成: if (declaringClass((OwnerType)this).getName()
         *          .equals("sun.misc.Unsafe")
         *       || ... .equals("jdk.internal.misc.Unsafe")) goto target;
         * 否则 fall through
         */
        private void emitDeclaringIsUnsafeJump(String ownerType, Label target) {
            // sun.misc.Unsafe
            emitDeclaringNameEqualsJump(ownerType, "sun.misc.Unsafe", target);
            // jdk.internal.misc.Unsafe
            emitDeclaringNameEqualsJump(ownerType, "jdk.internal.misc.Unsafe", target);
        }

        /** 生成: if (((Owner)this).getDeclaringClass().getName().equals(name)) goto target */
        private void emitDeclaringNameEqualsJump(String ownerType, String name, Label target) {
            visitVarInsn(Opcodes.ALOAD, 0);
            visitTypeInsn(Opcodes.CHECKCAST, ownerType);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    ownerType, "getDeclaringClass", "()Ljava/lang/Class;", false);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class", "getName", "()Ljava/lang/String;", false);
            visitLdcInsn(name);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(Opcodes.IFNE, target);
        }

        // ──────────────────────────────────────────────
        // 模式C: Field.get 的条件检查
        // ──────────────────────────────────────────────

        /**
         * 生成等价于：
         * if (("sun.misc.Unsafe".equals(this.getDeclaringClass().getName())
         *       || "jdk.internal.misc.Unsafe".equals(...))
         *     && "theUnsafe".equals(this.getName())) {
         *     完整栈检查;
         * }
         * // 其他字段零开销放行（Field.get 是反射热路径）
         */
        private void emitFieldTheUnsafeCheck() {
            Label doCheck = new Label();
            Label done    = new Label();
            Label tryJdk  = new Label();
            Label nameOk1 = new Label();
            Label nameOk2 = new Label();

            // ── declaring == sun.misc.Unsafe ? ──
            visitVarInsn(Opcodes.ALOAD, 0);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/reflect/Field", "getDeclaringClass", "()Ljava/lang/Class;", false);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class", "getName", "()Ljava/lang/String;", false);
            visitLdcInsn("sun.misc.Unsafe");
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(Opcodes.IFNE, nameOk1);
            visitJumpInsn(Opcodes.GOTO, tryJdk);

            // ── sun.misc 命中 → 再查字段名 ──
            visitLabel(nameOk1);
            emitFieldNameIsTheUnsafeJump(doCheck);
            visitJumpInsn(Opcodes.GOTO, done);

            // ── declaring == jdk.internal.misc.Unsafe ? ──
            visitLabel(tryJdk);
            visitVarInsn(Opcodes.ALOAD, 0);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/reflect/Field", "getDeclaringClass", "()Ljava/lang/Class;", false);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class", "getName", "()Ljava/lang/String;", false);
            visitLdcInsn("jdk.internal.misc.Unsafe");
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(Opcodes.IFNE, nameOk2);
            visitJumpInsn(Opcodes.GOTO, done);

            visitLabel(nameOk2);
            emitFieldNameIsTheUnsafeJump(doCheck);
            visitJumpInsn(Opcodes.GOTO, done);

            // ── 是 theUnsafe → 完整栈检查 ──
            visitLabel(doCheck);
            emitTrustedStackCheck();

            visitLabel(done);
            // fall through 到原方法体
        }

        /** 生成: if ("theUnsafe".equals(this.getName())) goto target */
        private void emitFieldNameIsTheUnsafeJump(Label target) {
            visitLdcInsn("theUnsafe");
            visitVarInsn(Opcodes.ALOAD, 0);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/reflect/Field", "getName", "()Ljava/lang/String;", false);
            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            visitJumpInsn(Opcodes.IFNE, target);
        }
    }

    // ==================== 日志 ====================

    private static void log(String msg) {
        System.out.println("[UnsafeGuard] " + msg);
    }

    private static void logErr(String msg) {
        System.err.println("[UnsafeGuard] " + msg);
    }
}
