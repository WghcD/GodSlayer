package com.godslayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber
public class NativeGuard {

    public static volatile boolean BYPASS = false;

    // 记录曾持有神剑的玩家 UUID（直到玩家断开连接）
    private static final Set<UUID> EVER_HELD = new HashSet<>();

    // ===== 检查玩家是否已完全初始化 =====
    private static boolean isPlayerReady(Player player) {
        if (player == null) return false;
        try {
            if (player.getInventory() == null) return false;
            if (player.level() == null) return false;
            return true;
        } catch (NullPointerException | IllegalStateException e) {
            return false;
        }
    }

    public static boolean isHoldingGodSlayer(Entity entity) {
        if (!(entity instanceof LivingEntity lEntity)) return false;
        return isHoldingGodSlayer(lEntity);
    }

    // ===== 持有判定 =====
    public static boolean isHoldingGodSlayer(LivingEntity entity) {
        if (!(entity instanceof Player player)) return false;
        return isHoldingGodSlayer(player);
    }

    private static boolean isHoldingGodSlayerOnMainHand(Player player) {
        return player != null &&
                !player.getMainHandItem().isEmpty() &&
                player.getMainHandItem().getItem() instanceof GodSlayerSwordItem;
    }

    public static boolean isHoldingGodSlayer(Player player) {
        if (player == null) return false;
        if (!isPlayerReady(player)) return false;

        UUID uuid = player.getUUID();
        if (EVER_HELD.contains(uuid)) {
            return true;
        }

        // 检查背包（包括主手）
        try {
            for (ItemStack stack : player.getInventory().items) {
                if (!stack.isEmpty() && stack.getItem() instanceof GodSlayerSwordItem) {
                    EVER_HELD.add(uuid);
                    return true;
                }
            }
        } catch (NullPointerException ignored) {
        }
        return false;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EVER_HELD.remove(event.getEntity().getUUID());
    }

    // ===== 防护检查 =====
    public static boolean shouldBlockSetHealth(LivingEntity entity, float health) {
        if (BYPASS) return false;
        if (!(entity instanceof Player player)) return false;
        if (!isHoldingGodSlayer(player)) return false;
        return health < entity.getHealth();
    }

    public static boolean shouldBlockHurt(LivingEntity entity) {
        if (BYPASS) return false;
        if (!(entity instanceof Player player)) return false;
        return isHoldingGodSlayer(player);
    }

    public static boolean shouldBlockDeath(LivingEntity entity) {
        if (BYPASS) return false;
        if (!(entity instanceof Player player)) return false;
        return isHoldingGodSlayer(player);
    }

    public static boolean shouldBlockRemove(Entity entity) {
        if (BYPASS) return false;
        if (!(entity instanceof Player player)) return false;
        return isHoldingGodSlayer(player);
    }

    public static boolean shouldBlockSetDeathTime(LivingEntity entity, int deathTime) {
        if (BYPASS) return false;
        if (!(entity instanceof Player player)) return false;
        if (!isHoldingGodSlayer(player)) return false;
        return deathTime > 0;
    }

    public static boolean shouldBlockDropLoot(Player player) {
        if (BYPASS) return false;
        if (player == null) return false;
        return isHoldingGodSlayer(player);
    }

    // ===== 强制复活 =====
    public static void forceRespawn(Player player) {
        GodSlayerMod.LOGGER.fatal("[GodSlayer]正在尝试在forceRespawn方法中强制阻止玩家死亡");
        if (!isPlayerReady(player)) return;
        try {
            player.setHealth(20F);
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.setAbsorptionAmount(0.0F);
            player.deathTime = 0;
            //player.getActiveEffects().forEach(effect -> player.removeEffect(effect.getEffect()));

            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.setHealth(20F);
                serverPlayer.getAbilities().invulnerable = false;
                serverPlayer.setNoGravity(false);
                serverPlayer.setInvisible(false);
                serverPlayer.onUpdateAbilities();
            }
        } catch (NullPointerException ignored) {}
    }
}