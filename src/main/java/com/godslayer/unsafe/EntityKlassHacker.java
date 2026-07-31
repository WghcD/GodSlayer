package com.godslayer.unsafe;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
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
    private static final ConcurrentHashMap<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    // 特殊方法集合（需特殊处理）
    private static final Set<MethodSignature> SPECIAL_METHODS = new HashSet<>();

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
            COMPRESSED_OOPS = UNSAFE.arrayIndexScale(Object[].class) == 4;
            KLASS_OFFSET = 8;

            initSpecialMethods();
        } catch (Exception e) {
            throw new RuntimeException("Failed to init Unsafe", e);
        }
    }

    private static void initSpecialMethods() {
        // tick 方法（单独特殊处理：调用 remove）
        addSpecialMethod(Entity.class, "tick", void.class);

        // AI/Ticking 方法（置空或返回默认值）
        addSpecialMethod(Entity.class, "baseTick", void.class);
        addSpecialMethod(LivingEntity.class, "aiStep", void.class);
        addSpecialMethod(LivingEntity.class, "serverAiStep", void.class);
        addSpecialMethod(LivingEntity.class, "handleEntityEvent", void.class, byte.class);
        addSpecialMethod(Mob.class, "mobTick", void.class);
        addSpecialMethod(Mob.class, "customServerAiStep", void.class);
        addSpecialMethod(Entity.class, "canUpdate", boolean.class);

        // 生命值方法（返回 0 / false）
        addSpecialMethod(LivingEntity.class, "isAlive", boolean.class);
        addSpecialMethod(LivingEntity.class, "getHealth", float.class);
        addSpecialMethod(LivingEntity.class, "getMaxHealth", float.class);
    }

    private static void addSpecialMethod(Class<?> clazz, String name, Class<?> ret, Class<?>... paramTypes) {
        try {
            Method m = clazz.getDeclaredMethod(name, paramTypes);
            SPECIAL_METHODS.add(new MethodSignature(m));
        } catch (NoSuchMethodException ignored) {
            // 某些版本可能不存在，忽略
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

        // 清零原始类声明的所有基本类型字段（不包括继承字段）
        clearPrimitiveFields(target, originalClass);
    }

    // ---- 内部实现 ----

    private static long generatePuppetKlass(Class<?> originalClass) {
        String originalInternal = Type.getInternalName(originalClass);
        String myPackageInternal = Type.getInternalName(EntityKlassHacker.class);
        String puppetInternal = myPackageInternal + "$Puppet$" + originalClass.getSimpleName();

        // 1. 清除所有 final 修饰符（对类中的所有方法）
        for (Method m : originalClass.getDeclaredMethods()) {
            if (Modifier.isFinal(m.getModifiers())) {
                try{
                    Field modifiers = Field.class.getDeclaredField("modifiers");
                    modifiers.setAccessible(true);
                    modifiers.setInt(m, m.getModifiers() & ~Modifier.FINAL);
                } catch (Exception e) {
                    System.out.println("清除原始类final修饰符失败");
                    e.printStackTrace();
                }
            }
        }

        // 收集需要覆写的方法
        List<Method> methodsToOverride = collectMethodsToOverride(originalClass);
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

    /**
     * 收集所有需要覆写的方法：
     * - 原始类自身声明的所有非静态、非 final、非 private 方法（包括重写和新增）
     * - 特殊方法（即使原始类未声明也强制覆写，以确保行为被覆盖）
     */
    private static List<Method> collectMethodsToOverride(Class<?> originalClass) {
        Set<MethodSignature> added = new HashSet<>();
        List<Method> result = new ArrayList<>();

        // 1. 原始类自身声明的方法（不包括构造器）
        for (Method m : originalClass.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || Modifier.isPrivate(mod)) continue;
            if (m.getName().equals("<init>") || m.getName().equals("<clinit>")) continue;
            MethodSignature sig = new MethodSignature(m);
            if (added.add(sig)) {
                result.add(m);
            }
        }

        // 2. 特殊方法（来自父类或接口），如果还未添加则补充
        for (MethodSignature special : SPECIAL_METHODS) {
            if (!added.contains(special)) {
                // 尝试从原始类或其父类获取该方法的 Method 对象
                Method m = findMethod(originalClass, special.name, special.paramTypes);
                if (m != null) {
                    // 确保不是静态/私有/final（若存在则允许覆写）
                    int mod = m.getModifiers();
                    if (!Modifier.isStatic(mod) && !Modifier.isFinal(mod) && !Modifier.isPrivate(mod)) {
                        if (added.add(new MethodSignature(m))) {
                            result.add(m);
                        }
                    }
                }
            }
        }

        return result;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
        try {
            return clazz.getMethod(name, paramTypes); // 只查找 public
        } catch (NoSuchMethodException e) {
            // 尝试查找 declared 方法（包括 protected/private 但在父类中）
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Method m = current.getDeclaredMethod(name, paramTypes);
                    if (!Modifier.isPrivate(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
                        return m;
                    }
                } catch (NoSuchMethodException ignored) {}
                current = current.getSuperclass();
            }
            return null;
        }
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

        // 覆写所有收集到的方法
        for (Method m : methodsToOverride) {
            MethodSignature sig = new MethodSignature(m);
            int access = Modifier.isPublic(m.getModifiers()) ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
            String name = m.getName();
            String desc = Type.getMethodDescriptor(m);

            mv = cw.visitMethod(access, name, desc, null, null);
            mv.visitCode();

            // 特殊处理：tick 方法调用 remove(KILLED)
            if (name.equals("tick") && m.getParameterCount() == 0) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/Entity$RemovalReason",
                        "KILLED", "Lnet/minecraft/world/entity/Entity$RemovalReason;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, originalInternal, "remove",
                        "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();
                continue;
            }

            // 其他特殊方法（生命值等）返回 0 / false
            if (SPECIAL_METHODS.contains(sig)) {
                generateDefaultReturn(mv, m.getReturnType());
                mv.visitMaxs(1, 1 + m.getParameterTypes().length);
                mv.visitEnd();
                continue;
            }

            // 普通方法：根据返回类型生成默认值
            generateDefaultReturn(mv, m.getReturnType());
            mv.visitMaxs(1, 1 + m.getParameterTypes().length);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void generateDefaultReturn(MethodVisitor mv, Class<?> retType) {
        if (retType == void.class) {
            mv.visitInsn(Opcodes.RETURN);
        } else if (retType.isPrimitive()) {
            if (retType == boolean.class || retType == byte.class || retType == short.class || retType == char.class || retType == int.class) {
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
    }

    /**
     * 将原始类自身声明的所有非静态、非 final 的基本类型字段置零。
     */
    private static void clearPrimitiveFields(Entity target, Class<?> clazz) {
        List<Field> fields = FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
                Class<?> type = f.getType();
                if (type.isPrimitive() && type != void.class) {
                    list.add(f);
                }
            }
            return list;
        });

        for (Field f : fields) {
            long offset = UNSAFE.objectFieldOffset(f);
            Class<?> type = f.getType();
            if (type == boolean.class || type == byte.class || type == short.class || type == char.class || type == int.class) {
                UNSAFE.putInt(target, offset, 0);
            } else if (type == long.class) {
                UNSAFE.putLong(target, offset, 0L);
            } else if (type == float.class) {
                UNSAFE.putFloat(target, offset, 0.0f);
            } else if (type == double.class) {
                UNSAFE.putDouble(target, offset, 0.0);
            }
        }
    }

    // ---- 辅助类 MethodSignature ----
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