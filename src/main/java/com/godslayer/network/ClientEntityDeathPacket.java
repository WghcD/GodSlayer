package com.godslayer.network;


import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

public class ClientEntityDeathPacket {


    /**
     * 向所有正在追踪此实体的玩家发送死亡动画包和实体移除包，
     * 以清除客户端残留。
     *
     * @param entity 目标实体（应在强制移除前调用，否则追踪信息可能已丢失）
     */
    public static void sendDeathPacketsToTrackers(Entity entity) {
        if (entity.level().isClientSide) return;

        ServerLevel level = (ServerLevel) entity.level();
        // 获取 ChunkMap.TrackedEntity
        Object tracked = getTrackedEntity(level, entity.getId());
        if (tracked == null) return;

        // 发送死亡动画（EntityEvent 3 = DEATH）
        ClientboundEntityEventPacket deathAnimPacket = new ClientboundEntityEventPacket(entity, (byte) 3);
        broadcastToTrackers(tracked, deathAnimPacket);

        // 发送移除实体包
        ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(entity.getId());
        broadcastToTrackers(tracked, removePacket);
    }

    /**
     * 通过反射从 ChunkMap.entityMap 获取 TrackedEntity
     */
    private static Object getTrackedEntity(ServerLevel level, int entityId) {
        try {
            // ChunkMap = level.getChunkSource().chunkMap
            Object chunkSource = level.getChunkSource();
            Field chunkMapField = findField(chunkSource.getClass(), "chunkMap");
            Object chunkMap = chunkMapField.get(chunkSource);

            // entityMap 是 Int2ObjectMap<ChunkMap.TrackedEntity>
            Field entityMapField = findField(chunkMap.getClass(), "entityMap", "f_140150_");
            Object entityMap = entityMapField.get(chunkMap);
            Method getMethod = entityMap.getClass().getMethod("get", int.class);
            return getMethod.invoke(entityMap, entityId);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 向 TrackedEntity 的所有“观看者”广播数据包
     */
    private static void broadcastToTrackers(Object trackedEntity, Packet<?> packet) {
        try {
            // TrackedEntity.seenBy 是 Set<ServerPlayerConnection>
            Field seenByField = findField(trackedEntity.getClass(), "seenBy", "f_140475_");
            Set<?> seenBy = (Set<?>) seenByField.get(trackedEntity);
            for (Object conn : seenBy) {
                // ServerPlayerConnection.send(Packet)
                Method sendMethod = conn.getClass().getMethod("send", Packet.class);
                sendMethod.invoke(conn, packet);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 辅助反射方法（兼容 MCP/SRG 名称）
    private static Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

}