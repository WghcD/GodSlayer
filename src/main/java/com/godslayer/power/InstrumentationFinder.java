package com.godslayer.power;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Minecraft Forge 1.20.1 (Java 17) 下的 Instrumentation 暴力搜索器
 */
public class InstrumentationFinder {

    private static Instrumentation cachedResult = null;
    private static final String TAG = "[InstFinder] ";

    public static Instrumentation getInstrumentationResult() {
        return cachedResult;
    }

    public static void searchNowAndPrintDetails() {
        log("========== 开始暴力搜索 Instrumentation 实例 ==========");
        Set<Instrumentation> foundInstances = new HashSet<>();

        log(">>> 执行策略一：定向反射已知组件...");
        foundInstances.addAll(searchStrategy1_KnownComponents());

        log(">>> 执行策略二：全内存暴力搜索...");
        foundInstances.addAll(searchStrategy2_FullMemoryScan(foundInstances));

        log("========== 搜索完毕，共找到 " + foundInstances.size() + " 个 Instrumentation 实例 ==========");
        int index = 1;
        for (Instrumentation inst : foundInstances) {
            log("--- 实例 #" + index + " ---");
            printInstrumentationDetails(inst);
            index++;
        }

        cachedResult = foundInstances.stream()
                .max(Comparator.comparingInt(InstrumentationFinder::getCapabilityScore))
                .orElse(null);

        if (cachedResult != null) {
            log(">>> 最终选择的能力最高的 Instrumentation 实例: " + cachedResult.getClass().getName());
        } else {
            log(">>> 未找到任何 Instrumentation 实例。");
            log(">>> 核心提示：如果没有任何 Java Agent 挂载，JVM 中本就不存在 Instrumentation 实例。");
            log(">>> 排查建议：请检查 JVM 参数是否放在了正确的位置 (-javaagent 等 JVM 参数区，而非程序参数区)。");
        }
    }

    // ========================================================================
    //  策略一：定向反射已知组件
    // ========================================================================
    private static Set<Instrumentation> searchStrategy1_KnownComponents() {
        Set<Instrumentation> results = new HashSet<>();
        // Forge, FML, ModLauncher 以及常见字节码操作库可能持有的类名
        String[] targetClasses = {
                "cpw.mods.modlauncher.Launcher",
                "net.minecraftforge.fml.loading.FMLLoader",
                "cpw.mods.modlauncher.ServiceLoaderHandler",
                "net.minecraftforge.fml.loading.moddiscovery.ModDiscoverer",
                "cpw.mods.cl.JarModuleFinder",
                // 如果是运行在 Arclight/Mohist 等服务端
                "io.izzel.arclight.api.ArclightInstrumentation",
                "com.mohist.MohistMC",
                // 常见 ByteCode 库
                "net.bytebuddy.agent.ByteBuddyAgent",
                "org.objectweb.asm.Agent"
        };

        for (String className : targetClasses) {
            try {
                Class<?> clazz = Class.forName(className, false, ClassLoader.getSystemClassLoader());
                foundInClass(clazz, results);
            } catch (ClassNotFoundException e) {
                // 静默忽略，正常现象
            } catch (Exception e) {
                log("访问 " + className + " 时发生异常: " + e.getMessage());
            }
        }
        return results;
    }

    // ========================================================================
    //  策略二：全内存暴力搜索
    // ========================================================================
    private static Set<Instrumentation> searchStrategy2_FullMemoryScan(Set<Instrumentation> knownInsts) {
        Set<Instrumentation> results = new HashSet<>(knownInsts);
        Set<Class<?>> classesToScan = new HashSet<>();

        // 阶段2.1：利用已发现的 Instrumentation 获取所有已加载类（破局点）
        if (!knownInsts.isEmpty()) {
            log("利用已发现的 Instrumentation 获取 getAllLoadedClasses()...");
            for (Instrumentation inst : knownInsts) {
                try {
                    Class<?>[] loadedClasses = inst.getAllLoadedClasses();
                    if (loadedClasses != null) {
                        Collections.addAll(classesToScan, loadedClasses);
                    }
                } catch (Exception e) {
                    log("调用 getAllLoadedClasses() 失败: " + e.getMessage());
                }
            }
        }

        // 阶段2.2：从可访问的 ClassLoader 获取已加载类
        log("遍历可访问的 ClassLoader 获取已加载类...");
        Set<ClassLoader> loaders = getAccessibleClassLoaders();
        for (ClassLoader loader : loaders) {
            extractClassesFromLoader(loader, classesToScan);
        }

        log("准备扫描 " + classesToScan.size() + " 个类的静态字段...");
        // 阶段2.3：扫描收集到的所有类
        for (Class<?> clazz : classesToScan) {
            foundInClass(clazz, results);
        }

        return results;
    }

