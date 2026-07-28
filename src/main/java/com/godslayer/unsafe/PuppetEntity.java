package com.godslayer.unsafe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 傀儡实体类。
 * <p>
 * 必须继承自 {@link Entity} 且<strong>不添加任何实例字段</strong>，
 * 以确保对象内存布局与原实体完全一致，避免 GC 扫描时因引用图差异而崩溃。
 * 其 {@link #tick()} 方法会立刻将实体从世界中移除。
 */
public class PuppetEntity extends Entity {

    /**
     * 正常途径不会使用该构造器。
     * 此处仅用于满足编译要求，实际实例由 {@link sun.misc.Unsafe#allocateInstance(Class)} 创建。
     */
    public PuppetEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {

    }

    @Override
    public void tick() {
        // 确保只执行一次自杀操作
        if (!this.isRemoved()) {
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag p_20052_) {

    }

    @Override
    protected void addAdditionalSaveData(CompoundTag p_20139_) {

    }
}