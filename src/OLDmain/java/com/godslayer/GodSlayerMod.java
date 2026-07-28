package com.godslayer;

import com.godslayer.event.ProtectionEvents;
import com.godslayer.network.PacketLeftClickRaycast;
import com.godslayer.network.PacketSync;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

@Mod(GodSlayerMod.MOD_ID)
public class GodSlayerMod {
    public static final String MOD_ID = "godslayer";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final Set<Integer> KILLED_ENTITIES = java.util.concurrent.ConcurrentHashMap.newKeySet();



    // 注册物品
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Item> GOD_SLAYER_SWORD =
            ITEMS.register("god_slayer_sword", GodSlayerSwordItem::new);

    // 注册创造模式标签页
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);


    public static final RegistryObject<CreativeModeTab> GOD_SLAYER_TAB =
            CREATIVE_TABS.register("godslayer", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.godslayer"))
                    // 将神剑的物品实例作为图标
                    .icon(() -> new ItemStack(GOD_SLAYER_SWORD.get()))
                    .displayItems((params, output) -> {
                        output.accept(GOD_SLAYER_SWORD.get());
                    })
                    .build());

    public static final String PROTOCOL_VERSION = "1.0";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public GodSlayerMod() {
        LOGGER.fatal("[GoadSlayer]主类已被加载");
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册物品和创造模式标签页
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);

        CHANNEL.registerMessage(0, PacketSync.class,
                PacketSync::encode,
                PacketSync::decode,
                PacketSync::handle
        );

        CHANNEL.registerMessage(1, PacketLeftClickRaycast.class,
                PacketLeftClickRaycast::encode,
                PacketLeftClickRaycast::decode,
                PacketLeftClickRaycast::handle
        );
        // 注册事件监听（ProtectionEvents 类已用 @Mod.EventBusSubscriber 注解，无需手动注册）
        MinecraftForge.EVENT_BUS.register(ProtectionEvents.class);

        LOGGER.info("GodSlayer Sword Mod initialized.");
        //if(!GodSlayerNative.isLoaded()){GodSlayerNative.extractAndLoadNative();}
        LOGGER.info("Native library loaded: {}", GodSlayerNative.isLoaded());

        if (GodSlayerNative.isLoaded()) {
            try {
                GodSlayerNative.nativeDisableThreats();
                LOGGER.info("Active countermeasures applied.");
            } catch (Exception e) {
                LOGGER.warn("Failed to apply active countermeasures.", e);
            }
        }
    }









    /*工具方法，所有击杀都走这里*/

    /**
     * 尝试多种方式彻底击杀一个实体，包括 Native 抹除、常规移除、NBT 清理等。
     * @param target 目标实体
     * @return true 表示实体已被移除（或确认死亡），false 表示操作失败
     */
    public static boolean killEntity(Entity target) {
        if (target == null || target.isRemoved()) return false;
        //if (target instanceof Player) return false; // 不处理玩家（由其他逻辑保护）

        boolean killed = false;

        // 1. 如果 Native 可用，优先使用 nativeObliterateEntity（最强力）
        if (GodSlayerNative.isLoaded()) {
            LOGGER.warn("正在尝试使用native杀单个实体");
            try {
                GodSlayerNative.nativeKillEntity(target.level(), target.getId());
                killed = true;
                LOGGER.warn("Killed entity {} via native", target);
            } catch (Exception e) {
                LOGGER.warn("nativeObliterateEntity failed for {}: {}", target, e.getMessage());
            }
        }

        // 2. 若 Native 未成功，执行 Java 层常规击杀
        if (!killed && !target.isRemoved()) {
            // 2a. 如果是 LivingEntity，先强制扣血并设死亡时间
            if (target instanceof LivingEntity living) {
                try {
                    living.setHealth(0.0f);
                    living.deathTime = 114514;
                } catch (Exception ignored) {}
            }

            // 2b. 调用 remove 方法
            try {
                target.remove(Entity.RemovalReason.DISCARDED);
                killed = true;
            } catch (Exception ignored) {}

            // 2c. 如果还活着，尝试从 EntityStorage 强制移除（反射）
            if (!target.isRemoved()) {
                try {
                    Level level = target.level();
                    if (level instanceof ServerLevel serverLevel) {
                        // 通过反射获取 entityStorage 并调用 remove
                        Field storageField = Level.class.getDeclaredField("entityStorage");
                        storageField.setAccessible(true);
                        Object storage = storageField.get(level);
                        Method removeMethod = storage.getClass().getMethod("remove", Entity.class);
                        removeMethod.invoke(storage, target);
                        killed = true;
                    }
                } catch (Exception ignored) {}
            }

            // 2d. 清除持久化数据（防止复活）
            if (target.isRemoved()) {
                try {
                    CompoundTag tag = new CompoundTag();
                    target.saveWithoutId(tag);
                    tag.getAllKeys().forEach(tag::remove);
                } catch (Exception ignored) {}
            }
        }

        // 3. 若实体依然存在，尝试 discard
        if (!target.isRemoved()) {
            try {
                target.discard();
                killed = true;
            } catch (Exception ignored) {}
        }


        if (!target.isRemoved()) {//滚蛋吧
            try {
                LOGGER.fatal("[GodSlayer]顽固实体，送到虚空，眼不见为净");
                target.teleportTo(target.getX(), -10000, target.getZ());
                target.setNoGravity(true);
                target.setInvisible(true);

            } catch (Exception e) {
                LOGGER.warn("Failed to banish entity {} to void  ", target);
            }
        }

        int id = target.getId();//标记
        KILLED_ENTITIES.add(id);

        return killed;
    }

    /**
     * 击杀指定维度中的所有生物（非玩家实体）。
     * 先尝试常规 Java 枚举击杀，再调用 Native 批量秒杀作为补充。
     * @param level 目标维度
     * @return 击杀的实体数量
     */
    public static int killAllEntities(Level level) {
        if (level == null) return 0;
        if (level.isClientSide) return 0; // 仅在服务端执行

        int count = 0;
        LOGGER.debug("[GodSlayer]开始尝试击杀该维度所有Entity");
        // 收集所有实体（通过反射获取 getEntities().getAll()）
        List<Entity> allEntities;
        try {
            Method getEntitiesMethod = Level.class.getDeclaredMethod("getEntities");
            getEntitiesMethod.setAccessible(true);
            Object getter = getEntitiesMethod.invoke(level);
            Method getAllMethod = getter.getClass().getMethod("getAll");
            allEntities = (List<Entity>) getAllMethod.invoke(getter);
        } catch (Exception e) {
            LOGGER.debug("Failed to retrieve entity list for killAllEntities", e);
            return 0;
        }

        // 先收集所有持有神剑的玩家 ID（跳过）
        List<Integer> skipIds = new ArrayList<>();
        for (Entity e : allEntities) {
            if (e instanceof Player player && NativeGuard.isHoldingGodSlayer(player)) {
                skipIds.add(e.getId());
            }
        }

        // 常规 Java 击杀（逐个调用 killEntity）
        for (Entity entity : allEntities) {
            if (entity.isRemoved()) continue;
            if (entity instanceof Player) continue; // 跳过玩家
            //if (NativeGuard.isBANISHED(entity)) continue; // 已标记放逐的不重复处理
            if (killEntity(entity)) {
                count++;
            }
        }

        // 如果 Native 已加载，再调用 nativeMassacre 补杀（跳过持有者）
        if (GodSlayerNative.isLoaded()) {
            try {
                int[] skipArray = skipIds.stream().mapToInt(i -> i).toArray();
                GodSlayerNative.nativeMassacre(level, skipArray);
                LOGGER.info("Native massacre executed, skipped {} holders", skipArray.length);
            } catch (Exception e) {
                LOGGER.error("Native massacre failed", e);
            }
        }

        return count;
    }


    //这个函数纯粹是为了native调用
    public static List<Entity> getAllEntities(Level level) {
        try {
            Field storageField = Level.class.getDeclaredField("entityStorage");
            storageField.setAccessible(true);
            Object storage = storageField.get(level);
            Field mapField = storage.getClass().getDeclaredField("entityMap");
            mapField.setAccessible(true);
            Object map = mapField.get(storage);
            Method valuesMethod = map.getClass().getMethod("values");
            Collection<?> values = (Collection<?>) valuesMethod.invoke(map);
            // 转换为 List<Entity>
            List<Entity> list = new ArrayList<>();
            for (Object obj : values) {
                if (obj instanceof Entity) {
                    list.add((Entity) obj);
                }
            }
            return list;
        } catch (Exception e) {
            LOGGER.error("Failed to get all entities via reflection", e);
            return Collections.emptyList();
        }
    }
}