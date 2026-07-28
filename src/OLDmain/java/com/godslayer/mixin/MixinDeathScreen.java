package com.godslayer.mixin;

import com.godslayer.NativeGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

//拦截原版死亡GUI
@Mixin(DeathScreen.class)
public class MixinDeathScreen {

    /**
     * 拦截死亡屏幕初始化 - 直接关闭
     */
    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void onInit(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && NativeGuard.isHoldingGodSlayer(mc.player)) {
            ci.cancel();
            mc.setScreen(null);
            // 强制恢复客户端状态
            mc.player.setHealth(20);
            mc.player.deathTime=0;
        }
    }

    /**
     * 拦截死亡屏幕渲染 - 不显示
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(net.minecraft.client.gui.GuiGraphics guiGraphics,
                          int mouseX, int mouseY, float partialTick,
                          CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && NativeGuard.isHoldingGodSlayer(mc.player)) {
            ci.cancel();
            mc.player.setHealth(20);
            mc.player.deathTime=0;
        }
    }
}





