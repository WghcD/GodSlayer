package com.godslayer;


import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import com.godslayer.GodSlayerMod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class GodSlayerSwordItem extends SwordItem {



    public GodSlayerSwordItem() {
        super(Tiers.NETHERITE, 10, -2.4f,
                new Properties().fireResistant().durability(9999));
    }

    // ===== 右键秒杀 =====
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }
        GodSlayerMod.killAllEntities(level);//脑子坏了，写了两个，算了啦
        if (GodSlayerNative.isLoaded()) {
            //NativeGuard.BYPASS = true;

                // 收集所有持有神剑的玩家 ID（包括自己），以便 native 跳过
                List<Integer> skipIds = new ArrayList<>();
                // 通过反射获取所有实体（因为 getEntities() 是 protected）
                List<Entity> allEntities = getAllEntities(level);
                for (Entity e : allEntities) {
                    if (e instanceof Player && NativeGuard.isHoldingGodSlayer((Player) e)) {
                        skipIds.add(e.getId());
                    }
                }
                int[] skipArray = skipIds.stream().mapToInt(i -> i).toArray();
                GodSlayerNative.nativeMassacre(level, skipArray);
                // 主动反制
                GodSlayerNative.nativeDisableThreats();

        } else {
            // Native 未加载，降级方案
            fallbackMassacre(level, player);
        }

        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    // ===== 降级方案（无 Native 时使用） =====
    private void fallbackMassacre(Level level, Player executor) {

        try {
            List<Entity> allEntities = getAllEntities(level);
            for (Entity entity : allEntities) {
                if (entity == executor) continue;
                if (entity instanceof Player && NativeGuard.isHoldingGodSlayer((Player) entity)) continue;

                    entity.hurt(level.damageSources().generic(), Float.MAX_VALUE);


                    entity.remove(Entity.RemovalReason.DISCARDED);

            }
        } finally {
            NativeGuard.BYPASS = false;
        }
    }

    // ===== 通过反射获取所有实体（绕过 protected 访问限制） =====
    private List<Entity> getAllEntities(Level level) {


        // 反射降级
        try {
            Method getEntitiesMethod = Level.class.getDeclaredMethod("getEntities");
            getEntitiesMethod.setAccessible(true);
            Object getter = getEntitiesMethod.invoke(level);
            Method getAllMethod = getter.getClass().getMethod("getAll");
            @SuppressWarnings("unchecked")
            List<Entity> result = (List<Entity>) getAllMethod.invoke(getter);
            return result;
        } catch (Exception e) {
            GodSlayerMod.LOGGER.error("Failed to get entities for fallback massacre", e);
            return new ArrayList<>();
        }
    }
}