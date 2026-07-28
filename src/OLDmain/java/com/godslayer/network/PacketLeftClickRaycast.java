package com.godslayer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.godslayer.GodSlayerMod;
import com.godslayer.NativeGuard;

public class PacketLeftClickRaycast {
    private final int playerId;
    private final double startX, startY, startZ;
    private final double endX, endY, endZ;

    public PacketLeftClickRaycast(int playerId, Vec3 start, Vec3 end) {
        this.playerId = playerId;
        this.startX = start.x;
        this.startY = start.y;
        this.startZ = start.z;
        this.endX = end.x;
        this.endY = end.y;
        this.endZ = end.z;
    }

    public static void encode(PacketLeftClickRaycast msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.playerId);
        buf.writeDouble(msg.startX);
        buf.writeDouble(msg.startY);
        buf.writeDouble(msg.startZ);
        buf.writeDouble(msg.endX);
        buf.writeDouble(msg.endY);
        buf.writeDouble(msg.endZ);
    }

    public static PacketLeftClickRaycast decode(FriendlyByteBuf buf) {
        return new PacketLeftClickRaycast(
                buf.readInt(),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
        );
    }

    public static void handle(PacketLeftClickRaycast msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (player.getId() != msg.playerId) return; // 安全校验

            // 检查是否持有神剑（服务端校验）
            if (player.getMainHandItem().isEmpty() ||
                    !(player.getMainHandItem().getItem() instanceof com.godslayer.GodSlayerSwordItem)) {
                return;
            }
            if (NativeGuard.BYPASS) return;

            // 执行射线击杀
            Level level = player.level();
            Vec3 start = new Vec3(msg.startX, msg.startY, msg.startZ);
            Vec3 end = new Vec3(msg.endX, msg.endY, msg.endZ);

            // 搜索范围膨胀
            AABB searchBox = new AABB(start, end).inflate(5.0);
            List<Entity> targets = level.getEntities(player, searchBox, e -> e != player);
            targets.sort(Comparator.comparingDouble(e -> e.distanceToSqr(start)));

            int killedCount = 0;
            for (Entity target : targets) {
                Optional<Vec3> hit = target.getBoundingBox().clip(start, end);
                if (hit.isPresent()) {
                    boolean success = GodSlayerMod.killEntity(target);
                    if (success) killedCount++;
                }
            }
            if (killedCount > 0) {
                GodSlayerMod.LOGGER.info("Server killed {} entities via raycast from {}", killedCount, player.getName().getString());
            }
        });
        context.setPacketHandled(true);
    }
}