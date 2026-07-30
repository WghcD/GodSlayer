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

    // 预定义需要覆写为空方法的 ticking/AI 方法签名集合
    private static final Set<MethodSignature> TICK_AI_METHODS = new HashSet<>();
    // 预定义需要覆写为返回 0/false 的生命值方法签名集合
    private static final Set<MethodSignature> HEALTH_METHODS = new HashSet<>();

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
            COMPRESSED_OOPS = UNSAFE.arrayIndexScale(Object[].class) == 4;
            KLASS_OFFSET = 8;

            // 初始化要覆写的核心方法列表
            initMethodSets();
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

        // 立刻标记移除
       /* target.remove(Entity.RemovalReason.KILLED);
        if (!target.isRemoved()) {
            target.discard();
        }*/
    }

    // ---- 内部实现 ----

    private static long generatePuppetKlass(Class<?> originalClass) {
        String originalInternal = Type.getInternalName(originalClass);
        String myPackageInternal = Type.getInternalName(EntityKlassHacker.class);
        String puppetInternal = myPackageInternal + "$Puppet$" + originalClass.getSimpleName();

        // 收集需要特殊覆写的方法
        List<Method> methodsToOverride = getMethodsToOverride(originalClass);
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
     * 从基类中预设 tick/AI 方法以及生命值方法的签名
     */
    private static void initMethodSets() {
        // Ticking/AI 方法（全部 void 或 boolean）
        addVoidMethod(Entity.class, "baseTick");          // m_6141_()
        addVoidMethod(LivingEntity.class, "aiStep");      // m_8107_()
        addVoidMethod(LivingEntity.class, "serverAiStep");// m_6140_()
        addVoidMethod(LivingEntity.class, "handleEntityEvent"); // m_7822_(B)V
        addVoidMethod(Mob.class, "mobTick");              // m_6145_()
        addVoidMethod(Mob.class, "customServerAiStep");   // m_8024_()
        addMethod(Entity.class, "canUpdate", boolean.class);   // m_6084_()Z

        // 生命值判断方法
        addMethod(LivingEntity.class, "isAlive", boolean.class);   // m_6084_()? 实际是 m_6084_()，注意区分
        // LivingEntity.isAlive() 混淆名实际是 m_6044_()? 需要准确，我们用反射获取
        // 为了避免混淆，我们在这里直接使用反射获取真实方法并添加
        try {
            Method isAlive = LivingEntity.class.getMethod("isAlive"); // 无参，返回 boolean
            HEALTH_METHODS.add(new MethodSignature(isAlive));
            Method getHealth = LivingEntity.class.getMethod("getHealth"); // 返回 float
            HEALTH_METHODS.add(new MethodSignature(getHealth));
            Method getMaxHealth = LivingEntity.class.getMethod("getMaxHealth"); // 返回 float
            HEALTH_METHODS.add(new MethodSignature(getMaxHealth));
        } catch (NoSuchMethodException ignored) {}
    }

    private static void addVoidMethod(Class<?> clazz, String name) {
        try {
            Method m = clazz.getDeclaredMethod(name);
            TICK_AI_METHODS.add(new MethodSignature(m));
        } catch (NoSuchMethodException e) {
            // 忽略，版本兼容
        }
    }

    private static void addMethod(Class<?> clazz, String name, Class<?> retType) {
        try {
            Method m = clazz.getDeclaredMethod(name);
            TICK_AI_METHODS.add(new MethodSignature(m));
        } catch (NoSuchMethodException ignored) {}
    }

    /**
     * 只挑选出需要覆写的方法（tick 单独处理，不在此处返回）
     */
    private static List<Method> getMethodsToOverride(Class<?> entityClass) {
        List<Method> result = new ArrayList<>();
        Set<MethodSignature> added = new HashSet<>();
        Class<?> current = entityClass;

        while (current != Object.class && current != null) {
            for (Method m : current.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || Modifier.isPrivate(mod)) continue;
                if (Modifier.isPublic(mod) || Modifier.isProtected(mod)) {
                    if (m.getName().equals("<init>") || m.getName().equals("<clinit>")) continue;
                    MethodSignature sig = new MethodSignature(m);

                    // 跳过 tick()，因为我们会单独覆写
                    if (m.getName().equals("tick") && m.getParameterCount() == 0) continue;

                    // 只加入我们预设集合中的方法
                    if (TICK_AI_METHODS.contains(sig) || HEALTH_METHODS.contains(sig)) {
                        if (added.add(sig)) {
                            result.add(m);
                        }
                    }
                }
            }
            if (current == Entity.class) break;
            current = current.getSuperclass();
        }
        return result;
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

        // 覆写 tick()：直接 remove(KILLED)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/Entity$RemovalReason",
                "KILLED", "Lnet/minecraft/world/entity/Entity$RemovalReason;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, originalInternal, "remove",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        // 处理其他需要覆写的方法
        for (Method m : methodsToOverride) {
            MethodSignature sig = new MethodSignature(m);
            int access = Modifier.isPublic(m.getModifiers()) ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED;
            String name = m.getName();
            String desc = Type.getMethodDescriptor(m);

            mv = cw.visitMethod(access, name, desc, null, null);
            mv.visitCode();

            Class<?> retType = m.getReturnType();
            if (HEALTH_METHODS.contains(sig)) {
                // 生命值方法返回 0 或 false
                if (retType == boolean.class) {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                } else if (retType == float.class) {
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                } else if (retType == int.class) {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                } else if (retType == double.class) {
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                } else {
                    // 其他对象返回 null（应该不会出现）
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
                }
            } else {
                // Ticking/AI 方法：生成空方法（返回类型默认值）
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
            mv.visitMaxs(1, 1 + m.getParameterTypes().length);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    // 移除 nukeFields 方法（不再需要）
    // 不再有任何字段清零操作

    // MethodSignature 类保持不变
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