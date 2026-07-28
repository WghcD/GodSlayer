package com.godslayercore;

import com.godslayer.GodSlayerMod;
import com.godslayer.GodSlayerNative;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;

public class GodSlayerLaunchPlugin implements ILaunchPluginService {

    @Override
    public String name() {
        extractAndLoadNative();

        return "godslayer_plugin";
    }

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


    /**
     * 核心方法：判断是否处理某个类
     * 签名：handlesClass(Type, boolean)
     */
    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        String className = classType.getClassName();
        if ("net.minecraft.world.entity.Entity".equals(className) ||
                "net.minecraft.world.entity.LivingEntity".equals(className)) {
            // 返回 ALL 表示在所有阶段都处理这个类
            return EnumSet.allOf(Phase.class);
        }
        return EnumSet.noneOf(Phase.class);
    }

    /**
     * 核心方法：处理类的字节码
     * 签名：processClassWithFlags(Phase, ClassNode, Type, String)
     */
    @Override
    public int processClassWithFlags(Phase phase, ClassNode classNode, Type classType, String reason) {
        String className = classType.getClassName();
        if ("net.minecraft.world.entity.Entity".equals(className)) {
            transformEntity(classNode);
        } else if ("net.minecraft.world.entity.LivingEntity".equals(className)) {
            transformLivingEntity(classNode);
        }
        System.out.println("processClassWithFlags...");
        // 返回 0 表示处理成功
        return 0;
    }

    // ==================== 字节码转换逻辑 ====================

    private void transformEntity(ClassNode classNode) {
        classNode.methods.stream()
                .filter(mn -> mn.name.equals("remove") && mn.desc.equals("(Lnet/minecraft/world/entity/Entity$RemovalReason;)V")
                        || mn.name.equals("discard") && mn.desc.equals("()V"))
                .forEach(this::injectRemoveCheck);

        classNode.methods.stream()
                .filter(mn -> mn.name.equals("tick") && mn.desc.equals("()V"))
                .forEach(this::injectTickCheck);
    }

    private void transformLivingEntity(ClassNode classNode) {
        classNode.methods.stream()
                .filter(mn -> mn.name.equals("hurt") && mn.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
                .forEach(this::injectHurtCheck);

        classNode.methods.stream()
                .filter(mn -> mn.name.equals("setHealth") && mn.desc.equals("(F)V"))
                .forEach(this::injectSetHealthCheck);
    }

    private void injectRemoveCheck(org.objectweb.asm.tree.MethodNode mn) {
        org.objectweb.asm.tree.InsnList list = new org.objectweb.asm.tree.InsnList();
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/godslayercore/ProtectionLnspectionForCore",
                "shouldBlockRemove",
                "(Lnet/minecraft/world/entity/Entity;)Z",
                false));
        org.objectweb.asm.tree.LabelNode jump = new org.objectweb.asm.tree.LabelNode();
        list.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, jump));
        list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        list.add(jump);
        mn.instructions.insert(list);
    }

    private void injectTickCheck(org.objectweb.asm.tree.MethodNode mn) {
        org.objectweb.asm.tree.InsnList list = new org.objectweb.asm.tree.InsnList();
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/godslayercore/ProtectionLnspectionForCore",
                "shouldBlockTick",
                "(Lnet/minecraft/world/entity/Entity;)Z",
                false));
        org.objectweb.asm.tree.LabelNode jump = new org.objectweb.asm.tree.LabelNode();
        list.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, jump));
        list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        list.add(jump);
        mn.instructions.insert(list);
    }

    private void injectHurtCheck(org.objectweb.asm.tree.MethodNode mn) {
        org.objectweb.asm.tree.InsnList list = new org.objectweb.asm.tree.InsnList();
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/godslayercore/ProtectionLnspectionForCore",
                "shouldBlockHurt",
                "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                false));
        org.objectweb.asm.tree.LabelNode jump = new org.objectweb.asm.tree.LabelNode();
        list.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, jump));
        list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
        list.add(jump);
        mn.instructions.insert(list);
    }

    private void injectSetHealthCheck(org.objectweb.asm.tree.MethodNode mn) {
        org.objectweb.asm.tree.InsnList list = new org.objectweb.asm.tree.InsnList();
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.FLOAD, 1));
        list.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/godslayercore/ProtectionLnspectionForCore",
                "shouldBlockSetHealth",
                "(Lnet/minecraft/world/entity/LivingEntity;F)Z",
                false));
        org.objectweb.asm.tree.LabelNode jump = new org.objectweb.asm.tree.LabelNode();
        list.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, jump));
        list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        list.add(jump);
        mn.instructions.insert(list);
    }
}