package com.godslayer.mixin;

import com.godslayer.GodSlayerMod;
import com.godslayer.NativeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class MixinServerPlayer {

    // 拦截 teleportTo(ServerLevel, double, double, double, float, float) - 常用的传送方法
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void onTeleportTo(ServerLevel level, double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        Player player = (Player)(Object)this;
        if (!NativeGuard.isHoldingGodSlayer(player)) return;
        if (NativeGuard.BYPASS) return;

        // 阻止传送到 -999 坐标 (永爱之刃的死亡标记)
        if (y<-1||x>100000||y>100000||y>300) {
            // 可选：记录日志，便于调试
             GodSlayerMod.LOGGER.fatal("Blocked teleport to invalid positiion for player " + player.getName().getString());
            ci.cancel();
            // 可选：强制将玩家移回安全位置（例如世界出生点）
            // player.teleportTo(level, 0, 0, 0);
        }
    }





    private static boolean isFromForeverLoveSword() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (className.startsWith("com.wzz.forever_love_sword.")) {
                return true;
            }
        }
        return false;
    }

    // 如果想更精确，可以使用堆栈判断，但通常 -999 坐标已足够标识
}