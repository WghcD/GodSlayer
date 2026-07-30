package com.godslayer.event;

import com.godslayer.GodSlayerMod;
import com.godslayer.GodSlayerNative;
import com.godslayer.NativeGuard;
import com.godslayer.network.PacketLeftClickRaycast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


import java.util.*;

import static com.godslayer.GodSlayerMod.LOGGER;
import static com.godslayer.GodSlayerMod.killEntity;

import static net.minecraft.world.effect.MobEffects.*;

@Mod.EventBusSubscriber
public class ProtectionEvents {

    private static boolean isHoldingGodSlayer(Player player) {
        return player != null && NativeGuard.isHoldingGodSlayer(player);
    }

    // ===== 反死亡 =====
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (isHoldingGodSlayer(player) && !NativeGuard.BYPASS) {
                GodSlayerMod.LOGGER.fatal("[GodSlayer]被保护玩家被打出了ondeath，正在事件反死亡");
                event.setCanceled(true);
                NativeGuard.forceRespawn(player);
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.syncPacketPositionCodec(
                            serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ()
                    );
                }
            }
        }
    }

    // ===== 伤害拦截 + 反击（击杀伤害源） =====
    //其实能被穿透防护到这一步的不多，毕竟有mixin和core
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (!(target instanceof Player player)) return;
        if (!isHoldingGodSlayer(player)) return;
        if (NativeGuard.BYPASS) return;

        DamageSource source = event.getSource();
        Entity sourceEntity = source.getEntity();
        if (sourceEntity != null) {
            // 反击：将伤害源送入击杀
            killEntity(sourceEntity);
        }

        event.setCanceled(true);
        player.setHealth(20.0F);
        player.deathTime=0;
    }

    // 左键攻击并命中实体
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttackEntity(AttackEntityEvent event) {
        Player attacker = event.getEntity();
        if (!isHoldingGodSlayer(attacker)) return;


        Entity target = event.getTarget();
        if (target == null) return;



        //event.setCanceled(true);
        killEntity(target);
    }

    // ===== Tick 守护：调用 native 强制恢复 =====
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (!isHoldingGodSlayer(player)) return;
        if (NativeGuard.BYPASS) return;

        // 启用创造飞行
        if (!player.getAbilities().mayfly) {
            player.getAbilities().mayfly = true;
            if (player instanceof ServerPlayer sp) sp.onUpdateAbilities();
        }


        //饱食和饱和
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);



        player.removeAllEffects();

        //  持续给予buff
        player.addEffect(new MobEffectInstance(MOVEMENT_SPEED, 20 * 50, 0, false, false, true));
        player.addEffect(new MobEffectInstance(DIG_SPEED, 20 * 50, 254, false, false, true));
        player.addEffect(new MobEffectInstance(DAMAGE_RESISTANCE, 20 * 50, 10, false, false, true));
        if (GodSlayerNative.isLoaded()) {

            //GodSlayerNative.nativeTickGuard(player);
        }
        player.setHealth(20.0F);
        player.deathTime = 0;



    }

    @SubscribeEvent
    public static void onLivingKnockBack(LivingKnockBackEvent event) {
        // 取消击退
        if (NativeGuard.isHoldingGodSlayer(event.getEntity())) {
            event.setCanceled(true);
        }
    }



    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (level.isClientSide) return; // 仅在服务端执行

        // 射线追踪，距离可调整（这里设为 5 格）
        HitResult hit = player.pick(5.0D, 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            // 检查方块是否可破坏（非空气）
            if (!state.isAir()) {
                // 掉落物品（模拟玩家破坏）
                Block.dropResources(state, level, pos, level.getBlockEntity(pos), player, player.getMainHandItem());
                // 将方块设为空气
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

            }
        }
        handleLeftClick(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        handleLeftClick(event);
    }

    private static void handleLeftClick(PlayerInteractEvent event) {
        Player player = event.getEntity();
        Level level = player.level();

        GodSlayerMod.LOGGER.fatal("正在处理左击空气的逻辑");

        if (!isHoldingGodSlayer(player)) return;
        if (NativeGuard.BYPASS) return;



        // 计算射线
        Vec3 start = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = start.add(look.scale(60.0));

        // 发送包到服务端
        GodSlayerMod.CHANNEL.sendToServer(new PacketLeftClickRaycast(player.getId(), start, end));
    }



//同样反伪造死亡GUI
    @Mod.EventBusSubscriber(modid = GodSlayerMod.MOD_ID)
    public class ClientGuiHandler {//反伪造死亡GUI
        // 允许正常显示的白名单界面（不会强制关闭）
        private static final List<Class<?>> ALLOWED_SCREENS = Arrays.asList(
                InventoryScreen.class,          // 背包
                CreativeModeInventoryScreen.class, // 创造模式物品栏
                ContainerScreen.class,          // 箱子、熔炉等容器
                ChatScreen.class,               // 聊天输入
                PauseScreen.class,              //  ESC 键暂停菜单
                //DeathScreen.class,              // 真正的死亡界面s
                TitleScreen.class,               // 标题界面
                CommandBlockEditScreen.class

                // 按需添加
        );


        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;
            if (!NativeGuard.isHoldingGodSlayer(player)) return;
            //if (player.getHealth() <= 0) return;
            Screen screen = mc.screen;
            if (screen != null && !isAllowedScreen(screen)) {
                mc.setScreen(null);
            }
        }



        private static boolean isAllowedScreen(Screen screen) {
            for (Class<?> cls : ALLOWED_SCREENS) {
                if (cls.isAssignableFrom(screen.getClass())) {
                    return true;
                }
            }
            return false;
        }
    }
}