package com.godslayer.power;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.JMX;
import javax.management.ObjectName;

import com.godslayer.core.EarlyNativeBridge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * 以 GodSlayerNative.inst（完全能力 Instrumentation）为武器，
 * 掐断一切「反射调用静态 getInstrumentation()」式的获取路径
 * （即 ByteBuddyAgent.doGetInstrumentation 的那套流程）。
 * ASM 由 Forge 1.20.1 运行时自带（org.objectweb.asm / org.objectweb.asm.tree），无需额外打包。
 */
public final class InstrumentationInterceptor {

    private static final Logger LOG = Logger.getLogger(InstrumentationInterceptor.class.getName());

    /** 统一的拒绝消息，方便在日志里定位是谁在尝试获取 */
    public static final String DENY_MESSAGE =
            "[GodSlayer] Instrumentation acquisition denied by InstrumentationInterceptor";

    /** 你的 GodSlayerNative 的实际全限定名（仅跨 ClassLoader 反射兜底时使用） */
    private static final String GODSLAYER_FQCN = "com.godslayer.GodSlayerNative";

    /** 是否废掉 Attach API（com.sun.tools.attach.VirtualMachine#loadAgent*），默认开 */
    private static final boolean BLOCK_SELF_ATTACH = true;

    /**
     * 激进模式：拦截所有“之后定义”的、携带下列签名的类（不限 ByteBuddy，含 shaded 改包名的副本）：
     *   static Instrumentation getInstrumentation()
     *   static void premain/agentmain(String, Instrumentation)
     */
    private static final boolean AGGRESSIVE = true;

    /** 激进模式跳过前缀，避免误伤 Forge / Minecraft / Mixin 自身基础设施 */
    private static final String[] SKIP_PREFIXES = {
            "cpw/", "net/minecraftforge/", "org/spongepowered/", "net/minecraft/",
            "com/mojang/", "java/", "jdk/", "sun/", "javax/", "org/objectweb/asm/",
            "com/godslayer/"
    };

    private static volatile boolean killed = false;
    private static Instrumentation weapon;

    private InstrumentationInterceptor() {}

    // =========================================================================
    // 对外 API
    // =========================================================================

