package com.godslayer.unsafe;

import net.minecraft.world.entity.Entity;
import org.objectweb.asm.*;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityKlassHacker {

    private static final Unsafe UNSAFE;
    private static final boolean COMPRESSED_OOPS;
    private static final long KLASS_OFFSET;
    private static final ConcurrentHashMap<Class<?>, Long> PUPPET_KLASS_CACHE = new ConcurrentHashMap<>();

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
            COMPRESSED_OOPS = UNSAFE.arrayIndexScale(Object[].class) == 4;
            KLASS_OFFSET = 8;
        } catch (Exception e) {
            throw new RuntimeException("Failed to init Unsafe", e);
        }
    }

    /**
     * 对外接口：将实体变为立即死亡的傀儡
     */
    public static void hack(Entity target) {
        if (target == null || target.isRemoved()) return;

        Class<?> originalClass = target.getClass();
        long puppetKlass = PUPPET_KLASS_CACHE.computeIfAbsent(originalClass, EntityKlassHacker::generatePuppetKlass);

        // 篡改 klass 指针
        if (COMPRESSED_OOPS) {
            UNSAFE.putInt(target, KLASS_OFFSET, (int) (puppetKlass & 0xFFFF_FFFFL));
        } else {
            UNSAFE.putLong(target, KLASS_OFFSET, puppetKlass);
        }

        // 立刻执行移除（不依赖外部调度）
        target.remove(Entity.RemovalReason.KILLED);
        if (!target.isRemoved()) {
            target.remove(Entity.RemovalReason.KILLED);
        }
    }

    /**
     * 彻底摧毁实体的所有字段（供傀儡 tick 调用）
     */
    public static void nukeFields(Entity entity) {
        Class<?> clazz = entity.getClass();
        while (clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                long offset = UNSAFE.objectFieldOffset(f);
                Class<?> type = f.getType();
                if (type == int.class || type == boolean.class) {
                    UNSAFE.putInt(entity, offset, 0);
                } else if (type == long.class) {
                    UNSAFE.putLong(entity, offset, 0L);
                } else if (type == short.class) {
                    UNSAFE.putShort(entity, offset, (short) 0);
                } else if (type == byte.class) {
                    UNSAFE.putByte(entity, offset, (byte) 0);
                } else if (type == char.class) {
                    UNSAFE.putChar(entity, offset, '\0');
                } else if (type == float.class) {
                    UNSAFE.putFloat(entity, offset, 0f);
                } else if (type == double.class) {
                    UNSAFE.putDouble(entity, offset, 0d);
                } else {
                    UNSAFE.putObject(entity, offset, null);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    // ---- 内部实现 ----

    private static long generatePuppetKlass(Class<?> originalClass) {
        String originalInternal = Type.getInternalName(originalClass);
        // 把傀儡类放在本工具类所在包下，避免包权限问题
        String myPackageInternal = Type.getInternalName(EntityKlassHacker.class);
        String puppetInternal = myPackageInternal + "$Puppet$" + originalClass.getSimpleName();

        // 收集所有需要覆写的方法
        List<Method> methodsToOverride = getOverridableMethods(originalClass);
        byte[] classBytes = generatePuppetClassBytes(originalInternal, puppetInternal, methodsToOverride);

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandles.Lookup hiddenLookup = lookup.defineHiddenClass(classBytes, false);
            Class<?> puppetClass = hiddenLookup.lookupClass();

            Entity puppetInstance = (Entity) UNSAFE.allocateInstance(puppetClass);
            long klass;
            if (COMPRESSED_OOPS) {
                klass = UNSAFE.getInt(puppetInstance, KLASS_OFFSET) & 0xFFFF_FFFFL;
            } else {
                klass = UNSAFE.getLong(puppetInstance, KLASS_OFFSET);
            }
            return klass;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate hidden puppet for " + originalClass.getName(), e);
        }
    }

    private static List<Method> getOverridableMethods(Class<?> entityClass) {
        Set<MethodSignature> sigs = new HashSet<>();
        List<Method> methods = new ArrayList<>();
        Class<?> current = entityClass;
        // 从当前类向上追溯到 Entity（含），但不包括 Object
        while (current != Object.class && current != null) {
            for (Method m : current.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || Modifier.isPrivate(mod)) continue;
                if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue;
                if (m.getName().equals("<init>") || m.getName().equals("<clinit>")) continue;
                MethodSignature ms = new MethodSignature(m);
                if (sigs.add(ms)) {
                    methods.add(m);
                }
            }
            if (current == Entity.class) break; // 停在 Entity 层，避免跑到 Object
            current = current.getSuperclass();
        }
        return methods;
    }

    private static byte[] generatePuppetClassBytes(String originalInternal, String puppetInternal,
                                                   List<Method> methodsToOverride) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, puppetInternal, null, originalInternal, null);

        // 构造器：super(null, null)
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE,
                        Type.getObjectType("net/minecraft/world/entity/EntityType"),
                        Type.getObjectType("net/minecraft/world/level/Level")),
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, originalInternal, "<init>",
                "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        // 空覆写所有可覆写方法（tick 除外，单独处理）
        for (Method m : methodsToOverride) {
            if (m.getName().equals("tick") && m.getParameterCount() == 0) continue;
            int access = Modifier.isPublic(m.getModifiers()) ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
            String name = m.getName();
            String desc = Type.getMethodDescriptor(m);
            String[] exceptions = null;
            Class<?>[] excTypes = m.getExceptionTypes();
            if (excTypes.length > 0) {
                exceptions = new String[excTypes.length];
                for (int i = 0; i < excTypes.length; i++) {
                    exceptions[i] = Type.getInternalName(excTypes[i]);
                }
            }

            mv = cw.visitMethod(access, name, desc, null, exceptions);
            mv.visitCode();
            // 生成默认返回值
            Class<?> retType = m.getReturnType();
            if (retType == void.class) {
                mv.visitInsn(Opcodes.RETURN);
            } else if (retType.isPrimitive()) {
                if (retType == int.class || retType == boolean.class ||
                        retType == byte.class || retType == short.class || retType == char.class) {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                } else if (retType == long.class) {
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                } else if (retType == float.class) {
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                } else if (retType == double.class) {
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                }
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
            mv.visitMaxs(1, 1 + m.getParameterTypes().length);
            mv.visitEnd();
        }

        // 定制 tick()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        mv.visitCode();
        // 调用 EntityKlassHacker.nukeFields(this)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(EntityKlassHacker.class),
                "nukeFields",
                "(Lnet/minecraft/world/entity/Entity;)V", false);
        // 调用 this.remove(RemovalReason.KILLED)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/Entity$RemovalReason",
                "KILLED", "Lnet/minecraft/world/entity/Entity$RemovalReason;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, originalInternal, "remove",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // 用于去重的方法签名
    private static class MethodSignature {
        String name;
        Class<?>[] paramTypes;

        MethodSignature(Method m) {
            this.name = m.getName();
            this.paramTypes = m.getParameterTypes();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MethodSignature ms)) return false;
            return name.equals(ms.name) && Arrays.equals(paramTypes, ms.paramTypes);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + Arrays.hashCode(paramTypes);
        }
    }
}