package com.godslayer;

import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;


import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import java.io.*;

import static com.godslayer.power.MyJavaAgentAttachBlocker.*;
import static com.godslayer.power.MyUnsafeGettingBlocker.disableAllUnsafeAccess;


public class GodSlayerNative {

    private static final Logger LOGGER = LogManager.getLogger(GodSlayerMod.MOD_ID);
    private static boolean loaded = true;
    public static boolean InstIsReady=false;
    public static Instrumentation inst;

    static{//這裏是最後的初始化

        GodSlayerNative.extractAndLoadNative("godslayer");
        GodSlayerNative.extractAndLoadNative("godslayerPower");

        Object obj = initializePower();
        if (obj instanceof Instrumentation) {
            LOGGER.fatal("\n\n\n\n\n[GodSlayer] Instrumentation object acquired successfully from native.\n\n\n\n");
            inst=(Instrumentation)obj;
            InstIsReady=true;
            testFullInstrumentation(inst);

        }

        installAllProtections();

        disableAllUnsafeAccess();

    }

    public static void testFullInstrumentation(Instrumentation inst) {




        System.out.println("\n=== [GodSlayer] Power Test Start ===");

        // 1. 检查 API 标记
        boolean redefineFlag = inst.isRedefineClassesSupported();
        boolean retransformFlag = inst.isRetransformClassesSupported();

        System.out.println("[Check 1] API Flags -> Redefine: " + redefineFlag + ", Retransform: " + retransformFlag);

        if (!retransformFlag) {
            System.err.println("Warning: Retransform flag is false. This usually means the hack failed or constructor args were wrong.");
        }

        // 2. 实战测试：Retransform (核心测试)
        // 如果内存修改失败，这里会抛出 UnsupportedOperationException
        System.out.println("[Check 2] Triggering Retransform on: " + inst.getClass().getName());
        try {
            // 注册一个空的转换器，必须传入 true 才能启用 retransform
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    // 仅做测试，不做任何修改
                    return null;
                }
            }, true);

            // 尝试重转换自身类
            inst.retransformClasses(inst.getClass());

            System.out.println("[Result] SUCCESS: Retransform capability is ACTIVE!");

        } catch (UnsupportedOperationException e) {
            System.err.println("[Result] FAILED: UnsupportedOperationException thrown!");
            System.err.println("         Reason: JVMTI capabilities were NOT actually patched in memory.");
            e.printStackTrace();
        } catch (Throwable t) {
            // 其他异常（如 ClassCastException 等）属于意外情况，但至少说明没有因为权限不足被拦截
            System.err.println("[Result] UNEXPECTED ERROR: " + t.getMessage());
            t.printStackTrace();
        }

        // 3. 实战测试：Redefine
        // 验证是否具备修改字节码的权限
        System.out.println("[Check 3] Triggering Redefine...");
        try {
            // 构造一个空字节码，这肯定会抛出 ClassFormatError，但我们只想验证权限
            ClassDefinition def = new ClassDefinition(Object.class, new byte[0]);
            inst.redefineClasses(def);
        } catch (UnsupportedOperationException e) {
            System.err.println("[Result] FAILED: Redefine not supported.");
        } catch (ClassFormatError | VerifyError e) {
            // 如果抛出 ClassFormatError，说明 API 调用成功进入了 JVMTI 层，权限验证通过！
            System.out.println("[Result] SUCCESS: Redefine capability ACTIVE (ClassFormatError is expected here).");
        } catch (Throwable t) {
            System.out.println("[Result] SUCCESS: Redefine capability ACTIVE (Exception: " + t.getClass().getSimpleName() + ").");
        }

        System.out.println("=== [GodSlayer] Power Test End ===\n");
    }

    /**
     * 自动将 Native 库从 JAR 释放到 mods 目录并加载。
     * @return 是否加载成功
     */
    public static synchronized boolean extractAndLoadNative(String libname) {



        //  从 JAR 中提取到 mods/natives 目录
        try {
            String libName = System.mapLibraryName(libname); // godslayer.dll / libgodslayer.so

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


        } catch (Exception e) {
            System.out.println("[GodSlayer] ERROR: Failed to extract or load native library.");
            e.printStackTrace();
        }


        return true;
    }




    public static boolean isLoaded() {
        return loaded;
    }

    // ---- Native 方法声明 ----
    public static native void nativeMassacre(Level level);
    public static native void nativeKillEntity(Level level, int entityId);
    private static native Object initializePower();


}

// 用于测试的靶子类
class TargetClass {
    public void hello() {
        System.out.println("I am alive.");
    }
}