    /**
     * 一键拦截（幂等）。调用后：
     *  1) net.bytebuddy.agent.Installer#getInstrumentation 永远抛异常；
     *     其 premain/agentmain 变成空方法体 —— 即使重新 self-attach 也无法再写入实例；
     *  2) net.bytebuddy.agent.ByteBuddyAgent 的 install()/getInstrumentation()/doGetInstrumentation 全部失效；
     *  3) Installer 中已缓存的 Instrumentation 静态字段被清空；
     *  4) （BLOCK_SELF_ATTACH）VirtualMachine#loadAgent* 全部失效，运行时无法再挂载任何 agent；
     *  5) （AGGRESSIVE）之后任何类只要带 Instrumentation 签名入口，定义瞬间即被阉割。
     * 于是 doGetInstrumentation(...) 的反射 invoke 必然抛 InvocationTargetException，
     * 被 catch(Exception) 吞掉后只能返回 null —— “获取失败”达成。
     */
    public static synchronized void kill() {
        if (killed) return;

        Instrumentation inst = obtainWeapon();
        if (inst == null) {
            throw new IllegalStateException(
                    "GodSlayerNative.inst 未初始化：拦截器拿不到武器，请确认调用时机");
        }
        weapon = inst;
        if (!inst.isRetransformClassesSupported()) {
            // 理论上 Forge 的 agent 带 canRetransformClasses=true；万一没有，
            // 定义期拦截（addTransformer）依然生效，只是无法处理已加载的类
            LOG.warning("Instrumentation 不支持 retransform，已加载的敌对类将无法处理");
        }

        // 1) 常驻守门员（canRetransform = true，retransform 时也会回调到它）
        inst.addTransformer(new Gatekeeper(), true);

        // 2) 清剿当前已加载的敌对类
        List<Class<?>> byteBuddyClasses = new ArrayList<>();
        List<Class<?>> suspects = new ArrayList<>();
        Class<?> attachVm = null;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.startsWith("net.bytebuddy.agent.")) {
                byteBuddyClasses.add(c);                       // Installer / ByteBuddyAgent 及其内部类
            } else if (BLOCK_SELF_ATTACH && n.equals("com.sun.tools.attach.VirtualMachine")) {
                attachVm = c;
            } else if (AGGRESSIVE && isSuspectName(n) && hasInstrumentationGetter(c)) {
                suspects.add(c);                               // 已加载的、被 shaded 的 Installer 副本等
            }
        }
        for (Class<?> c : byteBuddyClasses) {
            wipeStaticInstrumentationFields(c);                // 先关时间窗：清缓存字段
            retransform(c);                                    // 再阉割方法体
        }
        if (attachVm != null) retransform(attachVm);
        for (Class<?> c : suspects) retransform(c);

        // 3) 尽力直接关闭 Attach 机制（DisableAttachMechanism 并非 manageable flag，大概率失败，
        //    失败无妨 —— 上面 2)、4) 已在类加载层封死；彻底阻断请加 JVM 参数，见文末说明）
        tryDisableAttachMechanism();

        killed = true;
        LOG.info("InstrumentationInterceptor 已就位：ByteBuddyAgent / Installer 获取通道已全部切断");
    }

    public static boolean isKilled() { return killed; }

    // =========================================================================
    // 武器获取
    // =========================================================================

    /**
     * 从 GodSlayerNative.inst 获取完全能力 Instrumentation。
     * 假定其形如：public final class GodSlayerNative { public static volatile Instrumentation inst; }
     * 直接引用失败（跨 ClassLoader 等）时走反射兜底。
     */
    private static Instrumentation obtainWeapon() {
        try {
            Instrumentation direct = EarlyNativeBridge.inst;
            if (direct != null) return direct;
        } catch (Throwable ignored) {}
        for (ClassLoader cl = InstrumentationInterceptor.class.getClassLoader();
             cl != null; cl = cl.getParent()) {
            try {
                Class<?> c = Class.forName(GODSLAYER_FQCN, false, cl);
                Object v = c.getField("inst").get(null);
                if (v instanceof Instrumentation) return (Instrumentation) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // =========================================================================
    // 运行时扫描辅助
    // =========================================================================

    private static boolean isSuspectName(String binaryName) {
        if (binaryName.startsWith("[")) return false;             // 数组类
        String internal = binaryName.replace('.', '/');
        for (String p : SKIP_PREFIXES) if (internal.startsWith(p)) return false;
        return internal.contains("Installer") || internal.contains("Agent");
    }

    private static boolean hasInstrumentationGetter(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getName().equals("getInstrumentation")
                        && m.getReturnType() == Instrumentation.class) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 清空目标类中所有 static Instrumentation 字段（retransform 不重置字段值，必须手动清） */
    private static void wipeStaticInstrumentationFields(Class<?> c) {
        try {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == Instrumentation.class) {
                    f.setAccessible(true);   // Installer 在 system loader 的无名模块中，通常可行
                    f.set(null, null);
                }
            }
        } catch (Throwable t) {
            // 命名模块中的字段可能 setAccessible 失败 —— 无妨，方法体已被阉割，字段无人能读
            LOG.log(Level.FINE, "wipe 静态字段失败（不致命）: " + c.getName(), t);
        }
    }

    private static void retransform(Class<?> c) {
        try {
            weapon.retransformClasses(c);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "retransform 失败: " + c.getName(), t);
        }
    }

    private static void tryDisableAttachMechanism() {
        try {
            Class<?> mbeanType = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object proxy = JMX.newMXBeanProxy(ManagementFactory.getPlatformMBeanServer(),
                    new ObjectName("com.sun.management:type=HotSpotDiagnostic"), mbeanType);
            mbeanType.getMethod("setVMOption", String.class, String.class)
                    .invoke(proxy, "DisableAttachMechanism", "true");
            LOG.info("Attach 机制已动态关闭");
        } catch (Throwable t) {
            LOG.fine("无法动态关闭 Attach 机制（正常现象，建议 JVM 参数加固）: " + t);
        }
    }

    // =========================================================================
    // 字节码守门员
    // =========================================================================

    private static final class Gatekeeper implements ClassFileTransformer {

        private static final byte[] PAT_GETTER_NAME =
                "getInstrumentation".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] PAT_GETTER_DESC =
                "()Ljava/lang/instrument/Instrumentation;".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] PAT_AGENTMAIN =
                "agentmain".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] PAT_PREMAIN =
                "premain".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] PAT_ENTRY_DESC =
                "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V".getBytes(StandardCharsets.US_ASCII);

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            if (className == null || classfileBuffer == null || classfileBuffer.length < 16) return null;
            try {
                if (className.startsWith("net/bytebuddy/agent/")) {
                    return neuterByteBuddy(classfileBuffer, className);
                }
                if (BLOCK_SELF_ATTACH && className.equals("com/sun/tools/attach/VirtualMachine")) {
                    return neuterAttachApi(classfileBuffer);
                }
                if (AGGRESSIVE && !isSkipped(className) && smellsLikeAgent(classfileBuffer)) {
                    return neuterGeneric(classfileBuffer);
                }
                return null;
            } catch (Throwable t) {
                // transformer 抛异常等于放行，吞掉以保证不影响启动
                LOG.log(Level.WARNING, "transform 失败（放行原始字节码）: " + className, t);
                return null;
            }
        }

        private static boolean isSkipped(String internalName) {
            for (String p : SKIP_PREFIXES) if (internalName.startsWith(p)) return true;
            return false;
        }

        /** 字节级快速预筛（只搜常量池字符串，命中才进入 ASM 解析） */
        private static boolean smellsLikeAgent(byte[] buf) {
            if (indexOf(buf, PAT_GETTER_NAME) >= 0 && indexOf(buf, PAT_GETTER_DESC) >= 0) return true;
            return (indexOf(buf, PAT_AGENTMAIN) >= 0 || indexOf(buf, PAT_PREMAIN) >= 0)
                    && indexOf(buf, PAT_ENTRY_DESC) >= 0;
        }

        /** 处理 net.bytebuddy.agent 包：Installer 与 ByteBuddyAgent 各自的规则 */
        private static byte[] neuterByteBuddy(byte[] buf, String internalName) {
            ClassNode cn = read(buf);
            boolean installer = internalName.endsWith("Installer");
            boolean changed = false;
            for (MethodNode mn : cn.methods) {
                boolean hit = installer
                        ? mn.name.equals("getInstrumentation")
                          || mn.name.equals("premain") || mn.name.equals("agentmain")
                        : mn.name.equals("getInstrumentation")
                          || mn.name.equals("doGetInstrumentation")
                          || mn.name.equals("install");
                if (!hit) continue;
                if (mn.name.equals("premain") || mn.name.equals("agentmain")) {
                    replaceWithReturn(mn);      // 空体：attach 成功也绝不保存 Instrumentation
                } else {
                    replaceWithThrow(mn);       // 获取路径全部抛异常
                }
                changed = true;
            }
            return changed ? write(cn) : null;
        }

        /** 处理 com.sun.tools.attach.VirtualMachine：废掉所有 loadAgent* 重载 */
        private static byte[] neuterAttachApi(byte[] buf) {
            ClassNode cn = read(buf);
            boolean changed = false;
            for (MethodNode mn : cn.methods) {
                if (mn.name.startsWith("loadAgent")) {
                    replaceWithThrow(mn);
                    changed = true;
                }
            }
            return changed ? write(cn) : null;
        }

        /** 激进规则：任何 static Instrumentation getInstrumentation() / agentmain(String, Instrumentation)V */
        private static byte[] neuterGeneric(byte[] buf) {
            ClassNode cn = read(buf);
            boolean changed = false;
            for (MethodNode mn : cn.methods) {
                if ((mn.access & Opcodes.ACC_STATIC) == 0) continue;
                if (mn.name.equals("getInstrumentation")
                        && "()Ljava/lang/instrument/Instrumentation;".equals(mn.desc)) {
                    replaceWithThrow(mn);
                    changed = true;
                } else if ((mn.name.equals("premain") || mn.name.equals("agentmain"))
                        && "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V".equals(mn.desc)) {
                    replaceWithReturn(mn);
                    changed = true;
                }
            }
            return changed ? write(cn) : null;
        }
    }

    // =========================================================================
    // ASM 工具
    // =========================================================================

    private static ClassNode read(byte[] buf) {
        ClassNode cn = new ClassNode();
        new ClassReader(buf).accept(cn, 0);
        return cn;
    }

    /**
     * 只用 COMPUTE_MAXS：被替换的方法体是无跳转直线代码，无需 StackMapTable
     * （帧随 instructions.clear() 一并清除）；未改动方法的原始帧原样保留，
     * 避免 COMPUTE_FRAMES 的 getCommonSuperClass 触发类加载 / 产生不精确帧。
     */
    private static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return type1.equals(type2) ? type1 : "java/lang/Object";
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** 方法体 → throw new UnsupportedOperationException(DENY_MESSAGE) */
    private static void replaceWithThrow(MethodNode mn) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(DENY_MESSAGE));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        swapBody(mn, code);
    }

    /** 方法体 → 直接 return（用于 premain/agentmain，保证 attach 流程不因异常中断落把柄） */
    private static void replaceWithReturn(MethodNode mn) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        swapBody(mn, code);
    }

    /**
     * 替换方法体。只动方法体、不动任何方法/字段结构，完全符合 retransform 的
     * JVMTI 约束（不得增删改签名）。
     */
    private static void swapBody(MethodNode mn, InsnList code) {
        mn.instructions.clear();   // 旧指令 + 旧 StackMapTable(FrameNode) 一并清除
        mn.tryCatchBlocks = null;
        mn.localVariables = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        mn.maxStack = 0;
        mn.maxLocals = 0;          // 由 COMPUTE_MAXS 依据描述符重算
        mn.instructions.add(code);
    }

    /** 朴素字节子串搜索（首字节快筛），避免为大类分配字符串 */
    private static int indexOf(byte[] data, byte[] pat) {
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
