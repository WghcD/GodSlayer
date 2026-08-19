package com.godslayer.utils;

import com.godslayer.GodSlayerNative;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EntityPowerRemover {

    private static final Logger LOGGER = LogManager.getLogger("GodSlayer");
    private static final Set<Class<?>> processedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 对外接口：抹除实体的自定义行为
     */
    public static void neutralizeEntityObject(Entity entityInstance) {
        if (entityInstance == null) return;
        Class<?> targetClass = entityInstance.getClass();

        if (processedClasses.contains(targetClass)) return;

        synchronized (EntityPowerRemover.class) {
            if (processedClasses.contains(targetClass)) return;

            LOGGER.fatal("[GodSlayer] Acquired lock. Neutralizing: " + targetClass.getSimpleName());

            try {
                neutralizeEntityInternal((Class<? extends Entity>) targetClass);
                processedClasses.add(targetClass);
                LOGGER.fatal("[GodSlayer] SUCCESS: " + targetClass.getSimpleName() + " is now empty.");
            } catch (Throwable t) {
                LOGGER.fatal("[GodSlayer] FAILED to neutralize " + targetClass.getSimpleName());
                t.printStackTrace();
            }
        }
    }

    /**
     * 核心内部逻辑
     */
    private static void neutralizeEntityInternal(Class<? extends Entity> targetClass) throws Exception {
        Instrumentation inst = GodSlayerNative.inst;
        if (inst == null) throw new IllegalStateException("Instrumentation is null");

        // 1. 获取字节码 (优先内存，回退磁盘并修补结构)
        byte[] targetBytes = getBytesHybridWithFix(inst, targetClass);
        if (targetBytes == null) throw new IllegalStateException("Cannot read bytecode for " + targetClass.getName());

        // 2. 获取父类字节码以分析继承关系
        byte[] entityBytes = getBytesFromDisk(Entity.class);
        if (entityBytes == null) throw new IllegalStateException("Cannot read Entity.class");

        Set<String> parentMethods = findInheritableMethods(entityBytes);
        String superName = Type.getInternalName(Entity.class);

        // 3. 执行字节码转换
        byte[] newBytes = transformToEmptyEntity(targetBytes, targetClass, superName, parentMethods);

        // 4. 重定义
        ClassDefinition def = new ClassDefinition(targetClass, newBytes);
        inst.redefineClasses(def);
    }

    /**
     * 字节码获取策略：
     * 尝试 Retransform (内存) -> 失败则读取磁盘 -> 磁盘读取时注入运行时字段 (防止 ClassFormatError)
     */
    private static byte[] getBytesHybridWithFix(Instrumentation inst, Class<?> clazz) {
        // 策略 A: 尝试从内存获取 (最安全，包含 Mixin)
        try {
            final byte[][] container = new byte[1][];
            ClassFileTransformer transformer = new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if (classBeingRedefined == clazz) container[0] = classfileBuffer;
                    return null;
                }
            };

            inst.addTransformer(transformer, true);
            inst.retransformClasses(clazz);
            inst.removeTransformer(transformer);

            if (container[0] != null) {
                LOGGER.fatal("Source: JVM Memory (Compatible)");
                return container[0];
            }
        } catch (Throwable t) {
            LOGGER.fatal("Retransform failed (" + t.getClass().getSimpleName() + "), trying Disk Rescue.");
        }

        // 策略 B: 从磁盘获取，但必须修复结构
        LOGGER.fatal("Source: Disk (Structural Patching Required)");
        byte[] diskBytes = getBytesFromDisk(clazz);
        if (diskBytes == null) return null;

        // 关键步骤：将运行时存在的字段注入到磁盘字节码中
        return injectMissingFields(diskBytes, clazz);
    }

    /**
     * 核心 ASM 转换逻辑
     */
    private static byte[] transformToEmptyEntity(byte[] originalBytes, Class<?> liveClass, String superName, Set<String> parentMethods) {
        ClassReader reader = new ClassReader(originalBytes);
        // 必须使用 COMPUTE_FRAMES 以应对 Java 17+ 的验证器
        ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                // 不处理静态方法、构造器
                if ((access & Opcodes.ACC_STATIC) != 0 || name.equals("<init>") || name.equals("<clinit>")) {
                    return mv;
                }

                // 逻辑 1: 如果是重写父类的方法 -> 强制调用 super
                if (parentMethods.contains(name + " " + descriptor)) {
                    LOGGER.fatal("Patching: Redirecting to super -> " + name);
                    return new MethodRedirector(mv, superName, name, descriptor);
                }

                // 逻辑 2: 如果是子类特有的 void 方法 -> 掏空
                if (Type.getReturnType(descriptor) == Type.VOID_TYPE) {
                    LOGGER.fatal("Patching: Hollowing void method -> " + name);
                    return new MethodHollower(mv);
                }

                return mv;
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * 磁盘字节码修复：注入运行时字段
     * 解决：用磁盘字节码替换 Mixin 类时出现的 "Structural change" 错误
     */
    private static byte[] injectMissingFields(byte[] diskBytes, Class<?> liveClass) {
        // 收集磁盘上已有的字段
        Set<String> existingFields = new HashSet<>();
        ClassReader diskReader = new ClassReader(diskBytes);
        ClassNode diskNode = new ClassNode();
        diskReader.accept(diskNode, 0);
        for (FieldNode fn : diskNode.fields) {
            existingFields.add(fn.name + ";" + fn.desc);
        }

        // 检查运行时是否有缺失的字段
        List<FieldNode> missingFields = new ArrayList<>();
        for (Field f : liveClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            String desc = Type.getDescriptor(f.getType());
            String key = f.getName() + ";" + desc;
            if (!existingFields.contains(key)) {
                LOGGER.fatal("Injecting missing runtime field: " + f.getName());
                // 创建对应的 ASM FieldNode
                missingFields.add(new FieldNode(Opcodes.ACC_PUBLIC, f.getName(), desc, null, null));
            }
        }

        if (missingFields.isEmpty()) return diskBytes;

        // 写入新字段
        ClassWriter writer = new SafeClassWriter(diskReader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor injector = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                for (FieldNode fn : missingFields) {
                    super.visitField(fn.access, fn.name, fn.desc, fn.signature, fn.value);
                }
                super.visitEnd();
            }
        };
        diskReader.accept(injector, 0);
        return writer.toByteArray();
    }

    // --- 辅助工具类 ---

    private static Set<String> findInheritableMethods(byte[] classBytes) {
        Set<String> methods = new HashSet<>();
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode m : node.methods) {
            if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
            if ((m.access & Opcodes.ACC_PRIVATE) != 0) continue;
            if (m.name.equals("<init>")) continue;
            methods.add(m.name + " " + m.desc);
        }
        return methods;
    }

    private static byte[] getBytesFromDisk(Class<?> clazz) {
        String name = clazz.getName().replace('.', '/') + ".class";
        ClassLoader loader = clazz.getClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();
        try (InputStream is = loader.getResourceAsStream(name)) {
            return is != null ? is.readAllBytes() : null;
        } catch (Exception e) { return null; }
    }

    /**
     * 安全的 ClassWriter，防止 Forge 环境下的 ClassNotFoundError
     */
    static class SafeClassWriter extends ClassWriter {
        public SafeClassWriter(ClassReader reader, int flags) { super(reader, flags); }
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try { return super.getCommonSuperClass(type1, type2); }
            catch (Exception e) { return "java/lang/Object"; }
        }
    }

    /**
     * 方法重定向器：强制调用 super
     */
    static class MethodRedirector extends MethodVisitor {
        private final MethodVisitor mv;
        private final String superOwner;
        private final String methodName;
        private final String methodDesc;

        public MethodRedirector(MethodVisitor mv, String superOwner, String name, String desc) {
            super(Opcodes.ASM9, mv);
            this.mv = mv;
            this.superOwner = superOwner;
            this.methodName = name;
            this.methodDesc = desc;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // 加载 this
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // 加载参数
            Type[] args = Type.getArgumentTypes(methodDesc);
            int slot = 1;
            for (Type t : args) {
                mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                slot += t.getSize();
            }
            // 调用 super.method()
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superOwner, methodName, methodDesc, false);
            // 返回
            mv.visitInsn(Type.getReturnType(methodDesc).getOpcode(Opcodes.IRETURN));
        }

        // 屏蔽原代码
        @Override public void visitInsn(int opcode) {}
        @Override public void visitIntInsn(int opcode, int operand) {}
        @Override public void visitVarInsn(int opcode, int var) {}
        @Override public void visitTypeInsn(int opcode, String type) {}
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}
        @Override public void visitJumpInsn(int opcode, Label label) {}
        @Override public void visitLdcInsn(Object value) {}
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}
        @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}
        @Override public void visitIincInsn(int var, int increment) {}
    }

    /**
     * 方法掏空器：仅保留 RETURN
     */
    static class MethodHollower extends MethodVisitor {
        private final MethodVisitor mv;
        public MethodHollower(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
            this.mv = mv;
        }
        @Override
        public void visitCode() {
            super.visitCode();
            mv.visitInsn(Opcodes.RETURN);
        }
        @Override public void visitInsn(int opcode) {}
        @Override public void visitIntInsn(int opcode, int operand) {}
        @Override public void visitVarInsn(int opcode, int var) {}
        @Override public void visitTypeInsn(int opcode, String type) {}
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}
        @Override public void visitJumpInsn(int opcode, Label label) {}
        @Override public void visitLdcInsn(Object value) {}
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}
        @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}
        @Override public void visitIincInsn(int var, int increment) {}
    }
}
