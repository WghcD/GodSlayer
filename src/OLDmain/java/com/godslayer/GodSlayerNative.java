package com.godslayer;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


import java.io.*;
import java.nio.file.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GodSlayerNative {

    private static final Logger LOGGER = LogManager.getLogger(GodSlayerMod.MOD_ID);
    private static boolean loaded = false;

    /**
     * 自动将 Native 库从 JAR 释放到 mods 目录并加载。
     * @return 是否加载成功
     */
    public static synchronized boolean extractAndLoadNative() {
        if (loaded) {LOGGER.debug("已经加载完成"); return true;}

        // 1. 首先尝试系统库路径（备用）
        try {
            System.loadLibrary("godslayer");
            loaded = true;
            LOGGER.info("✅ Native library loaded via System.loadLibrary");
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("System.loadLibrary failed: {}", e.getMessage());
        }

        // 2. 从 JAR 中提取到 mods 目录下的 natives 子目录
        try {
            String libName = System.mapLibraryName("godslayer"); // godslayer.dll / libgodslayer.so
            // 获取 mods 目录：通过主类 JAR 的位置推断
            String jarPath = GodSlayerMod.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            // 处理 Windows 路径中的空格
            if (jarPath.startsWith("/") && System.getProperty("os.name").toLowerCase().contains("windows")) {
                jarPath = jarPath.substring(1); // 去掉开头的 '/'
            }
            File jarFile = new File(jarPath);
            File modsDir = jarFile.getParentFile(); // 通常就是 mods 文件夹
            if (modsDir == null || !modsDir.exists()) {
                modsDir = new File("mods"); // 回退到当前目录下的 mods
            }
            LOGGER.debug("Mods directory resolved to: {}", modsDir.getAbsolutePath());

            // 确保 natives 子目录存在
            File nativesDir = new File(modsDir, "natives");
            if (!nativesDir.exists()) {
                if (!nativesDir.mkdirs()) {
                    LOGGER.error("Failed to create natives directory: {}", nativesDir.getAbsolutePath());
                    return false;
                }
                LOGGER.debug("Created natives directory: {}", nativesDir.getAbsolutePath());
            }

            File destFile = new File(nativesDir, libName);
            LOGGER.debug("Target native library path: {}", destFile.getAbsolutePath());

            // 如果目标文件已存在且可写，删除后重新复制（确保最新）
            if (destFile.exists()) {
                if (!destFile.delete()) {
                    LOGGER.warn("⚠Could not delete existing native library: {}", destFile.getAbsolutePath());
                }
            }

            // 从 JAR 资源中读取
            String resourcePath = "/natives/" + libName;
            LOGGER.info("Attempting to extract from JAR resource: {}", resourcePath);
            try (InputStream in = GodSlayerNative.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    // 尝试从根目录查找
                    resourcePath = "/" + libName;
                    LOGGER.warn("Resource not found in /natives/, trying root: {}", resourcePath);
                    try (InputStream in2 = GodSlayerNative.class.getResourceAsStream(resourcePath)) {
                        if (in2 == null) {
                            LOGGER.error("Native library resource not found in JAR at any path.");
                            return false;
                        }
                        Files.copy(in2, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    Files.copy(in, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // 设置文件权限（Linux/macOS 可能需要执行权限）
            destFile.setExecutable(true, false);
            destFile.setReadable(true, false);
            destFile.setWritable(true, true);

            LOGGER.info("Native library extracted to: {}", destFile.getAbsolutePath());

            // 加载
            System.load(destFile.getAbsolutePath());
            loaded = true;
            LOGGER.info("Native library successfully loaded from: {}", destFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to extract or load native library from JAR", e);
        }

        LOGGER.error("All native loading attempts failed.");
        return true;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    // ---- Native 方法声明 ----
    public static native void nativeMassacre(Level level, int[] skipIds);
    public static native void nativeKillEntity(Level level, int entityId);
    public static native void nativeTickGuard(Player player);   // 不变
    public static native void nativeDisableThreats();
}