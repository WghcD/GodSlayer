package com.godslayer.unsafe;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
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
    private static final ConcurrentHashMap<Class<?>, Class<?>> DEFINALIZED_CLASS_CACHE = new ConcurrentHashMap<>();

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
        addSpecialMethod(Entity.class, "tick", void.class);
        addSpecialMethod(Entity.class, "baseTick", void.class);
        addSpecialMethod(LivingEntity.class, "aiStep", void.class);
        addSpecialMethod(LivingEntity.class, "serverAiStep", void.class);
        addSpecialMethod(LivingEntity.class, "handleEntityEvent", void.class, byte.class);
        addSpecialMethod(Mob.class, "mobTick", void.class);
        addSpecialMethod(Mob.class, "customServerAiStep", void.class);
        addSpecialMethod(Entity.class, "canUpdate", boolean.class);
        addSpecialMethod(LivingEntity.class, "isAlive", boolean.class);
        addSpecialMethod(LivingEntity.class, "getHealth", float.class);
        addSpecialMethod(LivingEntity.class, "getMaxHealth", float.class);
    }

    private static void addSpecialMethod(Class<?> clazz, String name, Class<?> ret, Class<?>... paramTypes) {
        try {
            Method m = clazz.getDeclaredMethod(name, paramTypes);
            SPECIAL_METHODS.add(new MethodSignature(m));
        } catch (NoSuchMethodException ignored) {
        }
    }

    /**
     * 对外接口：将实体变为立即死亡的傀儡
     */
    public static void hack(Entity target) {
        if (target == null || target.isRemoved()) return;

        Class<?> originalClass = target.getClass();
        Class<?> parentClass = resolveParentKlass(originalClass);
        long puppetKlass = PUPPET_KLASS_CACHE.computeIfAbsent(
                originalClass,
                key -> generatePuppetKlass(key, parentClass)
        );

        if (COMPRESSED_OOPS) {
            UNSAFE.putInt(target, KLASS_OFFSET, (int) (puppetKlass & 0xFFFF_FFFFL));
        } else {
            UNSAFE.putLong(target, KLASS_OFFSET, puppetKlass);
        }

        // 按实际父类布局清理基础类型字段
        clearPrimitiveFields(target, parentClass);
    }

    // ---- 内部实现 ----

    /**
     * 如果原类不是 final，直接返回原类。
     * 如果是 final，则创建并缓存一个去 final 的副本类，供傀儡继承。
     */
    private static Class<?> resolveParentKlass(Class<?> originalClass) {
        if (!Modifier.isFinal(originalClass.getModifiers())) {
            return originalClass;
        }
        return DEFINALIZED_CLASS_CACHE.computeIfAbsent(originalClass, EntityKlassHacker::defineDefinalizedClass);
    }

    /**
     * 读取 final 类的字节码，去掉 ACC_FINAL，并复制到同包的新类中。
     */
    private static Class<?> defineDefinalizedClass(Class<?> originalClass) {
        try {
            String originalInternal = Type.getInternalName(originalClass);
            String resource = "/" + originalInternal + ".class";
            java.io.InputStream in = originalClass.getResourceAsStream(resource);
            if (in == null) {
                throw new IllegalStateException("Cannot find class resource: " + resource);
            }
            byte[] originalBytes = in.readAllBytes();
            in.close();

            ClassReader cr = new ClassReader(originalBytes);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

            String newInternal = originalInternal + "$GodslayerDefinalized";

            Remapper remapper = new Remapper() {
                @Override
                public String map(String internalName) {
                    if (originalInternal.equals(internalName)) {
                        return newInternal;
                    }
                    return super.map(internalName);
                }
            };

            ClassVisitor remapperVisitor = new ClassRemapper(cw, remapper);

            ClassVisitor finalRemover = new ClassVisitor(Opcodes.ASM9, remapperVisitor) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    // 去掉 ACC_FINAL，让傀儡类可以继承
                    super.visit(version, access & ~Opcodes.ACC_FINAL, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    // 删除静态初始化块，避免重复执行原类的副作用
                    if (name.equals("<clinit>")) {
                        return null;
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
            };

            cr.accept(finalRemover, 0);
            byte[] definalizedBytes = cw.toByteArray();

            // 用原类的 Lookup 定义同包新类，保证包私有访问权限
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    originalClass,
                    MethodHandles.lookup()
            );
            return lookup.defineClass(definalizedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to definalize final entity class: " + originalClass.getName(), e);
        }
    }

    private static long generatePuppetKlass(Class<?> originalClass, Class<?> parentClass) {
        String parentInternal = Type.getInternalName(parentClass);
        String myPackageInternal = Type.getInternalName(EntityKlassHacker.class);
        String puppetInternal = myPackageInternal + "$Puppet$" + originalClass.getSimpleName();

        // 收集需要覆写的方法。注意这里传入 parentClass，而不是 originalClass。
        // 对于 final 类，方法签名已经被去 final 副本重映射过，必须用副本的 Method 来生成覆写描述符。
        List<Method> methodsToOverride = collectMethodsToOverride(parentClass);
        byte[] classBytes = generatePuppetClassBytes(parentInternal, puppetInternal, methodsToOverride);

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

    private static List<Method> collectMethodsToOverride(Class<?> parentClass) {
        Set<MethodSignature> added = new HashSet<>();
        List<Method> result = new ArrayList<>();

        for (Method m : parentClass.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || Modifier.isPrivate(mod)) continue;
            if (m.getName().equals("<init>") || m.getName().equals("<clinit>")) continue;
            MethodSignature sig = new MethodSignature(m);
            if (added.add(sig)) {
                result.add(m);
            }
        }

        for (MethodSignature special : SPECIAL_METHODS) {
            if (!added.contains(special)) {
                Method m = findMethod(parentClass, special.name, special.paramTypes);
                if (m != null) {
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
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Method m = current.getDeclaredMethod(name, paramTypes);
                    if (!Modifier.isPrivate(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
                        return m;
                    }
                } catch (NoSuchMethodException ignored) {
                }
                current = current.getSuperclass();
            }
            return null;
        }
    }

    private static byte[] generatePuppetClassBytes(String parentInternal, String puppetInternal,
                                                   List<Method> methodsToOverride) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, puppetInternal, null, parentInternal, null);

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
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parentInternal, "<init>",
                "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();

        for (Method m : methodsToOverride) {
            MethodSignature sig = new MethodSignature(m);
            int access = Modifier.isPublic(m.getModifiers()) ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
            String name = m.getName();
            String desc = Type.getMethodDescriptor(m);

            mv = cw.visitMethod(access, name, desc, null, null);
            mv.visitCode();

            if (name.equals("tick") && m.getParameterCount() == 0) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/Entity$RemovalReason",
                        "KILLED", "Lnet/minecraft/world/entity/Entity$RemovalReason;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, parentInternal, "remove",
                        "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();
                continue;
            }

            if (SPECIAL_METHODS.contains(sig)) {
                generateDefaultReturn(mv, m.getReturnType());
                mv.visitMaxs(1, 1 + m.getParameterTypes().length);
                mv.visitEnd();
                continue;
            }

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