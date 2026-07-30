package com.godslayer.utils;

import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.Function;


public final class ReflectHelper {

    private static final Unsafe UNSAFE;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError("Cannot obtain Unsafe instance: " + e);
        }
    }

    private ReflectHelper() {}

    /**
     * 获取 {@link Unsafe} 实例。
     */
    public static Unsafe getUnsafe() {
        return UNSAFE;
    }

    // ===================== 字段读写 =====================

    /**
     * 设置任意对象的指定字段值（绕过 final 和访问修饰符）。
     *
     * @param target    目标对象（静态字段则为 null）
     * @param fieldName 字段名
     * @param value     新值
     */
    public static void setFieldValue(Object target, String fieldName, Object value) {
        Objects.requireNonNull(fieldName, "fieldName");
        try {
            Field field = findField(target != null ? target.getClass() : (Class<?>) target, fieldName);
            long offset = (target == null) ? UNSAFE.staticFieldOffset(field) : UNSAFE.objectFieldOffset(field);
            UNSAFE.putObject(target, offset, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    /**
     * 读取任意对象的指定字段值（绕过访问修饰符）。
     *
     * @param target    目标对象（静态字段则为 null）
     * @param fieldName 字段名
     * @return 字段值
     */
    public static Object getFieldValue(Object target, String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        try {
            Field field = findField(target != null ? target.getClass() : (Class<?>) target, fieldName);
            long offset = (target == null) ? UNSAFE.staticFieldOffset(field) : UNSAFE.objectFieldOffset(field);
            return UNSAFE.getObject(target, offset);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get field " + fieldName, e);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " not found in " + clazz);
    }

    // ===================== 方法替换（仅影响反射调用） =====================

    /**
     * 替换目标方法的反射调用行为。
     * <p>修改 {@link Method} 对象内部的 {@code methodAccessor}，使得通过 {@code method.invoke()}
     * 调用时转而执行 {@code newImplementation}。对于 Java 字节码中的直接调用 (<code>invokevirtual</code>) 无效。
     *
     * @param targetMethod     被替换的方法对象
     * @param newImplementation 新的方法实现，可以是任意对象，其类型需与方法参数/返回值兼容
     *                          (可以使用 {@link java.lang.invoke.MethodHandle} 或普通对象)
     */
    public static void replaceMethodInvocation(Method targetMethod, Object newImplementation) {
        Objects.requireNonNull(targetMethod, "targetMethod");
        Objects.requireNonNull(newImplementation, "newImplementation");
        try {
            // 反射查找 Method 的 methodAccessor 字段
            Field accessorField = Method.class.getDeclaredField("methodAccessor");
            long offset = UNSAFE.objectFieldOffset(accessorField);
            Object originalAccessor = UNSAFE.getObject(targetMethod, offset);

            // 如果还没有 Accessor，先强制生成一个
            if (originalAccessor == null) {
                // 通过调用一次 setAccessible 等操作让 JVM 创建默认 Accessor
                targetMethod.setAccessible(true);
                originalAccessor = UNSAFE.getObject(targetMethod, offset);
            }

            // 创建一个包装 Accessor，将 invoke 委托给 newImplementation
            Object wrappedAccessor = createDelegatingAccessor(newImplementation, targetMethod.getReturnType());
            UNSAFE.putObject(targetMethod, offset, wrappedAccessor);
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace method invocation: " + targetMethod, e);
        }
    }

    /**
     * 通过反射和 Unsafe 创建一个 {@code jdk.internal.reflect.MethodAccessor} 的包装，
     * 使其将调用转发给指定的目标对象。
     * 注意：此处依赖 JDK 内部类，不同版本可能不同，需要验证。
     */
    @SuppressWarnings("unchecked")
    private static Object createDelegatingAccessor(Object target, Class<?> returnType) throws Exception {
        // 使用 LambdaMetafactory 动态生成 MethodAccessor 接口的实现
        // 但 MethodAccessor 是一个内部接口，我们可以通过 Proxy 来创建
        Class<?> methodAccessorClass = Class.forName("jdk.internal.reflect.MethodAccessor");
        return java.lang.reflect.Proxy.newProxyInstance(
                methodAccessorClass.getClassLoader(),
                new Class<?>[] { methodAccessorClass },
                (proxy, method, args) -> {
                    if ("invoke".equals(method.getName()) && args != null && args.length == 2) {
                        Object obj = args[0];
                        Object[] params = (Object[]) args[1];
                        // 这里假设 target 是 Function<Object[], Object> 或 MethodHandle
                        if (target instanceof Function) {
                            return ((Function<Object[], Object>) target).apply(params);
                        } else if (target instanceof java.lang.invoke.MethodHandle) {
                            java.lang.invoke.MethodHandle mh = (java.lang.invoke.MethodHandle) target;
                            if (params == null) params = new Object[0];
                            // 将接收者 obj 和参数组合？反射 invoke 的语义：obj 可能为 null (静态方法)
                            // 简化处理：忽略 obj，只传参数给 MethodHandle
                            return mh.invokeWithArguments(params);
                        } else {
                            // 回退：直接调用 target 的某个方法
                            // 这里可以扩展
                            throw new UnsupportedOperationException("Unsupported target type: " + target.getClass());
                        }
                    }
                    return null;
                });
    }

    // ===================== 实用方法 =====================

    /**
     * 直接分配一个类的实例（不调用任何构造器）。
     */
    public static <T> T allocateInstance(Class<T> clazz) {
        try {
            return (T) UNSAFE.allocateInstance(clazz);
        } catch (InstantiationException e) {
            throw new RuntimeException("Cannot allocate instance of " + clazz, e);
        }
    }

    /**
     * 获取字段在对象内存中的偏移量（用于后续直接操作）。
     */
    public static long objectFieldOffset(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }

    /**
     * 获取静态字段在类内存中的偏移量。
     */
    public static long staticFieldOffset(Field field) {
        return UNSAFE.staticFieldOffset(field);
    }

    /**
     * 从对象的内存地址读取 int 值（需要知道偏移量）。
     */
    public static int getInt(Object obj, long offset) {
        return UNSAFE.getInt(obj, offset);
    }

    public static void putInt(Object obj, long offset, int value) {
        UNSAFE.putInt(obj, offset, value);
    }

    // 类似可扩展其他基本类型...

    /**
     * 修改静态 final 字段的值（即使已经内联到其他类，也可能不生效）。
     */
    public static void setStaticFinalField(Class<?> clazz, String fieldName, Object value) {
        setFieldValue(clazz, fieldName, value); // 利用 getFieldValue 的内部逻辑
        // 注意：如果字段被编译时常量折叠，需要额外处理，此处略
    }
}