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
//拦截伪造死亡GUI
@Mixin(Minecraft.class)
public class MixinMinecraftClient {

    private static final List<Class<?>> ALLOWED_SCREENS = Arrays.asList(
            InventoryScreen.class,
            CreativeModeInventoryScreen.class,
            ContainerScreen.class,
            ChatScreen.class,
            PauseScreen.class,          // ESC菜单
            TitleScreen.class,
            ProgressScreen.class,
            DisconnectedScreen.class
    );

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        Player player = mc.player;
        if (player == null) return;
        if (!NativeGuard.isHoldingGodSlayer(player)) return;

        if (screen == null) return;

        boolean allowed = false;
        for (Class<?> cls : ALLOWED_SCREENS) {
            if (cls.isAssignableFrom(screen.getClass())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            ci.cancel();
            if (mc.screen != null) {
                mc.setScreen(null); // 强制关闭当前屏幕
            }
        }
    }
}
