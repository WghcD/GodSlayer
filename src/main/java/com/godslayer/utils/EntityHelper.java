package com.godslayer.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.*;

/**
 * 实体辅助工具类，通过反射获取世界中的实体信息。
 * 依赖 {@link ReflectHelper} 提供的 Unsafe 字段读写能力。
 */
public final class EntityHelper {

    // ---------- 混淆字段名（1.20.1 Forge） ----------
    private static final String SERVER_ENTITY_MANAGER = "f_143244_";   // ServerLevel.entityManager
    private static final String CLIENT_ENTITY_STORAGE = "f_171631_";   // ClientLevel.entityStorage
    private static final String MANAGER_VISIBLE_STORAGE = "f_157494_"; // PersistentEntitySectionManager.visibleEntityStorage
    private static final String TRANS_SECTION_ENTITY_STORAGE = "f_157637_"; // TransientEntitySectionManager.entityStorage（即 EntityLookup）
    private static final String ENTITY_LOOKUP_BY_ID = "f_156807_";     // EntityLookup.byId

    private EntityHelper() {}

    /**
     * 获取当前世界中所有已加载的实体（包括服务端和客户端）。
     * 返回的列表是副本，修改不会影响原数据。
     *
     * @param level 当前世界（ServerLevel 或 ClientLevel）
     * @return 所有实体的列表，若失败则返回空列表
     */
    public static List<Entity> getAllEntities(Level level) {
        if (level == null) return Collections.emptyList();

        if (level instanceof ServerLevel) {
            return getServerEntities((ServerLevel) level);
        } else if (level instanceof ClientLevel) {
            return getClientEntities((ClientLevel) level);
        }
        return Collections.emptyList();
    }





    /**
     * 通过反射移除与实体UUID关联的Boss血条。
     */


    /**
     * 根据实体 ID 查找实体。
     *
     * @param level    当前世界
     * @param entityId 实体 ID
     * @return 实体对象，不存在则返回 null
     */
    public static Entity getEntityById(Level level, int entityId) {
        List<Entity> all = getAllEntities(level);
        for (Entity e : all) {
            if (e.getId() == entityId) return e;
        }
        return null;
    }

    /**
     * 根据 UUID 查找实体。
     *
     * @param level 当前世界
     * @param uuid  实体的 UUID
     * @return 实体对象，不存在则返回 null
     */
    public static Entity getEntityByUuid(Level level, UUID uuid) {
        List<Entity> all = getAllEntities(level);
        for (Entity e : all) {
            if (uuid.equals(e.getUUID())) return e;
        }
        return null;
    }

    // ---------- 内部实现 ----------

    private static List<Entity> getServerEntities(ServerLevel serverLevel) {
        try {
            // 1. 获取 PersistentEntitySectionManager
            Object manager = ReflectHelper.getFieldValue(serverLevel, SERVER_ENTITY_MANAGER);
            if (manager == null) return Collections.emptyList();

            // 2. 获取 EntityLookup (visibleEntityStorage)
            Object lookup = ReflectHelper.getFieldValue(manager, MANAGER_VISIBLE_STORAGE);
            if (lookup == null) return Collections.emptyList();

            // 3. 获取 Int2ObjectMap<Entity> byId
            Int2ObjectMap<Entity> map = (Int2ObjectMap<Entity>) ReflectHelper.getFieldValue(lookup, ENTITY_LOOKUP_BY_ID);
            if (map == null) return Collections.emptyList();

            return new ArrayList<>(map.values());
        } catch (Exception e) {
            // 打印栈跟踪以帮助调试，生产环境可替换为日志
            e.printStackTrace();
            return Collections.emptyList();
        }
    }



    private static List<Entity> getClientEntities(ClientLevel clientLevel) {
        try {
            // 1. 获取 TransientEntitySectionManager
            Object manager = ReflectHelper.getFieldValue(clientLevel, CLIENT_ENTITY_STORAGE);
            if (manager == null) return Collections.emptyList();

            // 2. TransientEntitySectionManager 的 entityStorage 字段就是 EntityLookup
            Object lookup = ReflectHelper.getFieldValue(manager, TRANS_SECTION_ENTITY_STORAGE);
            if (lookup == null) return Collections.emptyList();

            // 3. 获取 Int2ObjectMap<Entity> byId
            Int2ObjectMap<Entity> map = (Int2ObjectMap<Entity>) ReflectHelper.getFieldValue(lookup, ENTITY_LOOKUP_BY_ID);
            if (map == null) return Collections.emptyList();

            return new ArrayList<>(map.values());
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}