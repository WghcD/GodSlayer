package com.godslayer.mixin;

import com.godslayer.NativeGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public int deathTime;

    /**
     * 拦截setHealth - 阻止血量降低
     */
    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void onSetHealth(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.BYPASS) return;
        if (NativeGuard.isHoldingGodSlayer(player) && health < getHealth()) {
            ci.cancel();
        }
    }

    /**
     * 拦截hurt - 完全免疫伤害
     */
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.BYPASS) return;
        if (NativeGuard.isHoldingGodSlayer(player)) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    /**
     * 拦截die - 阻止死亡流程
     */
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void onDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.BYPASS) return;
        if (NativeGuard.isHoldingGodSlayer(player)) {
            ci.cancel();
            // 强制恢复
            player.setHealth(20.0F);
            System.out.println("在Mixin中， die方法被拦截");
            this.deathTime = 0;
        }
    }

    /**
     * 每个Tick强制重置deathTime
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.BYPASS) return;
        if (NativeGuard.isHoldingGodSlayer(player)) {
            if (this.deathTime > 0) {
                this.deathTime = 0;

            }
        }
    }
}