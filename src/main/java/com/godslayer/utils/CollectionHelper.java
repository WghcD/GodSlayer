package com.godslayer.utils;

import sun.misc.Unsafe;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 通过 Unsafe 替换持有容器的字段引用，实现“构建新容器 -> 替换字段”。
 * 不直接读写容器内部数组。
 */
public final class CollectionHelper {
    private static final Unsafe UNSAFE = MyReflectionHelper.getUnsafe();

    /**
     * 从 Int2ObjectOpenHashMap 所属字段中删除指定 key。
     *
     * @param owner 持有该 map 的对象
     * @param field owner 中该 map 的字段
     * @param map   原 Int2ObjectMap 实例
     * @param key   要删除的 int key
     */
    public static void removeFromInt2ObjectMap(Object owner, Field field, Object map, int key) {
        if (owner == null || field == null || !(map instanceof Int2ObjectMap)) return;
        try {
            Int2ObjectMap<Object> oldMap = (Int2ObjectMap<Object>) map;
            Int2ObjectOpenHashMap<Object> newMap = new Int2ObjectOpenHashMap<>();
            boolean removed = false;

            for (Int2ObjectMap.Entry<Object> entry : oldMap.int2ObjectEntrySet()) {
                int k = entry.getIntKey();
                if (k == key) {
                    removed = true;
                } else {
                    newMap.put(k, entry.getValue());
                }
            }

            if (removed) {
                UNSAFE.putObject(owner, UNSAFE.objectFieldOffset(field), newMap);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 从 HashMap 所属字段中删除指定 key。
     */
    public static void removeFromHashMap(Object owner, Field field, Object map, Object key) {
        if (owner == null || field == null || !(map instanceof Map)) return;
        try {
            Map<Object, Object> oldMap = (Map<Object, Object>) map;
            Map<Object, Object> newMap = new HashMap<>();
            boolean removed = false;

            for (Map.Entry<Object, Object> entry : oldMap.entrySet()) {
                if (Objects.equals(entry.getKey(), key)) {
                    removed = true;
                } else {
                    newMap.put(entry.getKey(), entry.getValue());
                }
            }

            if (removed) {
                UNSAFE.putObject(owner, UNSAFE.objectFieldOffset(field), newMap);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 从 HashSet 所属字段中删除指定元素。
     */
    public static void removeFromHashSet(Object owner, Field field, Object set, Object element) {
        if (owner == null || field == null || !(set instanceof Set)) return;
        try {
            Set<Object> oldSet = (Set<Object>) set;
            Set<Object> newSet = new HashSet<>();
            boolean removed = false;

            for (Object e : oldSet) {
                if (Objects.equals(e, element)) {
                    removed = true;
                } else {
                    newSet.add(e);
                }
            }

            if (removed) {
                UNSAFE.putObject(owner, UNSAFE.objectFieldOffset(field), newSet);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 从 ArrayList 所属字段中按引用删除指定元素。
     */
    public static void removeFromArrayList(Object owner, Field field, Object list, Object element) {
        if (owner == null || field == null || !(list instanceof List)) return;
        try {
            List<Object> oldList = (List<Object>) list;
            List<Object> newList = new ArrayList<>(oldList.size());
            boolean removed = false;

            for (Object e : oldList) {
                if (e != element) {
                    newList.add(e);
                } else {
                    removed = true;
                }
            }

            if (removed) {
                UNSAFE.putObject(owner, UNSAFE.objectFieldOffset(field), newList);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 遍历 Map 的所有 value。
     * 如果 value 是 List 或 Set，则构建删除了指定元素的新容器，并最终替换整个 Map 字段。
     */
    public static void removeFromMapValues(Object owner, Field field, Object map, Object element) {
        if (owner == null || field == null || !(map instanceof Map)) return;
        try {
            Map<Object, Object> oldMap = (Map<Object, Object>) map;
            Map<Object, Object> newMap = new HashMap<>();
            boolean changed = false;

            for (Map.Entry<Object, Object> entry : oldMap.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof List) {
                    List<Object> oldList = (List<Object>) value;
                    List<Object> newList = new ArrayList<>(oldList.size());
                    boolean removed = false;

                    for (Object e : oldList) {
                        if (e != element) {
                            newList.add(e);
                        } else {
                            removed = true;
                        }
                    }

                    if (removed) {
                        changed = true;
                        newMap.put(key, newList);
                    } else {
                        newMap.put(key, value);
                    }
                } else if (value instanceof Set) {
                    Set<Object> oldSet = (Set<Object>) value;
                    Set<Object> newSet = new HashSet<>();
                    boolean removed = false;

                    for (Object e : oldSet) {
                        if (Objects.equals(e, element)) {
                            removed = true;
                        } else {
                            newSet.add(e);
                        }
                    }

                    if (removed) {
                        changed = true;
                        newMap.put(key, newSet);
                    } else {
                        newMap.put(key, value);
                    }
                } else {
                    newMap.put(key, value);
                }
            }

            if (changed) {
                UNSAFE.putObject(owner, UNSAFE.objectFieldOffset(field), newMap);
            }
        } catch (Exception ignored) {
        }
    }
}