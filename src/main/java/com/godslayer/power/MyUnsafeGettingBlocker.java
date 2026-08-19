package com.godslayer.power;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.godslayer.GodSlayerNative;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Unsafe 全面防护器
 *
 * 四重防线：
 *   防线1: 将 sun.misc.Unsafe.theUnsafe 静态字段值置为 null
 *   防线2: 将 sun.misc.Unsafe 私有构造函数替换为抛出 SecurityException
 *   防线3: 将 sun.misc.Unsafe.getUnsafe() 替换为抛出 SecurityException
 *   防线4: 对 jdk.internal.misc.Unsafe 执行相同操作
 *
 * 防护效果：
 *   - 反射获取 theUnsafe 字段 → 得到 null
 *   - 反射调用私有构造函数 → 抛出 SecurityException
 *   - 调用 getUnsafe() → 抛出 SecurityException
 *   - 所有其他路径全部被阻止
 *
 * 自身保护：
 *   在禁用前保存自己的 Unsafe 引用，后续仍可正常使用
 *
 * 运行环境: Minecraft 1.20.1 Forge / OpenJDK 17 / Win64
 */
public final class MyUnsafeGettingBlocker {

    // ==================== 状态 ====================

    private static final Instrumentation inst = GodSlayerNative.inst;

    private static final AtomicBoolean installed = new AtomicBoolean(false);

    /** 保存的 Unsafe 实例（供我们自己使用） */
    private static volatile sun.misc.Unsafe savedUnsafe;

    /** 保存的 Unsafe 类引用 */
    private static volatile Class<?> savedUnsafeClass;

    /** 已转换的类 */
    private static final Set<String> transformedClasses = ConcurrentHashMap.newKeySet();

    /** 已置空的字段 */
    private static final Set<String> nullifiedFields = ConcurrentHashMap.newKeySet();

    /** 已修改的构造函数 */
    private static final Set<String> blockedConstructors = ConcurrentHashMap.newKeySet();

    /** 已修改的方法 */
    private static final Set<String> blockedMethods = ConcurrentHashMap.newKeySet();

    // ==================== 公开 API ====================

    /**
     * 禁用所有 Unsafe 获取途径
     *
     * 必须在调用此方法前完成：
     *   1. 所有合法框架的 Unsafe 初始化
     *   2. 我们自己的 Unsafe 保存
     *
     * 调用后效果：
     *   - Unsafe.class.getDeclaredField("theUnsafe") → null
     *   - Unsafe.class.getDeclaredConstructor() → SecurityException on newInstance
     *   - Unsafe.getUnsafe() → SecurityException
     *   - jdk.internal.misc.Unsafe 所有路径 → SecurityException
     */
    public static void disableAllUnsafeAccess() {
        if (!installed.compareAndSet(false, true)) {
            log("[UnsafeGuard] 已安装，跳过重复初始化");
            return;
        }

        log("[UnsafeGuard] ═══════════════════════════════════════");
        log("[UnsafeGuard] === 开始安装 Unsafe 全面防护 ===");
        log("[UnsafeGuard] ═══════════════════════════════════════");

        // 步骤1: 保存我们自己的 Unsafe 实例（必须在最前面）
        if (!captureAndSaveUnsafe()) {
            log("[UnsafeGuard][FATAL] 无法保存 Unsafe 实例，防护无法安装");
            return;
        }

        // 步骤2: 注册 ASM Transformer（拦截后续加载和重转换）
        registerTransformer();

        // 步骤3: Retransform sun.misc.Unsafe（修改构造函数和 getUnsafe）
        retransformSunMiscUnsafe();

        // 步骤4: Retransform jdk.internal.misc.Unsafe
        retransformJdkInternalUnsafe();

        // 步骤5: 置空 theUnsafe 字段（必须在 retransform 之后，因为 retransform 会重置字段）
        nullifyTheUnsafeFields();

        // 步骤6: 验证防护效果
        verifyProtection();

        log("[UnsafeGuard] ═══════════════════════════════════════");
        log("[UnsafeGuard] === Unsafe 全面防护安装完成 ===");
        log("[UnsafeGuard] 防护清单:");
        log("[UnsafeGuard]   theUnsafe 字段置空: " + nullifiedFields.size() + " 个");
        log("[UnsafeGuard]   构造函数已阻止: " + blockedConstructors.size() + " 个");
        log("[UnsafeGuard]   方法已阻止: " + blockedMethods.size() + " 个");
        log("[UnsafeGuard] ═══════════════════════════════════════");
    }