    /**
     * 从 ClassLoader 中提取已加载的类，带有备用的深度反射扫描
     */
    private static void extractClassesFromLoader(ClassLoader loader, Set<Class<?>> classesToScan) {
        if (loader == null) return;

        // 尝试1：标准的 java.lang.ClassLoader 的 classes 字段
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<?> classes = (Vector<?>) classesField.get(loader);
            if (classes != null) {
                for (Object obj : classes) {
                    if (obj instanceof Class) {
                        classesToScan.add((Class<?>) obj);
                    }
                }
            }
        } catch (Throwable e) {
            // 如果标准字段失败，打印明确的异常名，方便用户排查为什么 --add-opens 没生效
            log("反射 " + loader.getClass().getName() + " 的标准 classes 字段失败 -> 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            log("  -> 如果异常是 InaccessibleObjectException，说明 JVM 参数 --add-opens java.base/java.lang=ALL-UNNAMED 未生效或放错位置。");
            log("  -> 正在尝试扫描自定义类加载器字段作为备选...");

            // 尝试2：深度扫描该 ClassLoader 实例的所有字段，寻找可能存有 Class 的集合
            try {
                for (Field f : loader.getClass().getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) &&
                            (Iterable.class.isAssignableFrom(f.getType()) || f.getType().isArray() || Map.class.isAssignableFrom(f.getType()))) {
                        try {
                            f.setAccessible(true);
                            Object obj = f.get(loader);
                            extractClassesFromObject(obj, classesToScan);
                        } catch (Throwable inner) {
                            // 忽略单个字段访问异常
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 忽略 getDeclaredFields 异常
            }
        }
    }

    /**
     * 递归从对象中提取 Class 实例（备用方案）
     */
    @SuppressWarnings("unchecked")
    private static void extractClassesFromObject(Object obj, Set<Class<?>> classesToScan) {
        if (obj == null) return;

        if (obj instanceof Class) {
            classesToScan.add((Class<?>) obj);
        } else if (obj instanceof Collection) {
            for (Object item : (Collection<?>) obj) {
                extractClassesFromObject(item, classesToScan);
            }
        } else if (obj instanceof Map) {
            for (Object item : ((Map<?, ?>) obj).values()) {
                extractClassesFromObject(item, classesToScan);
            }
        } else if (obj.getClass().isArray()) {
            if (obj instanceof Object[]) {
                for (Object item : (Object[]) obj) {
                    extractClassesFromObject(item, classesToScan);
                }
            }
        }
    }

    // ========================================================================
    //  辅助方法
    // ========================================================================

    private static void foundInClass(Class<?> clazz, Set<Instrumentation> results) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                for (Field f : current.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && Instrumentation.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            Object value = f.get(null);
                            if (value instanceof Instrumentation) {
                                log("  >>> 命中! 在 " + current.getName() + " 的字段 " + f.getName() + " 中发现实例!");
                                results.add((Instrumentation) value);
                            }
                        } catch (Exception inner) {
                            // 忽略
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
            current = current.getSuperclass();
        }
    }

    private static Set<ClassLoader> getAccessibleClassLoaders() {
        Set<ClassLoader> loaders = new HashSet<>();
        loaders.add(ClassLoader.getSystemClassLoader());

        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread t : threads) {
            ClassLoader cl = t.getContextClassLoader();
            while (cl != null) {
                loaders.add(cl);
                cl = cl.getParent();
            }
        }
        return loaders;
    }

    private static int getCapabilityScore(Instrumentation inst) {
        int score = 0;
        try {
            if (inst.isRedefineClassesSupported()) score += 1000;
            if (inst.isRetransformClassesSupported()) score += 1000;
            if (inst.isNativeMethodPrefixSupported()) score += 100;
            if (inst.isModifiableClass(String.class)) score += 50;
        } catch (Exception e) {
            // 忽略
        }
        return score;
    }

    private static void printInstrumentationDetails(Instrumentation inst) {
        if (inst == null) return;
        try {
            log("  类类型: " + inst.getClass().getName());
            log("  能力得分: " + getCapabilityScore(inst));
            log("  支持重定义: " + inst.isRedefineClassesSupported());
            log("  支持重转换: " + inst.isRetransformClassesSupported());
            log("  支持本地方法前缀: " + inst.isNativeMethodPrefixSupported());
            log("  能修改核心类: " + inst.isModifiableClass(String.class));
            log("  当前已加载类数量: " + inst.getAllLoadedClasses().length);
        } catch (Exception e) {
            log("  获取详细信息时发生异常: " + e.getMessage());
        }
    }

    private static void log(String msg) {
        System.out.println(TAG + msg);
    }
}
