package com.godslayer.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


import java.io.*;
import java.nio.file.*;

import com.godslayer.GodSlayerMod;
import com.godslayer.GodSlayerNative;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GodSlayerNativeBootstrap{//只为core使用，避免包括主类在内的其他class被提前加载
    public static synchronized boolean extractAndLoadNative() {


        // 1. 首先尝试系统库路径（备用）
        try {
            System.loadLibrary("godslayer");

            System.out.println("Native library loaded via System.loadLibrary");
            return true;
        } catch (UnsatisfiedLinkError e) {
            System.out.println("System.loadLibrary failed");
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
            System.out.println("Mods directory resolved");

            // 确保 natives 子目录存在
            File nativesDir = new File(modsDir, "natives");
            if (!nativesDir.exists()) {
                if (!nativesDir.mkdirs()) {
                    System.out.println("Failed to create natives directory");
                    return false;
                }
                System.out.println("Created natives directory");
            }

            File destFile = new File(nativesDir, libName);


            // 如果目标文件已存在且可写，删除后重新复制（确保最新）
            if (destFile.exists()) {
                if (!destFile.delete()) {

                }
            }

            // 从 JAR 资源中读取
            String resourcePath = "/natives/" + libName;
            System.out.println("Attempting to extract from JAR resource");
            try (InputStream in = GodSlayerNative.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    // 尝试从根目录查找
                    resourcePath = "/" + libName;

                    try (InputStream in2 = GodSlayerNative.class.getResourceAsStream(resourcePath)) {
                        if (in2 == null) {
                            System.out.println("Native library resource not found in JAR at any path.");
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

            System.out.println("Native library extracted to: {}");

            // 加载
            System.load(destFile.getAbsolutePath());

            System.out.println("Native library successfully loaded from: {}");
            return true;

        } catch (Exception e) {
            System.out.println("Failed to extract or load native library from JAR");
        }

        System.out.println("All native loading attempts failed.");
        return false;
    }
};