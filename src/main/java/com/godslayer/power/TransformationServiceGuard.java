package com.godslayer.power;

import com.godslayer.GodSlayerNative;
import com.godslayer.core.EarlyNativeBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ITransformationService 拦截器
 *
 * 用法：在你自己的 ITransformationService 实现类的 static 块中调用：
 *   static {
 *       TransformationServiceGuard.install();
 *   }
 *
 * 前提：你的类必须被 BootstrapLauncher 的 ModuleClassLoader 加载，
 *       且在 ModLauncher 开始扫描服务之前执行 static 块。
 *
 * 效果：除白名单外，所有后续被发现的 ITransformationService：
 *   - initialize() → 方法体掏空
 *   - onLoad() → 方法体掏空
 *   - transformers() → 返回 List.of()（空容器）
 */
public final class TransformationServiceGuard {

    private static final Logger LOG = Logger.getLogger("TSGuard");

    /** 允许正常运行的包名前缀（这些包的 ITransformationService 不被拦截） */
    private static final Set<String> WHITELISTED_PACKAGES = Set.of(
            "net.minecraftforge",          // Forge 官方服务
            "cpw.mods.modlauncher",         // ModLauncher 自身（不能拦自己）
            "org.spongepowered",            // Mixin（可选，视需求调整）
            "com.godslayer"                 // 你自己的包
    );

    /** 已处理的类缓存，防止 retransform 循环 */
    private static final Set<String> processed = ConcurrentHashMap.newKeySet();

    private static volatile Instrumentation inst;

    private TransformationServiceGuard() {}

    /**
     * 安装拦截器。必须在 ITransformationService 实现类的 static 块中调用。
     */
    public static void install() {
        if (inst != null) return; // 幂等

        Instrumentation instrumentation = obtainInstrumentation();
        if (instrumentation == null) {
            throw new IllegalStateException("无法获取 Instrumentation。" +
                    "请确保此类被 BootstrapLauncher 的 ModuleClassLoader 加载，" +
                    "且 Forge 以 -javaagent 方式启动。");
        }
        inst = instrumentation;

        // 1) 注册 transformer，拦截后续所有类的定义/重定义
        inst.addTransformer(new ServiceGuardTransformer(), true);

        // 2) 扫描并处理当前已加载的所有 ITransformationService 实现
        sweepLoadedServices();

        LOG.info("TransformationServiceGuard 已安装");
    }

    // =========================================================================
    // Instrumentation 获取
    // =========================================================================

    /**
     * 从 BootstrapLauncher / BootstrapLauncherEnterprise 的静态字段中获取 Instrumentation。
     * Forge 启动时 bootstraplauncher.jar 作为 -javaagent 注入，
     * agentmain/premain 收到的 Instrumentation 被存储在静态字段中。
     */
    private static Instrumentation obtainInstrumentation() {
        if(EarlyNativeBridge.inst!=null){return EarlyNativeBridge.inst;}
        else if(GodSlayerNative.inst!=null){return GodSlayerNative.inst;}

        return null;
    }

