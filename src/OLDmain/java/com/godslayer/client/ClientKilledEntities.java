package com.godslayer.client;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientKilledEntities {
    private static final Set<Integer> KILLED = ConcurrentHashMap.newKeySet();

    public static void add(int id) {
        KILLED.add(id);
    }

    public static boolean contains(int id) {
        return KILLED.contains(id);
    }

    public static void remove(int id) {
        KILLED.remove(id);
    }

    // 可选：在玩家退出/维度切换时清理，避免内存泄漏
    public static void clear() {
        KILLED.clear();
    }
}