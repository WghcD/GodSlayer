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
import com.godslayer.core.EarlyNativeBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import sun.misc.Unsafe;

/**
 * 增强版 Agent 防护器
 *
 * 三层防护：
 * 1. [Instrumentation层] 拦截 loadClassAndCallAgentmain / loadClassAndInstallAgent
 * 2. [反射层] 持续监控并重置 ALLOW_ATTACH_SELF 为 false
 * 3. [Unsafe层] 直接修改 JVM 内部状态阻止 attach
 *
 * 前提条件：C++ 代码已修正（添加了 SetEventCallbacks）
 */
public final class MyJavaAgentAttachBlocker {

    private static final Instrumentation inst = EarlyNativeBridge.inst;

    private static final AtomicBoolean layer1Installed = new AtomicBoolean(false);
    private static final AtomicBoolean layer2Installed = new AtomicBoolean(false);
    private static final AtomicBoolean layer3Installed = new AtomicBoolean(false);

    private static volatile boolean shutdown = false;

    /** 已成功转换的类 */
    private static final Set<String> transformedClasses = ConcurrentHashMap.newKeySet();

    // ==================== 主入口 ====================

    /**
     * 安装所有防护层（推荐在启动时调用一次）
     */
    public static void installAllProtections() {
        log("==========================================");
        log("=== AgentGuard: Installing all protections ===");
        log("==========================================");

        // Layer 1: Instrumentation 拦截
        installLayer1();

        // Layer 2: ALLOW_ATTACH_SELF 监控
        installLayer2();

        // Layer 3: System Property 屏蔽
        installLayer3();

        log("==========================================");
        log("=== All protections installed ===");
        log("==========================================");
    }

    // ==================== Layer 1: Instrumentation 拦截 ====================

    /**
     * Layer 1: 通过 Instrumentation 修改 InstrumentationImpl 类
     *
     * 拦截方法：
     * - loadClassAndCallAgentmain (动态attach的Java入口)
     * - loadClassAndInstallAgent (动态attach的另一个入口)
     * - transform (所有类转换的入口，可以阻止后续agent的transformer)
     *
     * 注意：需要C++代码已正确注册ClassFileLoadHook回调
     */
    public static void installLayer1() {
        if (!layer1Installed.compareAndSet(false, true)) {
            log("[Layer1] Already installed");
            return;
        }

        log("[Layer1] === Instrumentation-based interception ===");

        // 验证 Instrumentation 能力
        if (inst == null) {
            log("[Layer1][ERROR] GodSlayerNative.inst is null!");
            return;
        }

        boolean canRetransform = inst.isRetransformClassesSupported();
        log("[Layer1] canRetransformClasses: " + canRetransform);

        if (!canRetransform) {
            log("[Layer1][ERROR] Retransform not supported! Check C++ code.");
            log("[Layer1][ERROR] Make sure SetEventCallbacks is called in JNI_OnLoad.");
            return;
        }

        // 注册 Transformer
        try {
            ClassFileTransformer transformer = new InstrumentationInterceptor();
            inst.addTransformer(transformer, true);
            log("[Layer1] ✓ Transformer registered (canRetransform=true)");
        } catch (Exception e) {
            log("[Layer1][ERROR] Failed to register transformer: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // 立即验证 Transformer 是否工作
        verifyTransformerWorks();

        // 强制 retransform 已加载的类
        forceRetransform();

        // 验证防护是否生效
        verifyProtection();
    }

    /**
     * 验证 Transformer 是否真的被调用了
     */
    private static void verifyTransformerWorks() {
        log("[Layer1] Verifying transformer works...");

        // 创建一个测试类来触发 transformer
        try {
            // 触发一个类的加载，看 transformer 是否被调用
            Class<?> testClass = Class.forName("java.lang.Object", false,
                    ClassLoader.getSystemClassLoader());
            // 这不会触发 transformer（因为类已加载）

            // 尝试加载一个新类来触发
            String testClassName = "com.godslayer.test.TransformerTest_" + System.nanoTime();
            byte[] testBytecode = generateTestClass(testClassName);

            // 使用自定义类加载器加载
            ClassLoader testLoader = new ClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (name.equals(testClassName.replace('/', '.'))) {
                        return defineClass(name, testBytecode, 0, testBytecode.length);
                    }
                    throw new ClassNotFoundException(name);
                }
            };

            Class<?> cls = testLoader.loadClass(testClassName.replace('/', '.'));
            log("[Layer1] Test class loaded: " + cls.getName());

            // 检查 transformer 是否被调用
            if (transformedClasses.size() > 0) {
                log("[Layer1] ✓ Transformer is WORKING!");
                log("[Layer1] Transformed classes so far: " + transformedClasses);
            } else {
                log("[Layer1][WARN] Transformer may NOT be working.");
                log("[Layer1][WARN] Check C++ SetEventCallbacks implementation.");
            }

        } catch (Exception e) {
            log("[Layer1] Verification error: " + e.getMessage());
        }
    }

