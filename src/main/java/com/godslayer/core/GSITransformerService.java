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


import static com.godslayer.core.EarlyNativeBridge.Init;
import static com.godslayer.core.EarlyNativeBridge.extractAndLoadNative;
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



    static{
        System.out.println("[GodSlayerITransformationStaticBlock] Early.");
        System.out.println("Current Class Loader: "+ClassLoader.class.getClassLoader());
        Init();

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