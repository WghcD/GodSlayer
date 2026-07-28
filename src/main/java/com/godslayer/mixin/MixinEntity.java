package com.godslayer.mixin;

import com.godslayer.NativeGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class MixinEntity {

    /**
     * 拦截remove(RemovalReason) - 防止实体被移除
     */
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.BYPASS) return;
        if (NativeGuard.isHoldingGodSlayer(player)) {
            ci.cancel();
        }
    }

    /**
     * 拦截discard() - 另一条移除路径
     */
    @Inject(method = "discard", at = @At("HEAD"), cancellable = true)
    private void onDiscard(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.BYPASS) return;
        if (NativeGuard.isHoldingGodSlayer(player)) {
            ci.cancel();
        }
    }

    /**
     * 拦截setRemoved(RemovalReason) - 底层状态修改
     */
    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void onSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (NativeGuard.BYPASS) return;
        if (NativeGuard.isHoldingGodSlayer(player)) {
            ci.cancel();
        }
    }
}