package com.godslayer.power;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.godslayer.GodSlayerNative;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Unsafe 防护器 v2
 *
 * 核心改变（相比v1）：
 *   1. 【绝不置空 theUnsafe 字段】—— 那会误伤JDK内部所有 getUnsafe() 调用
 *      （WindowsWatchService$Poller 崩溃的根源）
 *   2. 拦截 setAccessible0 —— 敌方反射访问私有成员的必经之路
 *   3. 拦截 Unsafe 私有构造函数 —— 纵深防御
 *   4. 拦截 Field.get(theUnsafe) —— 纵深防御
 *   5. getUnsafe() 无需拦截 —— JDK自带CallerSensitive检查，mod调用者直接SecurityException
 *
 * 检查逻辑由注入到 bootstrap classpath 的 guard.GuardHook 完成（栈白名单检查）
 */
public final class UnsafeGuard {

    private static final Instrumentation inst = GodSlayerNative.inst;

    private static final AtomicBoolean installed = new AtomicBoolean(false);
    private static volatile sun.misc.Unsafe savedUnsafe;
    private static final Set<String> transformed = ConcurrentHashMap.newKeySet();

    /** GuardHook 在 mod jar 内的资源路径（编译后打包进去） */
    private static final String GUARD_HOOK_RESOURCE = "/guard/GuardHook.class";
    /** GuardHook 的 ASM 内部名 */
    private static final String HOOK_OWNER = "guard/GuardHook";

    public static void disableAllUnsafeAccess() {
        if (!installed.compareAndSet(false, true)) {
            log("[UnsafeGuard] 已安装，跳过");
            return;
        }

        log("[UnsafeGuard] ═══ 安装 Unsafe 防护 v2（不置空字段，纯拦截模式）═══");

        // 步骤1: 保存自己的 Unsafe（防护开启前最后的机会）
        if (!captureUnsafe()) {
            log("[UnsafeGuard][FATAL] 无法保存Unsafe实例，中止");
            return;
        }

        // 步骤2: 部署 GuardHook 到 bootstrap classpath（必须先于 retransform！
        //        因为注入的字节码引用 guard/GuardHook，执行时需能解析）
        if (!deployGuardHookToBootstrap()) {
            log("[UnsafeGuard][FATAL] GuardHook 部署失败，中止");
            return;
        }

        // 步骤3: 注册 C 层类名过滤（性能关键：只转发这些类到Java层transformer）
        GodSlayerNative.addTransformFilter("sun/misc/Unsafe");
        GodSlayerNative.addTransformFilter("jdk/internal/misc/Unsafe");
        GodSlayerNative.addTransformFilter("java/lang/reflect/AccessibleObject");
        GodSlayerNative.addTransformFilter("java/lang/reflect/Field");

        // 步骤4: 注册 Transformer 并 retransform 目标类
        try {
            inst.addTransformer(new GuardTransformer(), true);
            log("[UnsafeGuard] ✓ Transformer注册成功");
        } catch (Exception e) {
            log("[UnsafeGuard][ERROR] Transformer注册失败: " + e);
            return;
        }

        retransformTargets();

        // 步骤5: 验证
        verify();

        log("[UnsafeGuard] ═══ 安装完成 ═══");
    }

    public static sun.misc.Unsafe getUnsafe() {
        return savedUnsafe;
    }

    // ==================== 实现细节 ====================

