// ========== MyEntityHelper.java (完整版) ==========
package com.godslayer.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.*;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.*;

public final class MyEntityDataRemover {
    private static final Logger LOGGER = LogManager.getLogger();

    // ---------- 字段缓存 ----------
    // ServerLevel
    private static Field SERVER_LEVEL_ENTITY_MANAGER;
    private static Field SERVER_LEVEL_ENTITY_TICK_LIST;

    private static Field SERVER_CHUNK_CACHE_CHUNK_MAP;

    // PersistentEntitySectionManager
    private static Field PESM_SECTION_STORAGE;
    private static Field PESM_KNOWN_UUIDS;
    private static Field PESM_ENTITY_LOOKUP;

    // EntityLookup
    private static Field EL_BY_ID;
    private static Field EL_BY_UUID;

    // EntitySectionStorage
    private static Field ESS_SECTIONS;

    // EntitySection
    private static Field ENTITY_SECTION_STORAGE;

    // ClassInstanceMultiMap
    private static Field CIM_BY_CLASS;
    private static Field CIM_ALL_INSTANCES;

    // EntityTickList
    private static Field ETL_ACTIVE;

    // ChunkMap
    private static Field CM_ENTITY_MAP;

    // ClientLevel
    private static Field CLIENT_LEVEL_ENTITY_STORAGE;
    private static Field CLIENT_LEVEL_TICKING_ENTITIES;

    // TransientEntitySectionManager
    private static Field TESM_ENTITY_LOOKUP;
    private static Field TESM_SECTION_STORAGE;

    // Entity.level (private 字段)
    private static Field ENTITY_LEVEL_FIELD;

    // ---------- static 初始化块 ----------
    static {
        try {
            // ServerLevel 字段
            SERVER_LEVEL_ENTITY_MANAGER = findField(ServerLevel.class, "entityManager", "f_143244_");
            SERVER_LEVEL_ENTITY_TICK_LIST = findField(ServerLevel.class, "entityTickList", "f_143243_");
            //SERVER_LEVEL_CHUNK_CACHE = ObfuscationReflectionHelper.findField(ServerLevel.class, "field_19364_");

            // ServerChunkCache 的 chunkMap 字段
            Class<?> serverChunkCacheClass = Class.forName("net.minecraft.server.level.ServerChunkCache");
            SERVER_CHUNK_CACHE_CHUNK_MAP = findField(serverChunkCacheClass, "chunkMap", "f_8325_");

            // PersistentEntitySectionManager
            PESM_SECTION_STORAGE = findField(PersistentEntitySectionManager.class, "sectionStorage", "f_157495_");
            PESM_KNOWN_UUIDS = findField(PersistentEntitySectionManager.class, "knownUuids", "f_157491_");
            PESM_ENTITY_LOOKUP = findField(PersistentEntitySectionManager.class, "visibleEntityStorage", "f_157494_");

            // EntityLookup
            EL_BY_ID = findField(EntityLookup.class, "byId", "f_156807_");
            EL_BY_UUID = findField(EntityLookup.class, "byUuid", "f_156808_");

            // EntitySectionStorage
            ESS_SECTIONS = findField(EntitySectionStorage.class, "sections", "f_156852_");

            // EntitySection
            ENTITY_SECTION_STORAGE = findField(EntitySection.class, "storage", "f_156827_");

            // ClassInstanceMultiMap
            CIM_BY_CLASS = findField(ClassInstanceMultiMap.class, "byClass", "f_13527_");
            CIM_ALL_INSTANCES = findField(ClassInstanceMultiMap.class, "allInstances", "f_13529_");

            // EntityTickList
            ETL_ACTIVE = findField(EntityTickList.class, "active", "f_156903_");

            // ChunkMap
            CM_ENTITY_MAP = findField(ChunkMap.class, "entityMap", "f_140150_");

            // ClientLevel
            CLIENT_LEVEL_ENTITY_STORAGE = findField(ClientLevel.class, "entityStorage", "f_171631_");
            CLIENT_LEVEL_TICKING_ENTITIES = findField(ClientLevel.class, "tickingEntities", "f_171630_");

            // TransientEntitySectionManager
            TESM_ENTITY_LOOKUP = findField(TransientEntitySectionManager.class, "entityStorage", "f_157637_");
            TESM_SECTION_STORAGE = findField(TransientEntitySectionManager.class, "sectionStorage", "f_157638_");

            // Entity.level
            ENTITY_LEVEL_FIELD = findField(Entity.class, "level", "f_19853_");

        } catch (Exception e) {
            LOGGER.error("[MyEntityHelper] 静态初始化失败", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 通过混淆名/反混淆名查找字段（Forge 专用）
     */
    private static Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Field field = ObfuscationReflectionHelper.findField(clazz, name);
                field.setAccessible(true);      // 虽然后续用 Unsafe，但保留便于调试
                return field;
            } catch (Exception ignored) {
            }
        }
        throw new RuntimeException("无法找到字段: " + clazz.getName() + "，候选名: " + Arrays.toString(names));
    }

