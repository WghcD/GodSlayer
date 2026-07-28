package com.godslayercore;

import com.godslayer.GodSlayerMod;
import com.godslayer.GodSlayerNative;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.Optional;

import static cpw.mods.modlauncher.api.ITransformer.Target.targetClass;

public class GodSlayerTransformationService implements ITransformationService {

    @Override
    public String name() {
        return "godslayer_core";
    }

    @Override
    public void initialize(IEnvironment environment) {
        // 无需初始化
        extractAndLoadNative();
        System.out.println("GodSlayerTransformationServiceLoad!");
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        // 加载时无需操作
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




    @Override
    public List<ITransformer> transformers() {
        return List.of(
                new EntityTransformer(),
                new LivingEntityTransformer()
        );
    }

    // ---------- Entity 类转换 ----------
    private static class EntityTransformer implements ITransformer<ClassNode> {
        @Override
        public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            for (MethodNode mn : input.methods) {
                if (mn.name.equals("remove") && mn.desc.equals("(Lnet/minecraft/world/entity/Entity$RemovalReason;)V")) {
                    injectRemoveCheck(mn);
                } else if (mn.name.equals("discard") && mn.desc.equals("()V")) {
                    injectRemoveCheck(mn);
                } else if (mn.name.equals("tick") && mn.desc.equals("()V")) {
                    injectTickCheck(mn);
                }
            }
            return input;
        }

        private void injectRemoveCheck(MethodNode mn) {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/godslayercore/ProtectionLnspectionForCore",
                    "shouldBlockRemove",
                    "(Lnet/minecraft/world/entity/Entity;)Z",
                    false));
            LabelNode jump = new LabelNode();
            list.add(new JumpInsnNode(Opcodes.IFEQ, jump));
            list.add(new InsnNode(Opcodes.RETURN));
            list.add(jump);
            mn.instructions.insert(list);
        }

        private void injectTickCheck(MethodNode mn) {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/godslayercore/ProtectionLnspectionForCore",
                    "shouldBlockTick",
                    "(Lnet/minecraft/world/entity/Entity;)Z",
                    false));
            LabelNode jump = new LabelNode();
            list.add(new JumpInsnNode(Opcodes.IFEQ, jump));
            list.add(new InsnNode(Opcodes.RETURN));
            list.add(jump);
            mn.instructions.insert(list);
        }

        @Override
        public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }

        @Override
        public Set<Target> targets() {
            return Set.of(targetClass("net.minecraft.world.entity.Entity"));
        }
    }

    // ---------- LivingEntity 类转换 ----------
    private static class LivingEntityTransformer implements ITransformer<ClassNode> {
        @Override
        public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            for (MethodNode mn : input.methods) {
                if (mn.name.equals("hurt") && mn.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")) {
                    injectHurtCheck(mn);
                } else if (mn.name.equals("setHealth") && mn.desc.equals("(F)V")) {
                    injectSetHealthCheck(mn);
                }
            }
            return input;
        }

        private void injectHurtCheck(MethodNode mn) {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/godslayercore/ProtectionLnspectionForCore",
                    "shouldBlockHurt",
                    "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                    false));
            LabelNode jump = new LabelNode();
            list.add(new JumpInsnNode(Opcodes.IFEQ, jump));
            list.add(new InsnNode(Opcodes.ICONST_0));
            list.add(new InsnNode(Opcodes.IRETURN));
            list.add(jump);
            mn.instructions.insert(list);
        }

        private void injectSetHealthCheck(MethodNode mn) {
            InsnList list = new InsnList();
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new VarInsnNode(Opcodes.FLOAD, 1));
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/godslayercore/ProtectionLnspectionForCore",
                    "shouldBlockSetHealth",
                    "(Lnet/minecraft/world/entity/LivingEntity;F)Z",
                    false));
            LabelNode jump = new LabelNode();
            list.add(new JumpInsnNode(Opcodes.IFEQ, jump));
            list.add(new InsnNode(Opcodes.RETURN));
            list.add(jump);
            mn.instructions.insert(list);
        }

        @Override
        public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }

        @Override
        public Set<Target> targets() {
            return Set.of(targetClass("net.minecraft.world.entity.LivingEntity"));
        }
    }
}