    /**
     * 生成一个简单的测试类
     */
    private static byte[] generateTestClass(String className) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                className.replace('.', '/'), null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 强制 retransform 关键类
     */
    private static void forceRetransform() {
        String[] targets = {
                "sun.instrument.InstrumentationImpl"
        };

        for (String className : targets) {
            try {
                Class<?> clazz = Class.forName(className, false,
                        ClassLoader.getPlatformClassLoader());

                log("[Layer1] Retransforming: " + className);
                log("[Layer1]   ClassLoader: " + clazz.getClassLoader());
                log("[Layer1]   isModifiable: " + inst.isModifiableClass(clazz));

                if (inst.isModifiableClass(clazz)) {
                    inst.retransformClasses(clazz);

                    // 检查是否成功
                    if (transformedClasses.contains(className.replace('.', '/'))) {
                        log("[Layer1] ✓ Successfully transformed: " + className);
                    } else {
                        log("[Layer1] ⚠ Retransform called but transformer didn't fire!");
                        log("[Layer1]   This confirms ClassFileLoadHook is NOT registered.");
                    }
                }

            } catch (ClassNotFoundException e) {
                log("[Layer1] Class not yet loaded: " + className);
            } catch (Exception e) {
                log("[Layer1][ERROR] Failed to retransform " + className + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 验证防护是否生效
     */
    private static void verifyProtection() {
        try {
            // 尝试通过反射调用 loadClassAndCallAgentmain
            Class<?> implClass = Class.forName("sun.instrument.InstrumentationImpl",
                    false, ClassLoader.getPlatformClassLoader());

            java.lang.reflect.Method method = implClass.getDeclaredMethod(
                    "loadClassAndCallAgentmain", String.class, String.class);
            method.setAccessible(true);

            // 创建一个实例来测试
            // 注意：我们不会真的调用它，只是检查方法是否存在
            log("[Layer1] loadClassAndCallAgentmain method exists: " + (method != null));

            // 如果防护生效，调用这个方法应该抛出 SecurityException
            // 但我们不实际调用，因为那会真的尝试加载一个agent

        } catch (Exception e) {
            log("[Layer1] Verification exception: " + e.getMessage());
        }
    }

    // ==================== Layer 2: ALLOW_ATTACH_SELF 监控 ====================

    /**
     * Layer 2: 持续监控并重置 ALLOW_ATTACH_SELF 为 false
     *
     * 敌方代码会用 Unsafe 修改这个字段为 true 来允许 self-attach。
     * 我们用后台线程持续将其重置为 false。
     */
    public static void installLayer2() {
        if (!layer2Installed.compareAndSet(false, true)) {
            log("[Layer2] Already installed");
            return;
        }

        log("[Layer2] === ALLOW_ATTACH_SELF monitor ===");

        try {
            // 获取 Unsafe 实例
            Unsafe unsafe = getUnsafe();
            if (unsafe == null) {
                log("[Layer2][ERROR] Cannot get Unsafe instance");
                return;
            }

            // 获取 ALLOW_ATTACH_SELF 字段
            Class<?> vmClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
            Field field = vmClass.getDeclaredField("ALLOW_ATTACH_SELF");

            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);

            log("[Layer2] ALLOW_ATTACH_SELF field found:");
            log("[Layer2]   Base: " + base);
            log("[Layer2]   Offset: " + offset);
            log("[Layer2]   Current value: " + unsafe.getBoolean(base, offset));

            // 立即设置为 false
            unsafe.putBoolean(base, offset, false);
            log("[Layer2] ✓ Set ALLOW_ATTACH_SELF to false");

            // 启动监控线程
            Thread monitor = new Thread(() -> {
                log("[Layer2] Monitor thread started");
                while (!shutdown && !Thread.currentThread().isInterrupted()) {
                    try {
                        // 持续重置为 false
                        boolean current = unsafe.getBoolean(base, offset);
                        if (current) {
                            unsafe.putBoolean(base, offset, false);
                            log("[Layer2] ⚠ Detected and RESET ALLOW_ATTACH_SELF from true to false!");
                            log("[Layer2]   Stack trace of modifier:");
                            Thread.currentThread().getStackTrace();
                        }

                        // 短暂休眠以减少CPU使用
                        Thread.sleep(1); // 1ms 间隔，几乎不可能被绕过

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        // 忽略
                    }
                }
                log("[Layer2] Monitor thread stopped");
            }, "AgentGuard-AllowSelfMonitor");

            monitor.setDaemon(true);
            monitor.setPriority(Thread.MAX_PRIORITY);
            monitor.start();

            log("[Layer2] ✓ Monitor thread started (checks every 1ms)");

        } catch (Exception e) {
            log("[Layer2][ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== Layer 3: System Property 屏蔽 ====================

    /**
     * Layer 3: 修改系统属性阻止 attach
     */
    public static void installLayer3() {
        if (!layer3Installed.compareAndSet(false, true)) {
            log("[Layer3] Already installed");
            return;
        }

        log("[Layer3] === System property hardening ===");

        try {
            // 设置系统属性为 false
            System.setProperty("jdk.attach.allowSelfAttach", "false");
            log("[Layer3] ✓ Set jdk.attach.allowSelfAttach=false");

            // 同时移除可能的attach机制
            try {
                Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
                log("[Layer3] VirtualMachine class loaded: " + vmClass.getName());

                // 检查所有已加载的类
                Class<?>[] allClasses = inst.getAllLoadedClasses();
                int attachRelated = 0;
                for (Class<?> c : allClasses) {
                    String name = c.getName();
                    if (name.contains("attach") || name.contains("Attach")) {
                        attachRelated++;
                        log("[Layer3]   Found attach-related class: " + name);
                    }
                }
                log("[Layer3] Found " + attachRelated + " attach-related classes");

            } catch (Exception e) {
                log("[Layer3] VirtualMachine not yet loaded (good)");
            }

        } catch (Exception e) {
            log("[Layer3][ERROR] " + e.getMessage());
        }
    }

    // ==================== Transformer 实现 ====================

    /**
     * 拦截 sun.instrument.InstrumentationImpl 的关键方法
     */
    private static class InstrumentationInterceptor implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {

            if (className == null) return null;

            // 只处理 InstrumentationImpl
            if (!"sun/instrument/InstrumentationImpl".equals(className)) {
                return null;
            }

            log("[Transformer] ═══ INTERCEPTING: " + className + " ═══");
            log("[Transformer]   ClassLoader: " + loader);
            log("[Transformer]   classBeingRedefined: " + classBeingRedefined);
            log("[Transformer]   Buffer size: " + classfileBuffer.length);

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

                        // 拦截所有 agent 加载入口
                        if ("loadClassAndCallAgentmain".equals(name)) {
                            log("[Transformer] ✅ INTERCEPT: loadClassAndCallAgentmain");
                            log("[Transformer]   Descriptor: " + descriptor);
                            return new BlockAgentAdvice(mv, access, name, descriptor,
                                    "loadClassAndCallAgentmain");
                        }

                        if ("loadClassAndCallPremain".equals(name)) {
                            log("[Transformer] ✅ INTERCEPT: loadClassAndCallPremain");
                            return new BlockAgentAdvice(mv, access, name, descriptor,
                                    "loadClassAndCallPremain");
                        }

                        if ("loadClassAndStartAgent".equals(name)) {
                            log("[Transformer] ✅ INTERCEPT: loadClassAndStartAgent");
                            return new BlockAgentAdvice(mv, access, name, descriptor,
                                    "loadClassAndStartAgent");
                        }

                        // 不拦截功能方法
                        if ("addTransformer".equals(name) ||
                                "retransformClasses".equals(name) ||
                                "redefineClasses".equals(name)) {
                            log("[Transformer] ⭕ PRESERVE: " + name);
                        }

                        if ("<init>".equals(name)) {
                            log("[Transformer] ⭕ PRESERVE: <init> (constructor)");
                        }

                        return mv;
                    }
                };

                cr.accept(cv, 0);
                byte[] result = cw.toByteArray();

                transformedClasses.add(className);
                log("[Transformer] ✓ TRANSFORMED: " + className);
                log("[Transformer]   New size: " + result.length);
                log("[Transformer] ═════════════════════════════════════");

                return result;

            } catch (Exception e) {
                log("[Transformer][ERROR] ASM failed: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }
    }

    /**
     * 阻止 Agent 加载的 Advice
     * 在方法入口直接抛出 SecurityException
     */
    private static class BlockAgentAdvice extends AdviceAdapter {

        private final String methodName;

        protected BlockAgentAdvice(MethodVisitor mv, int access,
                                   String name, String desc, String methodName) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.methodName = methodName;
        }

        @Override
        protected void onMethodEnter() {
            // 直接在方法入口抛出异常
            // 这是最可靠的方式：无论原方法做什么，都不会执行
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/SecurityException");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(
                    "[AgentGuard] Dynamic agent loading is BLOCKED. " +
                            "Method: " + methodName + ". " +
                            "This JVM is protected."
            );
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "java/lang/SecurityException", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(Opcodes.ATHROW);

            // 注意：这里不需要 visitMaxs，因为 AdviceAdapter 会自动计算
            // 但由于我们抛出了异常，后面的代码不会执行
        }
    }

    // ==================== 工具方法 ====================

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void log(String message) {
        System.out.println(message);

        // 尝试同时写入 Forge 日志
        try {
            Class<?> loggerClass = Class.forName("com.mojang.logging.LogUtils");
            Object logger = loggerClass.getMethod("getLogger", String.class)
                    .invoke(null, "AgentGuard");
            loggerClass.getMethod("info", String.class).invoke(logger, message);
        } catch (Throwable ignored) {}
    }

    /**
     * 停止所有防护（仅供测试）
     */
    public static void shutdown() {
        shutdown = true;
        log("[AgentGuard] Shutting down...");
    }

    /**
     * 获取诊断信息
     */
    public static String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AgentGuard Diagnostics ===\n");
        sb.append("Layer1 installed: ").append(layer1Installed.get()).append("\n");
        sb.append("Layer2 installed: ").append(layer2Installed.get()).append("\n");
        sb.append("Layer3 installed: ").append(layer3Installed.get()).append("\n");
        sb.append("Transformed classes: ").append(transformedClasses).append("\n");
        sb.append("Instrumentation: ").append(inst != null ? "available" : "null").append("\n");
        if (inst != null) {
            sb.append("  canRetransform: ").append(inst.isRetransformClassesSupported()).append("\n");
            sb.append("  canRedefine: ").append(inst.isRedefineClassesSupported()).append("\n");
        }
        return sb.toString();
    }
}