    // ---------- 对外接口 ----------
    public static void ForceRemoveEntity(Entity entity) {
        if (entity == null) return;

        // 通过 Unsafe 获取 private 字段 level，绕过所有访问检查
        Level level = (Level) MyReflectionHelper.getFieldValue(entity, ENTITY_LEVEL_FIELD);
        if (level == null) {
            LOGGER.warn("实体 {} 的 level 字段为空，无法移除", entity);
            return;
        }

        if (level.isClientSide) {
            removeFromClient((ClientLevel) level, entity);
        } else {
            removeFromServer((ServerLevel) level, entity);
        }
    }

    // ---------- 服务端移除 ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeFromServer(ServerLevel level, Entity entity) {
        try {
            // 获取核心管理器
            PersistentEntitySectionManager<Entity> manager =
                    (PersistentEntitySectionManager<Entity>) MyReflectionHelper.getFieldValue(level, SERVER_LEVEL_ENTITY_MANAGER);
            EntityLookup<Entity> lookup = (EntityLookup<Entity>) MyReflectionHelper.getFieldValue(manager, PESM_ENTITY_LOOKUP);
            EntitySectionStorage<Entity> sectionStorage =
                    (EntitySectionStorage<Entity>) MyReflectionHelper.getFieldValue(manager, PESM_SECTION_STORAGE);
            EntityTickList tickList = (EntityTickList) MyReflectionHelper.getFieldValue(level, SERVER_LEVEL_ENTITY_TICK_LIST);


            Object chunkSource = level.getChunkSource();
            ChunkMap chunkMap = (ChunkMap) MyReflectionHelper.getFieldValue(chunkSource, SERVER_CHUNK_CACHE_CHUNK_MAP);

            // 快速清理已知容器
            removeFromLookup(lookup, entity);
            removeKnownUuids(manager, entity);
            removeFromTickList(tickList, entity);
            removeFromChunkMap(chunkMap, entity);
            removeFromSectionStorage(sectionStorage,entity);

            // 深度递归清理（穿透所有代理/封装对象）
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            deepCleanObject(manager, entity, visited);
            deepCleanObject(sectionStorage, entity, visited);
            deepCleanObject(level, entity, visited);
            deepCleanObject(chunkSource, entity, visited);
            deepCleanObject(chunkMap, entity, visited);

            // 实体自身清理
            //entity.remove(Entity.RemovalReason.DISCARDED);
            entity.invalidateCaps();

            LOGGER.info("[MyEntityHelper] 服务端暴力移除成功: {}", entity);

        } catch (Exception e) {
            LOGGER.error("[MyEntityHelper] 服务端移除失败", e);
        }
    }

    // ---------- 客户端移除 ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeFromClient(ClientLevel level, Entity entity) {
        try {
            TransientEntitySectionManager<Entity> manager =
                    (TransientEntitySectionManager<Entity>) MyReflectionHelper.getFieldValue(level, CLIENT_LEVEL_ENTITY_STORAGE);
            EntityLookup<Entity> lookup = (EntityLookup<Entity>) MyReflectionHelper.getFieldValue(manager, TESM_ENTITY_LOOKUP);
            EntitySectionStorage<Entity> sectionStorage =
                    (EntitySectionStorage<Entity>) MyReflectionHelper.getFieldValue(manager, TESM_SECTION_STORAGE);
            EntityTickList tickList = (EntityTickList) MyReflectionHelper.getFieldValue(level, CLIENT_LEVEL_TICKING_ENTITIES);

            // 快速清理
            removeFromLookup(lookup, entity);
            removeFromTickList(tickList, entity);

            removeFromSectionStorage(sectionStorage,entity);

            // 深度清理
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            deepCleanObject(sectionStorage, entity, visited);
            deepCleanObject(level, entity, visited);
            deepCleanObject(manager, entity, visited);

            entity.remove(Entity.RemovalReason.DISCARDED);
            entity.invalidateCaps();

            LOGGER.info("[MyEntityHelper] 客户端暴力移除成功: {}", entity);

        } catch (Exception e) {
            LOGGER.error("[MyEntityHelper] 客户端移除失败", e);
        }
    }

    // ---------- 快速清理辅助方法 ----------
    private static void removeFromLookup(EntityLookup<Entity> lookup, Entity entity) {
        try {
            Int2ObjectMap<Entity> byId = (Int2ObjectMap<Entity>) MyReflectionHelper.getFieldValue(lookup, EL_BY_ID);
            CollectionHelper.removeFromInt2ObjectMap(lookup, EL_BY_ID, byId, entity.getId());


            Map<UUID, Entity> byUuid = (Map<UUID, Entity>) MyReflectionHelper.getFieldValue(lookup, EL_BY_UUID);
            CollectionHelper.removeFromHashMap(lookup, EL_BY_UUID, byUuid, entity.getUUID());
        } catch (Exception e) {
            LOGGER.error("清理 EntityLookup 失败", e);
        }
    }

    private static void removeKnownUuids(PersistentEntitySectionManager<Entity> manager, Entity entity) {
        try {
            Set<UUID> known = (Set<UUID>) MyReflectionHelper.getFieldValue(manager, PESM_KNOWN_UUIDS);
            CollectionHelper.removeFromHashSet(manager, PESM_KNOWN_UUIDS, known, entity.getUUID());
        } catch (Exception e) {
            LOGGER.error("清理 knownUuids 失败", e);
        }
    }

    private static void removeFromTickList(EntityTickList tickList, Entity entity) {
        try {
            Int2ObjectMap<Entity> active = (Int2ObjectMap<Entity>) MyReflectionHelper.getFieldValue(tickList, ETL_ACTIVE);
            CollectionHelper.removeFromInt2ObjectMap(tickList, ETL_ACTIVE, active, entity.getId());
        } catch (Exception e) {
            LOGGER.error("清理 EntityTickList 失败", e);
        }
    }

    private static void removeFromChunkMap(ChunkMap chunkMap, Entity entity) {
        try {
            Int2ObjectMap<Object> entityMap = (Int2ObjectMap<Object>) MyReflectionHelper.getFieldValue(chunkMap, CM_ENTITY_MAP);
            CollectionHelper.removeFromInt2ObjectMap(chunkMap, CM_ENTITY_MAP, entityMap, entity.getId());
        } catch (Exception e) {
            LOGGER.error("清理 ChunkMap 失败", e);
        }
    }


    private static void removeFromSectionStorage(EntitySectionStorage<Entity> storage, Entity entity) {
        try {
            Long2ObjectMap<EntitySection<Entity>> sections =
                    (Long2ObjectMap<EntitySection<Entity>>) MyReflectionHelper.getFieldValue(storage, ESS_SECTIONS);
            if (sections == null) return;
            for (EntitySection<Entity> section : sections.values()) {
                if (section == null) continue;
                ClassInstanceMultiMap<Entity> cim =
                        (ClassInstanceMultiMap<Entity>) MyReflectionHelper.getFieldValue(section, ENTITY_SECTION_STORAGE);
                if (cim == null) continue;

                // 清理 byClass (Map<Class<?>, List<Entity>>)
                Map<Class<?>, List<Entity>> byClass =
                        (Map<Class<?>, List<Entity>>) MyReflectionHelper.getFieldValue(cim, CIM_BY_CLASS);
                if (byClass != null) {
                    CollectionHelper.removeFromMapValues(cim, CIM_BY_CLASS, byClass, entity);
                }



                // 清理 allInstances (List<Entity>)
                List<Entity> allInstances =
                        (List<Entity>) MyReflectionHelper.getFieldValue(cim, CIM_ALL_INSTANCES);
                if (allInstances != null) {
                    CollectionHelper.removeFromArrayList(cim, CIM_ALL_INSTANCES, allInstances, entity);
                }
            }
        } catch (Exception e) {
            LOGGER.error("清理 section storage 失败", e);
        }
    }

    // ---------- 深度递归清理 ----------
    private static void deepCleanObject(Object obj, Entity target, Set<Object> visited) {
        /*if (obj == null || visited.contains(obj)) return;
        // 如果对象不是核心游戏对象，或者为隐藏类，直接返回
        if (!MyReflectionHelper.isCoreGameObject(obj)) return;
        visited.add(obj);

        // 获取所有字段（跳过隐藏类的字段）
        for (Field field : MyReflectionHelper.getAllFields(obj.getClass())) {
            Object fieldValue = MyReflectionHelper.getFieldValue(obj, field);
            if (fieldValue == null) continue;

            // 如果字段值是容器，执行复制-替换
            if (MyReflectionHelper.isContainer(fieldValue)) {
                Object newContainer = cloneContainerAndRemove(fieldValue, target);
                if (newContainer != null) {
                    MyReflectionHelper.setFieldValue(obj, field, newContainer);
                    LOGGER.warn("替换了 {}.{} 容器", obj.getClass().getSimpleName(), field.getName());
                }
            } else {
                // 如果字段值是核心游戏对象，递归进入
                if (MyReflectionHelper.isCoreGameObject(fieldValue)) {
                    deepCleanObject(fieldValue, target, visited);
                }
            }
        }*/
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object cloneContainerAndRemove(Object container, Entity target) {
        // 支持 Int2ObjectMap
        if (container instanceof Int2ObjectMap) {
            Int2ObjectMap<Entity> map = (Int2ObjectMap<Entity>) container;
            if (!map.containsKey(target.getId())) return null;
            Int2ObjectMap<Entity> newMap = new Int2ObjectOpenHashMap<>(map);
            newMap.remove(target.getId());
            return newMap;
        }
        // 支持 Long2ObjectMap
        if (container instanceof Long2ObjectMap) {
            Long2ObjectMap<Entity> map = (Long2ObjectMap<Entity>) container;
            Long2ObjectMap<Entity> newMap = new Long2ObjectOpenHashMap<>();
            boolean removed = false;
            for (Long2ObjectMap.Entry<Entity> entry : map.long2ObjectEntrySet()) {
                Entity value = entry.getValue();
                if (value != target) {
                    newMap.put(entry.getLongKey(), value);
                } else {
                    removed = true;
                }
            }
            return removed ? newMap : null;
        }
        // 普通 Map
        if (container instanceof Map) {
            Map<Object, Object> map = (Map) container;
            boolean removed = false;
            Map<Object, Object> newMap = new HashMap<>();
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                if (entry.getKey() instanceof UUID && target.getUUID().equals(entry.getKey())) {
                    removed = true;
                    continue;
                }
                if (entry.getKey() instanceof Integer && target.getId() == (int) entry.getKey()) {
                    removed = true;
                    continue;
                }
                if (entry.getValue() == target) {
                    removed = true;
                    continue;
                }
                newMap.put(entry.getKey(), entry.getValue());
            }
            return removed ? newMap : null;
        }
        // Collection (List, Set, etc.)
        if (container instanceof Collection) {
            Collection<Entity> coll = (Collection<Entity>) container;
            if (!coll.contains(target)) return null;
            Collection<Entity> newColl;
            if (coll instanceof List) {
                newColl = new ArrayList<>(coll);
            } else if (coll instanceof Set) {
                newColl = new HashSet<>(coll);
            } else {
                newColl = new ArrayList<>(coll);
            }
            newColl.removeIf(e -> e == target);
            return newColl;
        }
        return null;
    }
}