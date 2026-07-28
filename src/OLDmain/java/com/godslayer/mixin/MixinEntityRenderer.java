package com.godslayer.mixin;

import com.godslayer.client.ClientKilledEntities;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {//客户端渲染拦截大法
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(Entity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
        // 使用客户端的被杀集合（通过网络同步）
        if (ClientKilledEntities.contains(entity.getId())) {
            ci.cancel();
        }
    }
}