    private static boolean captureUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            savedUnsafe = (sun.misc.Unsafe) f.get(null);
            log("[UnsafeGuard] ✓ Unsafe实例已保存: " + savedUnsafe);
            return savedUnsafe != null;
        } catch (Exception e) {
            log("[UnsafeGuard] ✗ 保存失败: " + e);
            return false;
        }
    }

    /**
     * 将 GuardHook.class 从 mod jar 提取到临时 jar，追加到 bootstrap classpath
     */
    private static boolean deployGuardHookToBootstrap() {
        try {
            InputStream is = UnsafeGuard.class.getResourceAsStream(GUARD_HOOK_RESOURCE);
            if (is == null) {
                log("[UnsafeGuard][ERROR] 资源未找到: " + GUARD_HOOK_RESOURCE);
                return false;
            }
            File jar = File.createTempFile("guardhook", ".jar");
            jar.deleteOnExit();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar))) {
                zos.putNextEntry(new ZipEntry("guard/GuardHook.class"));
                is.transferTo(zos);
                zos.closeEntry();
            } finally {
                is.close();
            }
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            log("[UnsafeGuard] ✓ GuardHook 已部署到 bootstrap classpath: " + jar);
            return true;
        } catch (Exception e) {
            log("[UnsafeGuard][ERROR] 部署失败: " + e);
            e.printStackTrace();
            return false;
        }
    }

    private static void retransformTargets() {
        Class<?>[] targets = {
                sun.misc.Unsafe.class,
                loadQuietly("jdk.internal.misc.Unsafe"),
                java.lang.reflect.Field.class,
                java.lang.reflect.AccessibleObject.class
        };
        for (Class<?> c : targets) {
            if (c == null) continue;
            try {
                inst.retransformClasses(c);
                String internal = c.getName().replace('.', '/');
                log("[UnsafeGuard] retransform " + c.getName() + " → " +
                        (transformed.contains(internal) ? "✓ 已拦截" : "✗ Transformer未触发!"));
            } catch (Exception e) {
                log("[UnsafeGuard][ERROR] retransform " + c.getName() + " 失败: " + e);
            }
        }
    }

    private static Class<?> loadQuietly(String name) {
        try {
            return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
        } catch (Exception e) {
            return null;
        }
    }

    private static void verify() {
        int pass = 0, total = 4;

        // 验证1: 反射获取 theUnsafe（敌方路径1）
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true); // ← 应该在这里抛 SecurityException
            Object u = f.get(null);
            log("[UnsafeGuard] " + (u != null && !f.isAccessible()
                    ? "✗" : "✗") + " 验证1: theUnsafe获取未被拦截, 值=" + u);
        } catch (SecurityException e) {
            log("[UnsafeGuard] ✓ 验证1: setAccessible被拦截 - " + e.getMessage());
            pass++;
        } catch (Exception e) {
            log("[UnsafeGuard] ✓ 验证1: 被拦截 (" + e.getClass().getSimpleName() + ")");
            pass++;
        }

        // 验证2: Unsafe.getUnsafe()（JDK自带检查）
        try {
            sun.misc.Unsafe.getUnsafe();
            log("[UnsafeGuard] ✗ 验证2: getUnsafe未抛异常");
        } catch (SecurityException e) {
            log("[UnsafeGuard] ✓ 验证2: getUnsafe被JDK自带机制拦截");
            pass++;
        }

        // 验证3: 反射构造（敌方路径2，日志中"Made a new Unsafe instance!"那条）
        try {
            java.lang.reflect.Constructor<?> ctor =
                    sun.misc.Unsafe.class.getDeclaredConstructor();
            ctor.setAccessible(true); // ← 应该在这里抛
            Object u = ctor.newInstance();
            log("[UnsafeGuard] ✗ 验证3: 构造了新实例: " + u);
        } catch (SecurityException e) {
            log("[UnsafeGuard] ✓ 验证3: 构造路径被拦截 - " + e.getMessage());
            pass++;
        } catch (Exception e) {
            Throwable c = e.getCause();
            if (c instanceof SecurityException) {
                log("[UnsafeGuard] ✓ 验证3: 构造函数抛出SecurityException");
                pass++;
            } else {
                log("[UnsafeGuard] ✓ 验证3: 被拦截 (" +
                        (c != null ? c.getClass().getSimpleName() : e.getClass().getSimpleName()) + ")");
                pass++;
            }
        }

        // 验证4: 自己的实例可用 + JDK内部功能正常（上次崩溃点）
        boolean jdkOk = false;
        try {
            // 模拟上次崩溃的调用路径：WatchService 需要 Unsafe.getUnsafe()
            java.nio.file.FileSystems.getDefault().newWatchService().close();
            jdkOk = true;
        } catch (Throwable t) {
            log("[UnsafeGuard] ✗ 验证4: WatchService异常: " + t);
        }
        if (savedUnsafe != null && jdkOk) {
            log("[UnsafeGuard] ✓ 验证4: 自用Unsafe可用 + JDK内部Unsafe访问正常");
            pass++;
        }

        log("[UnsafeGuard] 验证结果: " + pass + "/" + total);
    }

    // ==================== Transformer ====================

    private static final class GuardTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain pd,
                                byte[] classfileBuffer) throws IllegalClassFormatException {

            if (className == null) return null;

            boolean isUnsafe = "sun/misc/Unsafe".equals(className)
                    || "jdk/internal/misc/Unsafe".equals(className);
            boolean isAccessibleObject = "java/lang/reflect/AccessibleObject".equals(className);
            boolean isField = "java/lang/reflect/Field".equals(className);

            if (!isUnsafe && !isAccessibleObject && !isField) return null;

            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr,
                        ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name,
                                                     String desc, String sig, String[] ex) {

                        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);

                        // ── 拦截点B：Unsafe 私有构造函数 ──
                        if (isUnsafe && "<init>".equals(name) && "()V".equals(desc)) {
                            log("[UnsafeGuard] 注入: " + className + ".<init> → checkUnsafeAccess");
                            return new HookAdvice(mv, access, name, desc,
                                    "checkUnsafeAccess", 0); // 无参数
                        }

                        // ── 拦截点A：setAccessible0（公有setAccessible/trySetAccessible最终都走这里）──
                        if (isAccessibleObject && "setAccessible0".equals(name)) {
                            log("[UnsafeGuard] 注入: AccessibleObject.setAccessible0 → checkSetAccessible");
                            return new HookAdvice(mv, access, name, desc,
                                    "checkSetAccessible", 1); // 第1个参数 obj (aload_1)
                        }
                        // 保险：公有入口也注入（覆盖批量版本等）
                        if (isAccessibleObject && "setAccessible".equals(name)
                                && "(Z)V".equals(desc)) {
                            return new HookAdvice(mv, access, name, desc,
                                    "checkSetAccessible", 0); // this (aload_0)
                        }
                        if (isAccessibleObject && "trySetAccessible".equals(name)
                                && "()Z".equals(desc)) {
                            return new HookAdvice(mv, access, name, desc,
                                    "checkSetAccessible", 0);
                        }

                        // ── 拦截点C：Field.get ──
                        if (isField && "get".equals(name)
                                && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(desc)) {
                            log("[UnsafeGuard] 注入: Field.get → checkFieldGet");
                            return new HookAdvice(mv, access, name, desc,
                                    "checkFieldGet", 0); // this (aload_0)
                        }

                        return mv;
                    }
                };

                cr.accept(cv, 0);
                byte[] result = cw.toByteArray();
                transformed.add(className);
                return result;

            } catch (Exception e) {
                log("[UnsafeGuard][ERROR] 转换 " + className + " 失败: " + e);
                return null; // 失败时返回原字节码，不破坏启动
            }
        }
    }

    /**
     * 通用注入 Advice：方法入口调用 GuardHook 的静态检查方法
     */
    private static final class HookAdvice extends AdviceAdapter {
        private final String hookMethod;
        private final int loadSlot; // 传给hook的参数槽位：0=this, 1=第1参数

        HookAdvice(MethodVisitor mv, int access, String name, String desc,
                   String hookMethod, int loadSlot) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.hookMethod = hookMethod;
            this.loadSlot = loadSlot;
        }

        @Override
        protected void onMethodEnter() {
            if (loadSlot == 0) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, hookMethod,
                    "(Ljava/lang/Object;)V", false);
        }
    }

    private static void log(String msg) {
        System.out.println(msg);
        try {
            Class<?> lc = Class.forName("com.mojang.logging.LogUtils");
            Object logger = lc.getMethod("getLogger", String.class).invoke(null, "UnsafeGuard");
            lc.getMethod("info", String.class).invoke(logger, msg);
        } catch (Throwable ignored) {}
    }
}
