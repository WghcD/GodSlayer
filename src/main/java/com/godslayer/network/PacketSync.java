package com.godslayer.network;

import com.godslayer.client.ClientKilledEntities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSync {
    private final int entityId;

    public PacketSync(int entityId) {
        this.entityId = entityId;
    }

    // ===== 编码 =====
    public static void encode(PacketSync msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    // ===== 解码 =====
    public static PacketSync decode(FriendlyByteBuf buf) {
        return new PacketSync(buf.readInt());
    }

    // ===== 处理（客户端执行） =====
    public static void handle(PacketSync msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            // 将 ID 添加到客户端的“已击杀”集合中
            ClientKilledEntities.add(msg.entityId);
        });
        context.setPacketHandled(true);
    }
}