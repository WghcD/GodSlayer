package com.godslayer.core;

import com.godslayer.GodSlayerNative;
import cpw.mods.modlauncher.api.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.module.Configuration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.objectweb.asm.Opcodes.*;

/**
 * ITransformationService 实现，在类加载早期进行 ASM 字节码转换。
 * 头尾双钩子：在目标方法开头和结尾都插入防护代码。
 */
public class GodSlayerEntityProtectionCore implements ITransformationService {

    private static final Logger LOGGER = Logger.getLogger("GodSlayerCore");

    //  SPI 清理（原封不动保留）
    public static void makeMyModLoadable() {
        System.out.println("[GodSlayerEntityProtectionCore] 尝试清理SPI");

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
                        System.out.println("[GodSlayerEntityProtectionCore] 从found中移除: " + paths[0]);
                    }
                    return match;
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            System.err.println("[GodSlayerEntityProtectionCore] Step1失败: " + e.getMessage());
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
                System.out.println("[GodSlayerEntityProtectionCore] Launcher.INSTANCE == null，跳过 Step 2");
                return;
            }

            var mlhHandle = MethodHandles.privateLookupIn(launcherClass, MethodHandles.lookup())
                    .findVarHandle(launcherClass, "moduleLayerHandler", moduleLayerHandlerClass);
            Object moduleLayerHandler = mlhHandle.get(launcher);
            if (moduleLayerHandler == null) {
                System.out.println("[GodSlayerEntityProtectionCore] moduleLayerHandler == null，跳过 Step 2");
                return;
            }

            var layersHandle = MethodHandles.privateLookupIn(moduleLayerHandlerClass, MethodHandles.lookup())
                    .findVarHandle(moduleLayerHandlerClass, "completedLayers", EnumMap.class);
            EnumMap<?, ?> completedLayers = (EnumMap<?, ?>) layersHandle.get(moduleLayerHandler);
            if (completedLayers == null || completedLayers.isEmpty()) {
                System.out.println("[GodSlayerEntityProtectionCore] completedLayers 为空，跳过 Step 2");
                return;
            }

            var layerHandle = MethodHandles.privateLookupIn(layerInfoClass, MethodHandles.lookup())
                    .findVarHandle(layerInfoClass, "layer", ModuleLayer.class);

            String moduleName = GodSlayerEntityProtectionCore.class.getModule().getName();
            System.out.println("[GodSlayerEntityProtectionCore] 模块名: " + moduleName);

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
                        System.out.println("[GodSlayerEntityProtectionCore] 已从 Configuration 移除模块: " + moduleName);
                    }
                    unsafe.putObject(config, nameToModuleOffset, Collections.unmodifiableMap(newNameToModule));
                    unsafe.putObject(config, modulesOffset, Collections.unmodifiableSet(newModules));
                }
            }
        } catch (Exception e) {
            System.err.println("[GodSlayerEntityProtectionCore] Step 2 失败: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[GodSlayerEntityProtectionCore] makeMyModLoadable() 清理完成");
    }

    @Override
    public String name() {
        return "godslayer_main_core";
    }

    @Override
    public void initialize(IEnvironment environment) {
        System.out.println("[GodSlayerCore] Initializing...");
        GodSlayerNative.extractAndLoadNative();// 在这里尝试Load Native 库


    }



    @Override
    public List<ITransformer> transformers() {
        System.out.println("[GodSlayerCore] Returning transformers.");
        return List.of(
                new LivingEntityTransformer(),
                new EntityTransformer(),
                new ServerPlayerTransformer()
        );
    }

    // ===== 各个 Transformer =====

    /**
     * 处理 LivingEntity 类
     */
    private static class LivingEntityTransformer implements ITransformer<ClassNode> {
        @Override
        public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            String className = input.name.replace('/', '.');
            if (!"net.minecraft.world.entity.LivingEntity".equals(className)) {
                return input;
            }
            System.out.println("[GodSlayerCore] Transforming LivingEntity: " + className);

            for (MethodNode method : input.methods) {
                if ("setHealth".equals(method.name) && "(F)V".equals(method.desc)) {
                    injectHeadAndTail(method,
                            "com/godslayer/NativeGuard",
                            "shouldBlockSetHealth",
                            "(Lnet/minecraft/world/entity/LivingEntity;F)Z",
                            (mn) -> {
                                // 头部：检查并返回
                                InsnList head = new InsnList();
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new VarInsnNode(FLOAD, 1));
                                head.add(new MethodInsnNode(INVOKESTATIC,
                                        "com/godslayer/NativeGuard",
                                        "shouldBlockSetHealth",
                                        "(Lnet/minecraft/world/entity/LivingEntity;F)Z",
                                        false));
                                LabelNode label = new LabelNode();
                                head.add(new JumpInsnNode(IFEQ, label));
                                head.add(new InsnNode(RETURN));
                                head.add(label);
                                return head;
                            },
                            null // 尾部无额外操作
                    );
                }
                if ("hurt".equals(method.name) && "(Lnet/minecraft/world/damagesource/DamageSource;F)Z".equals(method.desc)) {
                    injectHeadAndTail(method,
                            "com/godslayer/NativeGuard",
                            "shouldBlockHurt",
                            "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                            (mn) -> {
                                InsnList head = new InsnList();
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new MethodInsnNode(INVOKESTATIC,
                                        "com/godslayer/NativeGuard",
                                        "shouldBlockHurt",
                                        "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                                        false));
                                LabelNode label = new LabelNode();
                                head.add(new JumpInsnNode(IFEQ, label));
                                head.add(new InsnNode(ICONST_0));
                                head.add(new InsnNode(IRETURN));
                                head.add(label);
                                return head;
                            },
                            null
                    );
                }
                if ("die".equals(method.name) && "(Lnet/minecraft/world/damagesource/DamageSource;)V".equals(method.desc)) {
                    injectHeadAndTail(method,
                            "com/godslayer/NativeGuard",
                            "shouldBlockDeath",
                            "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                            (mn) -> {
                                InsnList head = new InsnList();
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new MethodInsnNode(INVOKESTATIC,
                                        "com/godslayer/NativeGuard",
                                        "shouldBlockDeath",
                                        "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                                        false));
                                LabelNode label = new LabelNode();
                                head.add(new JumpInsnNode(IFEQ, label));
                                // 恢复血量
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new MethodInsnNode(INVOKEVIRTUAL,
                                        "net/minecraft/world/entity/LivingEntity",
                                        "getMaxHealth",
                                        "()F",
                                        false));
                                head.add(new MethodInsnNode(INVOKEVIRTUAL,
                                        "net/minecraft/world/entity/LivingEntity",
                                        "setHealth",
                                        "(F)V",
                                        false));
                                head.add(new InsnNode(RETURN));
                                head.add(label);
                                return head;
                            },
                            null
                    );
                }
                if ("tick".equals(method.name) && "()V".equals(method.desc)) {
                    // 头部：强制将 deathTime 置 0（针对永爱之刃设置 deathTime 的防御）
                    InsnList head = new InsnList();
                    head.add(new VarInsnNode(ALOAD, 0));
                    head.add(new MethodInsnNode(INVOKESTATIC,
                            "com/godslayer/NativeGuard",
                            "isHoldingGodSlayer",
                            "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                            false));
                    LabelNode skip = new LabelNode();
                    head.add(new JumpInsnNode(IFEQ, skip));
                    head.add(new VarInsnNode(ALOAD, 0));
                    head.add(new InsnNode(ICONST_0));
                    head.add(new FieldInsnNode(PUTFIELD,
                            "net/minecraft/world/entity/LivingEntity",
                            "deathTime",
                            "I"));
                    head.add(skip);
                    method.instructions.insert(head);

                    // 尾部：同样处理，但一般头部即可
                }
            }
            return input;
        }

        @Override
        public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }

        @Override
        public Set<Target> targets() {
            return Set.of(Target.targetClass("net.minecraft.world.entity.LivingEntity"));
        }
    }

    /**
     * 处理 Entity 类
     */
    private static class EntityTransformer implements ITransformer<ClassNode> {
        @Override
        public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            String className = input.name.replace('/', '.');
            if (!"net.minecraft.world.entity.Entity".equals(className)) {
                return input;
            }
            System.out.println("[GodSlayerCore] Transforming Entity: " + className);

            for (MethodNode method : input.methods) {
                if ("remove".equals(method.name) && "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V".equals(method.desc)) {
                    injectHead(method,
                            "com/godslayer/NativeGuard",
                            "shouldBlockRemove",
                            "(Lnet/minecraft/world/entity/Entity;)Z",
                            () -> {
                                InsnList head = new InsnList();
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new MethodInsnNode(INVOKESTATIC,
                                        "com/godslayer/NativeGuard",
                                        "shouldBlockRemove",
                                        "(Lnet/minecraft/world/entity/Entity;)Z",
                                        false));
                                LabelNode label = new LabelNode();
                                head.add(new JumpInsnNode(IFEQ, label));
                                head.add(new InsnNode(RETURN));
                                head.add(label);
                                return head;
                            }
                    );
                }
                if ("discard".equals(method.name) && "()V".equals(method.desc)) {
                    injectHead(method,
                            "com/godslayer/NativeGuard",
                            "shouldBlockRemove",
                            "(Lnet/minecraft/world/entity/Entity;)Z",
                            () -> {
                                InsnList head = new InsnList();
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new MethodInsnNode(INVOKESTATIC,
                                        "com/godslayer/NativeGuard",
                                        "shouldBlockRemove",
                                        "(Lnet/minecraft/world/entity/Entity;)Z",
                                        false));
                                LabelNode label = new LabelNode();
                                head.add(new JumpInsnNode(IFEQ, label));
                                head.add(new InsnNode(RETURN));
                                head.add(label);
                                return head;
                            }
                    );
                }
                if ("setRemoved".equals(method.name) && "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V".equals(method.desc)) {
                    injectHead(method,
                            "com/godslayer/NativeGuard",
                            "shouldBlockRemove",
                            "(Lnet/minecraft/world/entity/Entity;)Z",
                            () -> {
                                InsnList head = new InsnList();
                                head.add(new VarInsnNode(ALOAD, 0));
                                head.add(new MethodInsnNode(INVOKESTATIC,
                                        "com/godslayer/NativeGuard",
                                        "shouldBlockRemove",
                                        "(Lnet/minecraft/world/entity/Entity;)Z",
                                        false));
                                LabelNode label = new LabelNode();
                                head.add(new JumpInsnNode(IFEQ, label));
                                head.add(new InsnNode(RETURN));
                                head.add(label);
                                return head;
                            }
                    );
                }
            }
            return input;
        }

        @Override
        public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }

        @Override
        public Set<Target> targets() {
            return Set.of(Target.targetClass("net.minecraft.world.entity.Entity"));
        }
    }

    /**
     * 处理 ServerPlayer 类（拦截 teleportTo）
     */
    private static class ServerPlayerTransformer implements ITransformer<ClassNode> {
        @Override
        public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            String className = input.name.replace('/', '.');
            if (!"net.minecraft.server.level.ServerPlayer".equals(className)) {
                return input;
            }
            System.out.println("[GodSlayerCore] Transforming ServerPlayer: " + className);

            for (MethodNode method : input.methods) {
                if ("teleportTo".equals(method.name) &&
                        "(Lnet/minecraft/server/level/ServerLevel;DDDFF)V".equals(method.desc)) {
                    injectHead(method,
                            "com/godslayer/NativeGuard",
                            "shouldBlockTeleport",
                            "(Lnet/minecraft/server/level/ServerPlayer;DDD)Z",
                            () -> {
                                InsnList head = new InsnList();
                                head.add(new VarInsnNode(ALOAD, 0)); // player
                                head.add(new VarInsnNode(DLOAD, 2)); // x
                                head.add(new VarInsnNode(DLOAD, 4)); // y
                                head.add(new VarInsnNode(DLOAD, 6)); // z
                                head.add(new MethodInsnNode(INVOKESTATIC,
                                        "com/godslayer/NativeGuard",
                                        "shouldBlockTeleport",
                                        "(Lnet/minecraft/server/level/ServerPlayer;DDD)Z",
                                        false));
                                LabelNode label = new LabelNode();
                                head.add(new JumpInsnNode(IFEQ, label));
                                head.add(new InsnNode(RETURN));
                                head.add(label);
                                return head;
                            }
                    );
                }
            }
            return input;
        }

        @Override
        public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }

        @Override
        public Set<Target> targets() {
            return Set.of(Target.targetClass("net.minecraft.server.level.ServerPlayer"));
        }
    }

    // ===== 辅助注入方法 =====

    private static void injectHead(MethodNode method, String owner, String methodName, String desc, java.util.function.Supplier<InsnList> headSupplier) {
        InsnList head = headSupplier.get();
        if (head != null) {
            method.instructions.insert(head);
        }
    }

    private static void injectHeadAndTail(MethodNode method, String owner, String methodName, String desc,
                                          java.util.function.Function<MethodNode, InsnList> headSupplier,
                                          java.util.function.Function<MethodNode, InsnList> tailSupplier) {
        if (headSupplier != null) {
            InsnList head = headSupplier.apply(method);
            if (head != null) {
                method.instructions.insert(head);
            }
        }
        if (tailSupplier != null) {
            InsnList tail = tailSupplier.apply(method);
            if (tail != null) {
                method.instructions.add(tail);
            }
        }
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        makeMyModLoadable();
        System.out.println("[GodSlayerMainCore] onLoad called, otherServices: " + otherServices);
    }
}