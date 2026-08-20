package com.godslayer.core;


import com.godslayer.power.InstrumentationInterceptor;
import com.godslayer.power.MyJavaAgentAttachBlocker;
import com.godslayer.power.TransformationServiceGuard;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static com.godslayer.GodSlayerNative.testFullInstrumentation;
import static com.godslayer.power.UnsafeGuard.disableAllUnsafeAccess;

public class EarlyNativeBridge{

    public static Instrumentation inst;

    public static synchronized boolean extractAndLoadNative(String name) {

        //GodSlayerNative.extractAndLoadNative("godslayerAntiDanger");




        //  从 JAR 中提取到 mods/natives 目录
        try {
            String libName = System.mapLibraryName(name);

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
        System.out.println("[GodSlayerNativeEarlyBridge] Early.");




    }

    public static void Init(){
        extractAndLoadNative("godslayerAntiDanger");
        extractAndLoadNative("godslayerPowerE");


        Object obj = initializePower();
        if (obj instanceof Instrumentation) {
            System.out.println("\n\n\n\n\n[GodSlayer] Instrumentation object acquired successfully from native.\n\n\n\n");
            inst=(Instrumentation)obj;



        }

        MyJavaAgentAttachBlocker.installAllProtections();

        InstrumentationInterceptor.kill();
        TransformationServiceGuard.install();
        //disableAllUnsafeAccess();
    }

    private static native Object initializePower();
}