    private static Instrumentation tryGetFromField(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className, false,
                    TransformationServiceGuard.class.getClassLoader());
            for (Field f : clazz.getDeclaredFields()) {
                if (Instrumentation.class.isAssignableFrom(f.getType())
                        && (fieldName.equals(f.getName()) || fieldName.isEmpty())) {
                    f.setAccessible(true);
                    Object value = f.get(null);
                    if (value instanceof Instrumentation) {
                        return (Instrumentation) value;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // 扫描已加载的服务
    // =========================================================================

    private static void sweepLoadedServices() {
        Class<?> serviceInterface = null;
        // ITransformationService 在 MC-BOOTSTRAP 类加载器中
        for (ClassLoader cl = TransformationServiceGuard.class.getClassLoader();
             cl != null && serviceInterface == null; cl = cl.getParent()) {
            try {
                serviceInterface = Class.forName(
                        "cpw.mods.modlauncher.api.ITransformationService", false, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        // 也尝试从 system classloader 找
        if (serviceInterface == null) {
            try {
                serviceInterface = Class.forName(
                        "cpw.mods.modlauncher.api.ITransformationService", false,
                        ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException ignored) {}
        }
        if (serviceInterface == null) {
            LOG.warning("未找到 ITransformationService 接口，无法扫描已加载服务");
            return;
        }

        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (serviceInterface.isAssignableFrom(c)
                    && !c.isInterface()
                    && !isWhitelisted(c.getName())
                    && !isSelf(c)) {
                targets.add(c);
            }
        }

        for (Class<?> c : targets) {
            if (processed.add(c.getName())) {
                try {
                    inst.retransformClasses(c);
                    LOG.info("已拦截 ITransformationService: " + c.getName());
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "retransform 失败: " + c.getName(), t);
                }
            }
        }
    }

    private static boolean isWhitelisted(String className) {
        for (String prefix : WHITELISTED_PACKAGES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isSelf(Class<?> c) {
        return c.getName().equals(TransformationServiceGuard.class.getName());
    }

    // =========================================================================
    // 字节码转换器
    // =========================================================================

    private static final class ServiceGuardTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (className == null || classfileBuffer == null) return null;

            try {
                // 快速预筛：检查是否引用了 ITransformationService
                if (!mightBeTransformationService(classfileBuffer)) {
                    return null;
                }

                // 解析字节码，检查是否实现了 ITransformationService
                ClassNode cn = read(classfileBuffer);
                if (!implementsTransformationService(cn)) {
                    return null;
                }

                // 检查白名单
                String binaryName = className.replace('/', '.');
                if (isWhitelisted(binaryName)) return null;

                // 执行字节码手术
                boolean changed = neuterService(cn);

                if (changed) {
                    LOG.info("拦截 ITransformationService 定义: " + binaryName
                            + " (loader=" + loader + ")");
                    return write(cn);
                }
                return null;

            } catch (Throwable t) {
                LOG.log(Level.WARNING, "transform 失败: " + className, t);
                return null; // 放行原始字节码
            }
        }

        /** 字节级预筛：查找 ITransformationService 引用 */
        private boolean mightBeTransformationService(byte[] buf) {
            // 查找常量池中的 "cpw/mods/modlauncher/api/ITransformationService"
            byte[] pattern = "cpw/mods/modlauncher/api/ITransformationService"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            return indexOf(buf, pattern) >= 0;
        }

        /** 检查 ClassNode 是否实现了 ITransformationService */
        private boolean implementsTransformationService(ClassNode cn) {
            // 检查接口列表
            for (String itf : cn.interfaces) {
                if (itf.equals("cpw/mods/modlauncher/api/ITransformationService")) {
                    return true;
                }
            }
            // 检查父类是否实现（处理抽象中间类的情况）
            if (cn.superName != null && !cn.superName.equals("java/lang/Object")) {
                String superBinary = cn.superName.replace('/', '.');
                try {
                    Class<?> superClass = Class.forName(superBinary, false,
                            TransformationServiceGuard.class.getClassLoader());
                    Class<?> serviceInterface = Class.forName(
                            "cpw.mods.modlauncher.api.ITransformationService", false,
                            TransformationServiceGuard.class.getClassLoader());
                    if (serviceInterface.isAssignableFrom(superClass)) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
            return false;
        }

        /** 对 ITransformationService 实现执行字节码手术 */
        private boolean neuterService(ClassNode cn) {
            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                switch (mn.name) {
                    case "initialize":
                        // void initialize(IEnvironment environment) → 掏空
                        if ("(Lcpw/mods/modlauncher/api/IEnvironment;)V".equals(mn.desc)) {
                            emptyBody(mn);
                            changed = true;
                        }
                        break;

                    case "onLoad":
                        // void onLoad(IEnvironment env, Set<String> otherServices) → 掏空
                        if ("(Lcpw/mods/modlauncher/api/IEnvironment;Ljava/util/Set;)V"
                                .equals(mn.desc)
                                || mn.desc.contains("IEnvironment")
                                && mn.desc.contains("Set")) {
                            emptyBody(mn);
                            changed = true;
                        }
                        break;

                    case "transformers":
                        // List<? extends ITransformer<?>> transformers() → 返回 List.of()
                        if ("()Ljava/util/List;".equals(mn.desc)) {
                            replaceWithEmptyList(mn);
                            changed = true;
                        }
                        break;
                }
            }
            return changed;
        }

        /** 掏空方法体：仅保留 RETURN */
        private void emptyBody(MethodNode mn) {
            InsnList code = new InsnList();
            code.add(new InsnNode(Opcodes.RETURN));
            swapBody(mn, code);
        }

        /** 替换方法体为 return List.of() */
        private void replaceWithEmptyList(MethodNode mn) {
            InsnList code = new InsnList();
            // List.of() 是 Java 9+ 的静态方法
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/List",
                    "of",
                    "()Ljava/util/List;",
                    true));
            code.add(new InsnNode(Opcodes.ARETURN));
            swapBody(mn, code);
        }

        /** 安全替换方法体 */
        private void swapBody(MethodNode mn, InsnList newCode) {
            mn.instructions.clear();
            mn.tryCatchBlocks = null;
            mn.localVariables = null;
            mn.visibleLocalVariableAnnotations = null;
            mn.invisibleLocalVariableAnnotations = null;
            mn.maxStack = 1;  // List.of() 需要栈空间
            mn.maxLocals = 0;  // 由 COMPUTE_MAXS 重算
            mn.instructions.add(newCode);
        }

        /** ASM 读写工具 */
        private ClassNode read(byte[] buf) {
            ClassNode cn = new ClassNode();
            new ClassReader(buf).accept(cn, 0);
            return cn;
        }

        private byte[] write(ClassNode cn) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    if (type1.equals(type2)) return type1;
                    return "java/lang/Object";
                }
            };
            cn.accept(cw);
            return cw.toByteArray();
        }

        /** 朴素字节子串搜索 */
        private int indexOf(byte[] data, byte[] pat) {
            if (data.length < pat.length) return -1;
            int last = data.length - pat.length;
            outer:
            for (int i = 0; i <= last; i++) {
                if (data[i] != pat[0]) continue;
                for (int j = 1; j < pat.length; j++) {
                    if (data[i + j] != pat[j]) continue outer;
                }
                return i;
            }
            return -1;
        }
    }
}