    /**
     * 获取我们保存的 Unsafe 实例
     * 此方法在 disableAllUnsafeAccess() 调用后仍可正常使用
     */
    public static sun.misc.Unsafe getUnsafe() {
        return savedUnsafe;
    }

    /**
     * 检查防护是否已安装
     */
    public static boolean isInstalled() {
        return installed.get();
    }

    /**
     * 获取诊断信息
     */
    public static String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== UnsafeGuard Diagnostics ===\n");
        sb.append("Installed: ").append(installed.get()).append("\n");
        sb.append("Saved Unsafe: ").append(savedUnsafe != null ? "YES" : "NO").append("\n");
        sb.append("Transformed classes: ").append(transformedClasses).append("\n");
        sb.append("Nullified fields: ").append(nullifiedFields).append("\n");
        sb.append("Blocked constructors: ").append(blockedConstructors).append("\n");
        sb.append("Blocked methods: ").append(blockedMethods).append("\n");
        return sb.toString();
    }

    // ==================== 内部实现 ====================

    /**
     * 步骤1: 保存 Unsafe 实例
     * 必须在任何防护措施之前执行
     */
    private static boolean captureAndSaveUnsafe() {
        log("[UnsafeGuard] 步骤1: 保存 Unsafe 实例...");

        try {
            savedUnsafeClass = sun.misc.Unsafe.class;

            // 使用反射获取 theUnsafe
            Field theUnsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            savedUnsafe = (sun.misc.Unsafe) theUnsafeField.get(null);

            if (savedUnsafe == null) {
                log("[UnsafeGuard]   ✗ theUnsafe 字段为 null（可能已被其他防护处理）");
                return false;
            }

            log("[UnsafeGuard]   ✓ Unsafe 类: " + savedUnsafeClass.getName());
            log("[UnsafeGuard]   ✓ Unsafe 实例: " + savedUnsafe);
            log("[UnsafeGuard]   ✓ hashCode: " + System.identityHashCode(savedUnsafe));

            // 验证实例可用
            try {
                long offset = savedUnsafe.staticFieldOffset(theUnsafeField);
                log("[UnsafeGuard]   ✓ 实例验证成功, theUnsafe offset = " + offset);
            } catch (Exception e) {
                log("[UnsafeGuard]   ⚠ 实例验证警告: " + e.getMessage());
            }

            return true;

        } catch (Exception e) {
            log("[UnsafeGuard]   ✗ 保存失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 步骤2: 注册 ASM Transformer
     */
    private static void registerTransformer() {
        log("[UnsafeGuard] 步骤2: 注册 ASM Transformer...");

        try {
            inst.addTransformer(new UnsafeGuardTransformer(), true);
            log("[UnsafeGuard]   ✓ Transformer 注册成功 (canRetransform=true)");
        } catch (Exception e) {
            log("[UnsafeGuard]   ✗ 注册失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 步骤3: Retransform sun.misc.Unsafe
     */
    private static void retransformSunMiscUnsafe() {
        log("[UnsafeGuard] 步骤3: Retransform sun.misc.Unsafe...");

        try {
            Class<?> unsafeClass = sun.misc.Unsafe.class;

            log("[UnsafeGuard]   ClassLoader: " + unsafeClass.getClassLoader());
            log("[UnsafeGuard]   isModifiable: " + inst.isModifiableClass(unsafeClass));

            if (inst.isModifiableClass(unsafeClass)) {
                inst.retransformClasses(unsafeClass);

                if (transformedClasses.contains("sun/misc/Unsafe")) {
                    log("[UnsafeGuard]   ✓ sun.misc.Unsafe 转换成功");
                } else {
                    log("[UnsafeGuard]   ⚠ Transformer 未触发");
                }
            } else {
                log("[UnsafeGuard]   ✗ sun.misc.Unsafe 不可修改");
            }

        } catch (Exception e) {
            log("[UnsafeGuard]   ✗ Retransform 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 步骤4: Retransform jdk.internal.misc.Unsafe
     */
    private static void retransformJdkInternalUnsafe() {
        log("[UnsafeGuard] 步骤4: Retransform jdk.internal.misc.Unsafe...");

        try {
            Class<?> internalUnsafe = Class.forName("jdk.internal.misc.Unsafe", false,
                    ClassLoader.getPlatformClassLoader());

            log("[UnsafeGuard]   找到: " + internalUnsafe.getName());
            log("[UnsafeGuard]   ClassLoader: " + internalUnsafe.getClassLoader());
            log("[UnsafeGuard]   isModifiable: " + inst.isModifiableClass(internalUnsafe));

            if (inst.isModifiableClass(internalUnsafe)) {
                inst.retransformClasses(internalUnsafe);

                if (transformedClasses.contains("jdk/internal/misc/Unsafe")) {
                    log("[UnsafeGuard]   ✓ jdk.internal.misc.Unsafe 转换成功");
                } else {
                    log("[UnsafeGuard]   ⚠ Transformer 未触发");
                }
            }

        } catch (ClassNotFoundException e) {
            log("[UnsafeGuard]   ℹ jdk.internal.misc.Unsafe 未加载（首次加载时生效）");
        } catch (Exception e) {
            log("[UnsafeGuard]   ⚠ 处理失败: " + e.getMessage());
        }
    }

    /**
     * 步骤5: 置空 theUnsafe 字段
     * 注意：必须在 retransform 之后执行，因为 retransform 可能重置静态字段
     */
    private static void nullifyTheUnsafeFields() {
        log("[UnsafeGuard] 步骤5: 置空 theUnsafe 字段...");

        // 5.1 置空 sun.misc.Unsafe.theUnsafe
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");

            if (savedUnsafe != null) {
                Object base = savedUnsafe.staticFieldBase(field);
                long offset = savedUnsafe.staticFieldOffset(field);

                Object oldValue = savedUnsafe.getObject(base, offset);
                log("[UnsafeGuard]   sun.misc.Unsafe.theUnsafe 旧值: " + oldValue);

                // 置空
                savedUnsafe.putObject(base, offset, null);

                // 验证
                Object newValue = savedUnsafe.getObject(base, offset);
                if (newValue == null) {
                    log("[UnsafeGuard]   ✓ sun.misc.Unsafe.theUnsafe 已置空");
                    nullifiedFields.add("sun.misc.Unsafe.theUnsafe");
                } else {
                    log("[UnsafeGuard]   ✗ 置空失败，当前值: " + newValue);
                }
            }

        } catch (Exception e) {
            log("[UnsafeGuard]   ✗ 置空 sun.misc.Unsafe.theUnsafe 失败: " + e.getMessage());
        }

        // 5.2 置空 jdk.internal.misc.Unsafe.theUnsafe（如果存在）
        try {
            Class<?> internalUnsafe = Class.forName("jdk.internal.misc.Unsafe", false,
                    ClassLoader.getPlatformClassLoader());
            Field field = internalUnsafe.getDeclaredField("theUnsafe");

            if (savedUnsafe != null) {
                Object base = savedUnsafe.staticFieldBase(field);
                long offset = savedUnsafe.staticFieldOffset(field);

                Object oldValue = savedUnsafe.getObject(base, offset);
                log("[UnsafeGuard]   jdk.internal.misc.Unsafe.theUnsafe 旧值: " + oldValue);

                // 置空
                savedUnsafe.putObject(base, offset, null);

                Object newValue = savedUnsafe.getObject(base, offset);
                if (newValue == null) {
                    log("[UnsafeGuard]   ✓ jdk.internal.misc.Unsafe.theUnsafe 已置空");
                    nullifiedFields.add("jdk.internal.misc.Unsafe.theUnsafe");
                }
            }

        } catch (ClassNotFoundException e) {
            log("[UnsafeGuard]   ℹ jdk.internal.misc.Unsafe 未加载");
        } catch (Exception e) {
            log("[UnsafeGuard]   ⚠ 置空 jdk.internal 失败: " + e.getMessage());
        }
    }

    /**
     * 步骤6: 验证防护效果
     */
    private static void verifyProtection() {
        log("[UnsafeGuard] 步骤6: 验证防护效果...");

        int passed = 0;
        int total = 4;

        // 验证1: 反射获取 theUnsafe 应该得到 null
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object result = f.get(null);

            if (result == null) {
                log("[UnsafeGuard]   ✓ 验证1通过: theUnsafe 返回 null");
                passed++;
            } else {
                log("[UnsafeGuard]   ✗ 验证1失败: theUnsafe = " + result);
            }
        } catch (Exception e) {
            log("[UnsafeGuard]   ✓ 验证1通过: 反射被阻止 (" + e.getClass().getSimpleName() + ")");
            passed++;
        }

        // 验证2: 调用 getUnsafe() 应该抛异常
        try {
            sun.misc.Unsafe.getUnsafe();
            log("[UnsafeGuard]   ✗ 验证2失败: getUnsafe() 未抛异常");
        } catch (SecurityException e) {
            log("[UnsafeGuard]   ✓ 验证2通过: getUnsafe() 抛出 SecurityException");
            passed++;
        } catch (Exception e) {
            log("[UnsafeGuard]   ✓ 验证2通过: getUnsafe() 抛出 " + e.getClass().getSimpleName());
            passed++;
        }

        // 验证3: 通过构造函数创建实例应该抛异常
        try {
            java.lang.reflect.Constructor<?> ctor =
                    sun.misc.Unsafe.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object result = ctor.newInstance();

            log("[UnsafeGuard]   ✗ 验证3失败: 构造函数创建了新实例: " + result);
        } catch (SecurityException e) {
            log("[UnsafeGuard]   ✓ 验证3通过: 构造函数抛出 SecurityException");
            passed++;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) {
                log("[UnsafeGuard]   ✓ 验证3通过: 构造函数抛出 SecurityException (wrapped)");
                passed++;
            } else {
                log("[UnsafeGuard]   ✓ 验证3通过: 构造函数抛出 " +
                        (cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName()));
                passed++;
            }
        }

        // 验证4: 我们自己保存的实例仍然可用
        if (savedUnsafe != null) {
            log("[UnsafeGuard]   ✓ 验证4通过: 保存的实例仍可用");
            passed++;
        } else {
            log("[UnsafeGuard]   ✗ 验证4失败: 保存的实例不可用");
        }

        log("[UnsafeGuard] 验证结果: " + passed + "/" + total + " 通过");

        if (passed < total) {
            log("[UnsafeGuard] ⚠ 部分验证未通过，请检查日志");
        }
    }

    // ==================== Transformer 实现 ====================

    /**
     * ASM Transformer：修改 Unsafe 类
     *
     * 修改内容：
     *   1. 所有私有构造函数 → 抛出 SecurityException
     *   2. getUnsafe() 方法 → 抛出 SecurityException
     *   3. getUnsafeInternal() 方法（如果存在）→ 抛出 SecurityException
     */
    private static class UnsafeGuardTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {

            if (className == null) return null;

            boolean isSunMiscUnsafe = "sun/misc/Unsafe".equals(className);
            boolean isJdkInternalUnsafe = "jdk/internal/misc/Unsafe".equals(className);

            if (!isSunMiscUnsafe && !isJdkInternalUnsafe) {
                return null;
            }

            String targetName = isSunMiscUnsafe ? "sun.misc.Unsafe" : "jdk.internal.misc.Unsafe";

            log("[UnsafeGuard] ═══ 拦截: " + targetName + " ═══");
            log("[UnsafeGuard]   ClassLoader: " + loader);
            log("[UnsafeGuard]   classBeingRedefined: " + classBeingRedefined);
            log("[UnsafeGuard]   原始字节码大小: " + classfileBuffer.length);

            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr,
                        ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name,
                                                     String descriptor, String signature, String[] exceptions) {

                        MethodVisitor mv = super.visitMethod(access, name, descriptor,
                                signature, exceptions);

                        // ========== 1. 拦截所有构造函数 ==========
                        if ("<init>".equals(name)) {
                            log("[UnsafeGuard] ✅ 拦截构造函数: <init>" + descriptor);
                            blockedConstructors.add(targetName + ".<init>" + descriptor);
                            return new BlockConstructorAdvice(mv, access, name, descriptor, targetName);
                        }

                        // ========== 2. 拦截 getUnsafe() ==========
                        if ("getUnsafe".equals(name) &&
                                (descriptor.contains("Unsafe") || descriptor.contains("unsafe"))) {
                            log("[UnsafeGuard] ✅ 拦截方法: getUnsafe()" + descriptor);
                            blockedMethods.add(targetName + ".getUnsafe()");
                            return new BlockMethodAdvice(mv, access, name, descriptor,
                                    targetName, "getUnsafe");
                        }

                        // ========== 3. 拦截 getUnsafeInternal() ==========
                        if ("getUnsafeInternal".equals(name)) {
                            log("[UnsafeGuard] ✅ 拦截方法: getUnsafeInternal()" + descriptor);
                            blockedMethods.add(targetName + ".getUnsafeInternal()");
                            return new BlockMethodAdvice(mv, access, name, descriptor,
                                    targetName, "getUnsafeInternal");
                        }

                        // ========== 4. 拦截其他可能的获取方法 ==========
                        // 一些 Unsafe 类可能有其他获取实例的静态方法
                        if ((access & Opcodes.ACC_STATIC) != 0 &&
                                descriptor.startsWith("()L") &&
                                (name.toLowerCase().contains("unsafe") ||
                                        name.toLowerCase().contains("get"))) {

                            // 检查返回类型是否是 Unsafe 自身
                            String returnType = descriptor.substring(2, descriptor.length() - 1);
                            if (returnType.contains("Unsafe")) {
                                log("[UnsafeGuard] ✅ 拦截静态获取方法: " + name + descriptor);
                                blockedMethods.add(targetName + "." + name + "()");
                                return new BlockMethodAdvice(mv, access, name, descriptor,
                                        targetName, name);
                            }
                        }

                        return mv;
                    }
                };

                cr.accept(cv, 0);
                byte[] result = cw.toByteArray();
                transformedClasses.add(className);

                log("[UnsafeGuard] ✓ 转换完成: " + targetName);
                log("[UnsafeGuard]   新字节码大小: " + result.length);
                log("[UnsafeGuard] ═════════════════════════════════");

                return result;

            } catch (Exception e) {
                log("[UnsafeGuard] ✗ ASM 转换失败: " + targetName + " - " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }
    }

    // ==================== ASM Advice 适配器 ====================

    /**
     * 阻止构造函数的 Advice
     * 在构造函数入口直接抛出 SecurityException
     */
    private static class BlockConstructorAdvice extends AdviceAdapter {

        private final String targetClass;

        protected BlockConstructorAdvice(MethodVisitor mv, int access,
                                         String name, String desc, String targetClass) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.targetClass = targetClass;
        }

        @Override
        protected void onMethodEnter() {
            // 直接在构造函数入口抛出异常
            // 这会阻止任何通过反射调用构造函数创建新实例的尝试
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/SecurityException");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(
                    "[UnsafeGuard] Unsafe 实例化被禁止。" +
                            "类: " + targetClass + "。" +
                            "此 JVM 已启用安全防护。"
            );
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "java/lang/SecurityException", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(Opcodes.ATHROW);
            // 注意：抛出异常后，原构造函数体不会执行
        }
    }

    /**
     * 阻止静态获取方法的 Advice
     */
    private static class BlockMethodAdvice extends AdviceAdapter {

        private final String targetClass;
        private final String methodName;

        protected BlockMethodAdvice(MethodVisitor mv, int access,
                                    String name, String desc, String targetClass, String methodName) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.targetClass = targetClass;
            this.methodName = methodName;
        }

        @Override
        protected void onMethodEnter() {
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/SecurityException");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(
                    "[UnsafeGuard] Unsafe 获取被禁止。" +
                            "方法: " + targetClass + "." + methodName + "()" + "。" +
                            "此 JVM 已启用安全防护。"
            );
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "java/lang/SecurityException", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(Opcodes.ATHROW);
        }
    }

    // ==================== 工具方法 ====================

    private static void log(String message) {
        System.out.println(message);

        // 尝试写入 Forge 日志
        try {
            Class<?> loggerClass = Class.forName("com.mojang.logging.LogUtils");
            Object logger = loggerClass.getMethod("getLogger", String.class)
                    .invoke(null, "UnsafeGuard");
            loggerClass.getMethod("info", String.class).invoke(logger, message);
        } catch (Throwable ignored) {
        }
    }
}
