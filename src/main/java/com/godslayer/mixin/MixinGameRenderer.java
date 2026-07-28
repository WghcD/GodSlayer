package com.godslayer.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    /**
     * 在 bobView 方法执行前拦截，取消视野抖动
     * bobView 方法负责处理受伤时的屏幕晃动
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void onBobView(CallbackInfo ci) {
        // 取消方法执行，彻底去除抖动
        ci.cancel();
    }
}