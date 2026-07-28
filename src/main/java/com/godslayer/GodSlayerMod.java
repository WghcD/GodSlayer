package com.godslayer;

import com.godslayer.event.ProtectionEvents;
import com.godslayer.network.PacketLeftClickRaycast;
import com.godslayer.network.PacketSync;

import com.godslayer.unsafe.EntityKlassHacker;
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

    static {
        GodSlayerNative.extractAndLoadNative();
    }

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
        if(!GodSlayerNative.isLoaded()){GodSlayerNative.extractAndLoadNative();}
        LOGGER.info("Native library loaded: {}", GodSlayerNative.isLoaded());

        if (GodSlayerNative.isLoaded()) {
            try {
                //GodSlayerNative.nativeDisableThreats();
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
        if (target.level().isClientSide) {
            LOGGER.fatal("客户端不能执行killEntity");

            return false; // 客户端不能执行
        }
        if (target == null || target.isRemoved()) return false;
        //if (target instanceof Player) return false;

        boolean killed = false;


        if (GodSlayerNative.isLoaded()) {
            LOGGER.fatal("正在尝试使用native杀单个实体");
            try {
                GodSlayerNative.nativeKillEntity(target.level(), target.getId());
                killed = true;
                LOGGER.warn("Killed entity {} via native", target);
            } catch (Exception e) {
                LOGGER.warn("nativeObliterateEntity failed for {}: {}", target, e.getMessage());
            }
        }


        if (!target.isRemoved()) {


            if (target instanceof LivingEntity living) {
                try {
                    living.setHealth(0.0f);
                    living.deathTime = 114514;
                } catch (Exception ignored) {}
            }


            try {
                target.remove(Entity.RemovalReason.DISCARDED);
                killed = true;
            } catch (Exception ignored) {}

            // 2c. 如果还活着，尝试从 EntityStorage 强制移除（反射）
            if (!target.isRemoved()) {
                try {
                    Level level = target.level();
                    if (level instanceof ServerLevel serverLevel) {

                        Field storageField = Level.class.getDeclaredField("entityStorage");
                        storageField.setAccessible(true);
                        Object storage = storageField.get(level);
                        Method removeMethod = storage.getClass().getMethod("remove", Entity.class);
                        removeMethod.invoke(storage, target);
                        killed = true;
                    }
                } catch (Exception ignored) {}
            }


            if (target.isRemoved()) {
                try {
                    CompoundTag tag = new CompoundTag();
                    target.saveWithoutId(tag);
                    tag.getAllKeys().forEach(tag::remove);
                } catch (Exception ignored) {}
            }
        }

        //LOGGER.fatal("native击杀实体失败");

    
        if (!target.isRemoved()) {
            try {
                target.discard();
                killed = true;
            } catch (Exception ignored) {}
        }



        if (!target.isRemoved()) {//滚蛋吧
            try {
                LOGGER.fatal("[GodSlayer]顽固实体，送到虚空，眼不见为净");
                target.teleportTo(target.getX(), -100000, target.getZ());
                target.setNoGravity(true);
                target.setInvisible(true);

            } catch (Exception e) {
                LOGGER.warn("Failed to banish entity {} to void  ", target);
            }
        }



        if(!target.isRemoved()){
            LOGGER.fatal("尝试klass攻击");
            EntityKlassHacker.hack(target);
            target.remove(Entity.RemovalReason.KILLED);
            target.discard();
        }


        int id = target.getId();//标记
        KILLED_ENTITIES.add(id);



        return true;
    }

    /**
     * 击杀指定维度中的所有生物（非玩家实体）。
     * 先尝试常规 Java 枚举击杀，再调用 Native 批量秒杀作为补充。
     * @param level 目标维度
     * @return 击杀的实体数量
     */
    public static void killAllEntities(Level level) {
        if (level == null) return;
        if (level.isClientSide) return; // 仅在服务端执行

        if (GodSlayerNative.isLoaded()) {
            LOGGER.fatal("正在尝试使用native清level");
            try {
                GodSlayerNative.nativeMassacre(level);
            } catch (Exception e) {

            }
        }


/*
        // 尝试通过反射获取所有实体的集合
        Collection<Entity> entityList = getEntitiesUnsafe(level);
        if (entityList == null) return; // 实在拿不到就放弃

        // 快照复制，防止并发修改
        List<Entity> snapshot = new ArrayList<>(entityList);
        for (Entity entity : snapshot) {
            if (entity != null && !entity.isRemoved()) {
                killEntity(entity);
            }
        }

*/
    }

    /**
     * 通过反射暴力提取 Level 中的实体集合。
     * 自动适配 Mojang / MCP / SRG 等常见映射名称。
     */
    @SuppressWarnings("unchecked")
    private static Collection<Entity> getEntitiesUnsafe(Level level) {



        try {
            // 1. 尝试直接调用 EntityGetter 的 getAllEntities (Mojang 映射)
            try {
                Method getAll = level.getClass().getMethod("getAllEntities");
                Iterable<Entity> iterable = (Iterable<Entity>) getAll.invoke(level);
                // 转换为 Collection（通常返回的是 LazyIterable，无法直接获取 size，但能遍历）
                List<Entity> list = new ArrayList<>();
                iterable.forEach(list::add);
                return list;
            } catch (NoSuchMethodException ignored) {}

            // 2. 尝试通过 ServerLevel 的 getEntities (某些 MCP 映射)
            if (level instanceof ServerLevel serverLevel) {
                try {
                    Method getEntities = ServerLevel.class.getMethod("getEntities");
                    return (Collection<Entity>) getEntities.invoke(serverLevel);
                } catch (NoSuchMethodException ignored) {}
            }

            // 3. 暴力反射字段：遍历所有声明的字段，寻找 Entity 集合
            for (Field field : level.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(level);
                if (value instanceof Collection<?> coll) {
                    if (!coll.isEmpty() && coll.iterator().next() instanceof Entity) {
                        return (Collection<Entity>) coll;
                    }
                }
                // 也检查 Map 类型（entitiesById 等）
                if (value instanceof Map<?, ?> map) {
                    if (!map.isEmpty() && map.values().iterator().next() instanceof Entity) {
                        return (Collection<Entity>) map.values();
                    }
                }
                // fastutil Int2ObjectMap
                if (value instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> map) {
                    if (!map.isEmpty() && map.values().iterator().next() instanceof Entity) {
                        return (Collection<Entity>) map.values();
                    }
                }
            }

            // 4. 最后尝试从父类（EntityGetter）接口寻找默认方法
            //    此处省略，因为反射接口方法比较繁琐，且通常前几步已足够

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


}