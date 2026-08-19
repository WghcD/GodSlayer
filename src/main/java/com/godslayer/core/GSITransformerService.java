package com.godslayer.core;

import com.godslayer.GodSlayerNative;
import com.godslayer.NativeGuard;//必須是灰的
import cpw.mods.modlauncher.api.*;
import cpw.mods.modlauncher.api.ITransformer;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.module.Configuration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;


import java.io.*;



import static net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField;


public class GSITransformerService implements ITransformationService {






    //  SPI 清理（原封不动保留）
    public static void makeMyModLoadable() {
        System.out.println("[GSITransformerService] 尝试清理SPI");

        //ModDirTransformerDiscoverer.found
        try {
            Class<?> discoverer = Class.forName("net.minecraftforge.fml.loading.ModDirTransformerDiscoverer");
            var foundHandle = MethodHandles.privateLookupIn(discoverer, MethodHandles.lookup())
                    .findStaticVarHandle(discoverer, "found", List.class);
            List<?> found = (List<?>) foundHandle.get();

            final Method[] pathsMethod = {null};

            found.removeIf(namedPath -> {
                try {
                    if (pathsMethod[0] == null) {
                        pathsMethod[0] = namedPath.getClass().getMethod("paths");
                    }
                    Path[] paths = (Path[]) pathsMethod[0].invoke(namedPath);
                    boolean match = paths[0].toString().contains("godslayer");
                    if (match) {
                        System.out.println("[GSITransformerService] 从found中移除: " + paths[0]);
                    }
                    return match;
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            System.err.println("[GSITransformerService] Step1失败: " + e.getMessage());
        }

        //ModuleLayer Configuration
        try {
            Class<?> launcherClass = Class.forName("cpw.mods.modlauncher.Launcher");
            Class<?> moduleLayerHandlerClass = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler");
            Class<?> layerInfoClass = Class.forName("cpw.mods.modlauncher.ModuleLayerHandler$LayerInfo");

            var instanceHandle = MethodHandles.privateLookupIn(launcherClass, MethodHandles.lookup())
                    .findStaticVarHandle(launcherClass, "INSTANCE", launcherClass);
            Object launcher = instanceHandle.get();
            if (launcher == null) {
                System.out.println("[GSITransformerService] Launcher.INSTANCE == null，跳过 Step 2");
                return;
            }

            var mlhHandle = MethodHandles.privateLookupIn(launcherClass, MethodHandles.lookup())
                    .findVarHandle(launcherClass, "moduleLayerHandler", moduleLayerHandlerClass);
            Object moduleLayerHandler = mlhHandle.get(launcher);
            if (moduleLayerHandler == null) {
                System.out.println("[GSITransformerService] moduleLayerHandler == null，跳过 Step 2");
                return;
            }

            var layersHandle = MethodHandles.privateLookupIn(moduleLayerHandlerClass, MethodHandles.lookup())
                    .findVarHandle(moduleLayerHandlerClass, "completedLayers", EnumMap.class);
            EnumMap<?, ?> completedLayers = (EnumMap<?, ?>) layersHandle.get(moduleLayerHandler);
            if (completedLayers == null || completedLayers.isEmpty()) {
                System.out.println("[GSITransformerService] completedLayers 为空，跳过 Step 2");
                return;
            }

            var layerHandle = MethodHandles.privateLookupIn(layerInfoClass, MethodHandles.lookup())
                    .findVarHandle(layerInfoClass, "layer", ModuleLayer.class);

            String moduleName = GSITransformerService.class.getModule().getName();
            System.out.println("[GSITransformerService] 模块名: " + moduleName);

            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);

            Field nameToModuleField = Configuration.class.getDeclaredField("nameToModule");
            long nameToModuleOffset = unsafe.objectFieldOffset(nameToModuleField);
            Field modulesField = Configuration.class.getDeclaredField("modules");
            long modulesOffset = unsafe.objectFieldOffset(modulesField);

            for (Object layerInfo : completedLayers.values()) {
                ModuleLayer layer = (ModuleLayer) layerHandle.get(layerInfo);
                if (layer == null) continue;

                Configuration config = layer.configuration();
                if (config == null) continue;

                Map<String, Object> nameToModule = (Map<String, Object>) unsafe.getObject(config, nameToModuleOffset);
                Set<Object> modules = (Set<Object>) unsafe.getObject(config, modulesOffset);

                if (nameToModule != null && nameToModule.containsKey(moduleName)) {
                    Map<String, Object> newNameToModule = new HashMap<>(nameToModule);
                    Set<Object> newModules = new HashSet<>(modules);
                    Object removed = newNameToModule.remove(moduleName);
                    if (removed != null) {
                        newModules.remove(removed);
                        System.out.println("[GSITransformerService] 已从 Configuration 移除模块: " + moduleName);
                    }
                    unsafe.putObject(config, nameToModuleOffset, Collections.unmodifiableMap(newNameToModule));
                    unsafe.putObject(config, modulesOffset, Collections.unmodifiableSet(newModules));
                }
            }
        } catch (Exception e) {
            System.err.println("[GSITransformerService] Step 2 失败: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[GSITransformerService] makeMyModLoadable() 清理完成");
    }

    public static synchronized boolean extractAndLoadNative() {

        //GodSlayerNative.extractAndLoadNative("godslayerAntiDanger");




        //  从 JAR 中提取到 mods/natives 目录
        try {
            String libName = System.mapLibraryName("godslayerAntiDanger");

            // 获取当前方法所在的类（即本工具类），从而定位其 JAR
            Class<?> currentClass = new Object() {}.getClass().getEnclosingClass();
            String jarPath = currentClass.getProtectionDomain().getCodeSource().getLocation().getPath();
            if (jarPath.startsWith("/") && System.getProperty("os.name").toLowerCase().contains("windows")) {
                jarPath = jarPath.substring(1);
            }

            File jarFile = new File(jarPath);
            File modsDir = jarFile.getParentFile(); // mods 文件夹
            if (modsDir == null || !modsDir.exists()) {
                modsDir = new File("mods"); // 备用
            }
            System.out.println("[GodSlayer] Mods directory: " + modsDir.getAbsolutePath());

            File nativesDir = new File(modsDir, "natives");
            if (!nativesDir.exists()) {
                if (!nativesDir.mkdirs()) {
                    System.out.println("[GodSlayer] ERROR: Failed to create natives directory.");
                    return false;
                }
                System.out.println("[GodSlayer] Created natives directory.");
            }

            File destFile = new File(nativesDir, libName);
            if (destFile.exists()) {
                if (!destFile.delete()) {
                    System.out.println("[GodSlayer] WARNING: Could not delete existing native library.");
                }
            }

            // 从 JAR 资源中加载
            String resourcePath = "/natives/" + libName;
            System.out.println("[GodSlayer] Attempting to extract: " + resourcePath);
            try (InputStream in = currentClass.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    resourcePath = "/" + libName;
                    System.out.println("[GodSlayer] Resource not in /natives/, trying root: " + resourcePath);
                    try (InputStream in2 = currentClass.getResourceAsStream(resourcePath)) {
                        if (in2 == null) {
                            System.out.println("[GodSlayer] ERROR: Native library not found in JAR.");
                            return false;
                        }
                        Files.copy(in2, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    Files.copy(in, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            destFile.setExecutable(true, false);
            destFile.setReadable(true, false);
            destFile.setWritable(true, true);

            System.out.println("[GodSlayer] Native library extracted to: " + destFile.getAbsolutePath());
            System.load(destFile.getAbsolutePath());

            System.out.println("[GodSlayer] Native library loaded successfully.");
            System.setProperty("godslayer.native.path", destFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            System.out.println("[GodSlayer] ERROR: Failed to extract or load native library.");
            e.printStackTrace();
        }



        return true;
    }

    static{
        System.out.println("[GodSlayer] Early.");
        System.out.println("Current Class Loader: "+ClassLoader.class.getClassLoader());
        extractAndLoadNative();

        //installAllProtections();
    }

    @Override
    public String name() {
        return "GodSlayerEarly";
    }



    @Override
    public void initialize(IEnvironment environment) {










    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        makeMyModLoadable();

        System.out.println("[GodSlayerEarlyService] onLoad called, otherServices: " + otherServices);
    }

    @Override
    public List<ITransformer> transformers() {
        // 返回我们自己的 ClassTransformer 列表
        return Collections.emptyList();
    }




}