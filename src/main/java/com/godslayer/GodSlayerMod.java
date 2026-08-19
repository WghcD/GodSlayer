package com.godslayer;

import com.godslayer.event.*;
import com.godslayer.network.*;
import com.godslayer.utils.*;
import com.godslayer.unsafe.*;



import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.godslayer.utils.EntityAllRemover.eraseEntity;
import static com.godslayer.utils.EntityPowerRemover.neutralizeEntityObject;
import static net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField;

@Mod(GodSlayerMod.MOD_ID)
public class GodSlayerMod {
    public static final String MOD_ID = "godslayer";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final Set<Integer> KILLED_ENTITIES = ConcurrentHashMap.newKeySet();

    static {


        LOGGER.info("GodSlayerStaticBlockCalled.");


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
        if (target == null) return false;




        MyEntityDataRemover.ForceRemoveEntity(target);

        eraseEntity(target);

        if (target.level().isClientSide) {
            LOGGER.fatal("客户端执行killEntity中...");

            if (target.level() instanceof ClientLevel clientLevel) {
                clientLevel.removeEntity(target.getId(), Entity.RemovalReason.UNLOADED_TO_CHUNK);
                return true;
            }




            return false;
        }








        boolean killed = false;





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




            if (target.isRemoved()) {
                try {
                    CompoundTag tag = new CompoundTag();
                    target.saveWithoutId(tag);
                    tag.getAllKeys().forEach(tag::remove);
                } catch (Exception ignored) {}
            }
        }




        if(!target.isRemoved()){
            LOGGER.fatal("尝试klass攻击");
            EntityKlassHacker.hack(target);
            //target.remove(Entity.RemovalReason.KILLED);
            target.discard();
        }



        if (GodSlayerNative.isLoaded()&&!target.isRemoved()) {
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

                Method getY = Entity.class.getDeclaredMethod("getX");
                ReflectHelper.replaceMethodInvocation(getY, (Function<Object[], Object>) args -> 10.0);

                Vec3 newPos = new Vec3(114514, -114514, 114514);
                // 使用 ReflectHelper 直接设置 Entity 的 position 字段
                ReflectHelper.setFieldValue(target, "position", newPos);

            } catch (Exception e) {
                LOGGER.warn("Failed to banish entity {} to void  ", target);
                e.printStackTrace();
            }
        }


        ClientEntityDeathPacket.sendDeathPacketsToTrackers(target);





        if(!target.isRemoved()) {
            try {
                LOGGER.warn(" \n\n\n\n   Fuck.Start Erase. Thak you,glm. \n\n\n ", target);
                eraseEntity(target);
            } catch (Throwable e) {
                LOGGER.warn(" \n\n\n\n   Fuck! Trhowed.  \n\n\n ", target);
                e.printStackTrace();
            }
        }



        NativeGuard.AddBlockedEntity(target);//標記

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



        LOGGER.fatal("正在清level");


        List<Entity> allEntities = EntityHelper.getAllEntities(level);

        for (Entity entity : allEntities) {
            if (!NativeGuard.isHoldingGodSlayer(entity)) {
                entity.discard();
                killEntity(entity);
            }

        }

        if (level.isClientSide) return; // native仅在服务端执行

        if (GodSlayerNative.isLoaded()) {

            try {
                GodSlayerNative.nativeMassacre(level);
            } catch (Exception e) {

            }
        }





    }




}