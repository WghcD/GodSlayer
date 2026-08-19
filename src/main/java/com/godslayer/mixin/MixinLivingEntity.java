package com.godslayer.mixin;

import com.godslayer.NativeGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
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

    @Shadow
    public abstract void heal(float p_21116_);

    @Shadow
    public abstract boolean isHolding(Item p_21056_);

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
        if(self.getTags().contains("GodSlayerKilled")){
            health=0;
        }
    }

    /**
     * 拦截hurt - 完全免疫伤害
     */
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self instanceof Player player)) return;

        if (NativeGuard.isHoldingGodSlayer(player)) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    //拦截击退
    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void onKnockback(double strength, double x, double z, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.isHoldingGodSlayer(player)) {
            ci.cancel();
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
            player.setHealth(player.getMaxHealth());
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
            if (this.getHealth() <= 0) {
                self.setHealth(20.0F);
            }
        }
        if(self.getTags().contains("GodSlayerKilled")||NativeGuard.shouldBlockAll(self)){
            self.discard();
            ci.cancel();
        }
    }
    /** 核心：getHealth 恒为 0 → isDeadOrDying() == true → 原版 isAlive() 链式返回 false */
    @Inject(method = "getHealth", at = @At("HEAD"), cancellable = true)
    private void nativeguard$getHealth(CallbackInfoReturnable<Float> cir) {
        if (NativeGuard.shouldBlockAll((LivingEntity) (Object) this)) {
            cir.setReturnValue(0.0F);
        }
        if(NativeGuard.isHoldingGodSlayer((Entity)(Object)this)){
            cir.setReturnValue(20.0F);
        }
    }

    /** 显式覆盖（防自定义子类重写 isAlive 绕过 getHealth 链） */
    @Inject(method = "isAlive", at = @At("HEAD"), cancellable = true)
    private void nativeguard$isAlive(CallbackInfoReturnable<Boolean> cir) {
        if (NativeGuard.shouldBlockAll((LivingEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }





    /** 兜底：主 tick 已被 Level 层拦截，此为防御性注入 */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void nativeguard$tick(CallbackInfo ci) {
        if (NativeGuard.shouldBlockAll((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }

    /** 移动/物理兜底 */
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void nativeguard$aiStep(CallbackInfo ci) {
        if (NativeGuard.shouldBlockAll((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void nativeguard$travel(Vec3 travelVector, CallbackInfo ci) {
        if (NativeGuard.shouldBlockAll((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}