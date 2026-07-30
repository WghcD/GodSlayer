package com.godslayer.mixin;

import com.godslayer.GodSlayerMod;
import com.godslayer.client.ClientKilledEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class MixinEntityKilled {

    // 拦截 tick()
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (GodSlayerMod.KILLED_ENTITIES.contains(self.getId()) ||
                ClientKilledEntities.contains(self.getId())||self.getTags().contains("GodSlayerKilled")) {
            ci.cancel();
        }
    }


    @Inject(method = "setPos(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void onSetPos(Vec3 pos, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (GodSlayerMod.KILLED_ENTITIES.contains(self.getId()) ||
                ClientKilledEntities.contains(self.getId())||self.getTags().contains("GodSlayerKilled")) {
            ci.cancel();
        }
    }

    // 拦截 moveTo
    @Inject(method = "moveTo(DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void onMoveTo(double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (GodSlayerMod.KILLED_ENTITIES.contains(self.getId()) ||
                ClientKilledEntities.contains(self.getId())||self.getTags().contains("GodSlayerKilled")) {
            ci.cancel();
        }
    }


}