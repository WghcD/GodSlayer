package com.godslayercore;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * 纯反射钩子类，完全不直接引用 NativeGuard 或 GodSlayerMod，
 * 确保不会在类加载时触发主类提前初始化。
 */
public class ProtectionInspectionForCore {

    private static final Object LOCK = new Object();
    private static volatile boolean initialized = false;

    // 反射缓存
    private static Class<?> nativeGuardClass;
    private static Class<?> godSlayerModClass;
    private static Method shouldBlockRemoveMethod;
    private static Method shouldBlockHurtMethod;
    private static Method shouldBlockSetHealthMethod;
    private static Field killedEntitiesField;

    private static void init() {
        if (initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            try {
                // 通过字符串加载类，不触发编译期符号引用
                nativeGuardClass = Class.forName("com.godslayer.NativeGuard");
                godSlayerModClass = Class.forName("com.godslayer.GodSlayerMod");

                shouldBlockRemoveMethod = nativeGuardClass.getDeclaredMethod("shouldBlockRemove", Entity.class);
                shouldBlockRemoveMethod.setAccessible(true);

                shouldBlockHurtMethod = nativeGuardClass.getDeclaredMethod("shouldBlockHurt", LivingEntity.class);
                shouldBlockHurtMethod.setAccessible(true);

                shouldBlockSetHealthMethod = nativeGuardClass.getDeclaredMethod("shouldBlockSetHealth", LivingEntity.class, float.class);
                shouldBlockSetHealthMethod.setAccessible(true);

                killedEntitiesField = godSlayerModClass.getDeclaredField("KILLED_ENTITIES");
                killedEntitiesField.setAccessible(true);

                initialized = true;
            } catch (Exception e) {
                // 若反射失败（理论上不会发生），打印错误并保持 initialized=false，
                // 后续调用均返回 false（即不阻塞），不影响游戏运行。
                e.printStackTrace();
            }
        }
    }

    public static boolean shouldBlockRemove(Entity entity) {
        init();
        if (!initialized) return false;
        try {
            return (boolean) shouldBlockRemoveMethod.invoke(null, entity);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean shouldBlockHurt(LivingEntity entity) {
        init();
        if (!initialized) return false;
        try {
            return (boolean) shouldBlockHurtMethod.invoke(null, entity);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean shouldBlockSetHealth(LivingEntity entity, float health) {
        init();
        if (!initialized) return false;
        try {
            return (boolean) shouldBlockSetHealthMethod.invoke(null, entity, health);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean shouldBlockTick(Entity entity) {
        init();
        if (!initialized) return false;
        try {
            Set<Integer> set = (Set<Integer>) killedEntitiesField.get(null);
            return set != null && set.contains(entity.getId());
        } catch (Exception e) {
            return false;
        }
    }
}