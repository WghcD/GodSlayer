package com.godslayer.mixin;


import com.godslayer.NativeGuard;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 EntityRenderDispatcher.render() 入口处直接取消，
 * 完全跳过该实体的所有 OpenGL/LWJGL 绘制指令。
 *
 * 这是处理非 LivingEntity（如矿车、物品实体、箭矢、TNT 等）的唯一方式，
 * 因为 Forge 只为 LivingEntity 提供了 RenderLivingEvent<span data-allow-html class='source-item source-aggregated' data-group-key='source-group-4' data-url='https://maven&#46;fabricmc&#46;net/docs/yarn&#45;21w05b&#43;build&#46;8/net/minecraft/client/render/entity/EntityRenderDispatcher&#46;html' data-id='turn4search3'><span data-allow-html class='source-item-num' data-group-key='source-group-4' data-id='turn4search3' data-url='https://maven&#46;fabricmc&#46;net/docs/yarn&#45;21w05b&#43;build&#46;8/net/minecraft/client/render/entity/EntityRenderDispatcher&#46;html'><span class='source-item-num-name' data-allow-html>fabricmc.net</span><span data-allow-html class='source-item-num-count'></span></span></span>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderer2 {

    /**
     * 拦截主 render 方法
     * 方法签名参考 1.20.1 SRG: m_114384_ / official: render
     * 使用宽松匹配以兼容重载
     */

    @Inject(
            method = "render*",       // 匹配 render(...) 所有重载
            at = @At("HEAD"),
            cancellable = true
    )
    private <E extends Entity> void onRenderEntity(
            E entity,
            double x, double y, double z,
            float yaw, float partialTicks,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci) {
        if (NativeGuard.shouldBlockAll(entity)) {
            //System.out.println("已攔截一個Entity的渲染");
            ci.cancel();
        }
    }

}
