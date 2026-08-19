// ========== MyReflectionHelper.java ==========
package com.godslayer.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class MyReflectionHelper {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Unsafe UNSAFE;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static Unsafe getUnsafe() {
        return UNSAFE;
    }

    /**
     * 获取类的所有字段（包括父类，排除静态字段）
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            if (current.isHidden()) { // 跳过隐藏类（Lambda、代理等）
                break;
            }
            for (Field f : current.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * Unsafe 获取字段值
     */
    public static Object getFieldValue(Object obj, Field field) {
        try {
            return UNSAFE.getObject(obj, UNSAFE.objectFieldOffset(field));
        } catch (Exception e) {
            LOGGER.error("Unsafe getFieldValue 失败: {}", field.getName(), e);
            return null;
        }
    }

    /**
     * Unsafe 设置字段值
     */
    public static void setFieldValue(Object obj, Field field, Object newValue) {
        try {
            UNSAFE.putObject(obj, UNSAFE.objectFieldOffset(field), newValue);
        } catch (Exception e) {
            LOGGER.error("Unsafe setFieldValue 失败: {}", field.getName(), e);
        }
    }

    /**
     * 判断对象是否为“容器类型”（Map/List/Set/Collection 及 fastutil 扩展）
     */
    public static boolean isContainer(Object obj) {
        return obj instanceof java.util.Map ||
                obj instanceof java.util.Collection ||
                obj instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap ||
                obj instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap;
    }

    /**
     * 判断对象是否为“核心游戏对象”（需要递归穿透）
     */
    public static boolean isCoreGameObject(Object obj) {
        if (obj == null) return false;
        String className = obj.getClass().getName();
        if (className.startsWith("java.") ||
                className.startsWith("javax.") ||
                className.startsWith("com.google.") ||
                className.startsWith("it.unimi.dsi.") ||
                className.startsWith("sun.")) {
            return false;
        }
        // 典型Minecraft内部类
        return true; // 默认穿透所有非JDK类，避免遗漏
    }
}