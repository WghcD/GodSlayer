package com.godslayer;


import com.godslayer.utils.EntityHelper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import com.godslayer.GodSlayerMod;
import net.minecraftforge.common.ForgeMod;


import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraftforge.common.ForgeMod;

import com.google.common.collect.Multimap;
import com.google.common.collect.HashMultimap;

import java.util.UUID;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class NormalSwordItem extends SwordItem {




    public NormalSwordItem() {
        super(Tiers.NETHERITE, 1, -2.4f,//伤害
                new Properties().fireResistant().durability(999999));
    }


    private static final UUID REACH_MODIFIER_UUID =
            UUID.fromString("12345678-1214-1234-1234-635456789abc");

    public NormalSwordItem(Tier tier, int attackDamageModifier,
                              float attackSpeedModifier, Properties properties) {
        super(tier, attackDamageModifier, attackSpeedModifier, properties);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        // 获取父类返回的不可变 Multimap
        Multimap<Attribute, AttributeModifier> original = super.getDefaultAttributeModifiers(slot);
        // 创建可变的 Multimap 副本
        Multimap<Attribute, AttributeModifier> modifiers = ArrayListMultimap.create(original);

        if (slot == EquipmentSlot.MAINHAND) {
            Attribute reachAttribute = ForgeMod.ENTITY_REACH.get();
            AttributeModifier reachModifier = new AttributeModifier(
                    REACH_MODIFIER_UUID,
                    "GodSlayer Reach Modifier",
                    800.0,
                    AttributeModifier.Operation.ADDITION
            );
            modifiers.put(reachAttribute, reachModifier);
        }
        return modifiers;
    }

    // ===== 右键 =====
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {


        List<Entity> allEntities = EntityHelper.getAllEntities(level);

        for (Entity entity : allEntities) {
            if (!NativeGuard.isHoldingGodSlayer(entity)) {
                NativeGuard.AddBlockedEntity(entity);

            }

        }

        return InteractionResultHolder.success(player.getItemInHand(hand));
    }




}