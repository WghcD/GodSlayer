#include "godslayer.h"
#include <windows.h>
#include <debugapi.h>
#include <cstring>
#include <vector>
#include <set>
#include <atomic>
#include <jni.h>

// ---------- 调试宏 ----------
#define LOG_DEBUG(fmt, ...) { \
    char buf[512]; \
    sprintf_s(buf, sizeof(buf), "[GodSlayer Native] " fmt "\n", ##__VA_ARGS__); \
    OutputDebugStringA(buf); \
}

// ===================================================================
// 全局缓存（使用 Unsafe 偏移量 + 类型匹配，不依赖具体字段名）
// ===================================================================
static jobject    g_unsafeInstance = nullptr;     // sun.misc.Unsafe 全局引用

// Level -> EntityStorage 偏移
static jclass     g_levelClass = nullptr;
static jlong      g_offset_level_entityStorage = -1;

// EntityStorage -> entityMap 偏移
static jclass     g_entityStorageClass = nullptr;
static jlong      g_offset_entityStorage_entityMap = -1;

// Map.remove(Object) 的 MethodID（不缓存 Class，每次局部使用）
static jmethodID  g_method_map_remove = nullptr;

// Entity 类及 getId 方法
static jclass     g_entityClass = nullptr;
static jmethodID  g_method_entity_getId = nullptr;

// 初始化完成标志
static bool       g_init_done = false;

// 新增二级偏移量
static jlong g_offset_manager_entityLookup = -1;
static jlong g_offset_entityLookup_map = -1;

// 用于批量屠杀的开关（原样保留）
std::atomic<bool> bypassFlag{ false };

// ===================================================================
// 工具：获取 Unsafe 实例
// ===================================================================
static jobject getUnsafe(JNIEnv* env) {
    jclass unsafeCls = env->FindClass("sun/misc/Unsafe");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOG_DEBUG("Unsafe class not found");
        return nullptr;
    }
    jfieldID fid = env->GetStaticFieldID(unsafeCls, "theUnsafe", "Lsun/misc/Unsafe;");
    if (env->ExceptionCheck() || !fid) {
        env->ExceptionClear();
        env->DeleteLocalRef(unsafeCls);
        LOG_DEBUG("theUnsafe field not found");
        return nullptr;
    }
    jobject unsafeObj = env->GetStaticObjectField(unsafeCls, fid);
    if (env->ExceptionCheck() || !unsafeObj) {
        env->ExceptionClear();
        env->DeleteLocalRef(unsafeCls);
        LOG_DEBUG("Failed to get Unsafe instance");
        return nullptr;
    }
    jobject globalUnsafe = env->NewGlobalRef(unsafeObj);
    env->DeleteLocalRef(unsafeObj);
    env->DeleteLocalRef(unsafeCls);
    return globalUnsafe;
}

/**
 * 按关键字模糊匹配字段类型（如包含 "EntityStorage" 或 "Map"）。
 * 同时将所有字段的 [类名#字段名 : 类型] 打印到调试输出，便于排错。
 */
static jobject findFieldByTypeDebug(JNIEnv* env, jclass clazz,
                                    const char* typeKeyword,
                                    const char* contextLabel)
{
    jclass cls = clazz;
    while (cls) {
        // 获取类名
        jclass classClass = env->FindClass("java/lang/Class");
        jmethodID clsGetName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
        jstring clsNameStr = (jstring)env->CallObjectMethod(cls, clsGetName);
        const char* clsName = env->GetStringUTFChars(clsNameStr, nullptr);

        // getDeclaredFields
        jmethodID getDeclaredFields = env->GetMethodID(classClass,
            "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
        if (!getDeclaredFields) {
            env->ReleaseStringUTFChars(clsNameStr, clsName);
            env->DeleteLocalRef(clsNameStr);
            break;
        }
        jobjectArray fields = (jobjectArray)env->CallObjectMethod(cls, getDeclaredFields);
        if (env->ExceptionCheck() || !fields) {
            env->ExceptionClear();
            env->ReleaseStringUTFChars(clsNameStr, clsName);
            env->DeleteLocalRef(clsNameStr);
            // 尝试父类
            jmethodID getSuper = env->GetMethodID(classClass, "getSuperclass", "()Ljava/lang/Class;");
            cls = getSuper ? (jclass)env->CallObjectMethod(cls, getSuper) : nullptr;
            env->DeleteLocalRef(classClass);
            continue;
        }

        jsize len = env->GetArrayLength(fields);
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        jmethodID getType = env->GetMethodID(fieldClass, "getType", "()Ljava/lang/Class;");
        jmethodID getName = env->GetMethodID(fieldClass, "getName", "()Ljava/lang/String;");

        for (jsize i = 0; i < len; ++i) {
            jobject fieldObj = env->GetObjectArrayElement(fields, i);
            jclass type = (jclass)env->CallObjectMethod(fieldObj, getType);
            jstring typeNameStr = (jstring)env->CallObjectMethod(type, clsGetName);
            const char* typeName = env->GetStringUTFChars(typeNameStr, nullptr);

            jstring fieldNameStr = (jstring)env->CallObjectMethod(fieldObj, getName);
            const char* fieldName = env->GetStringUTFChars(fieldNameStr, nullptr);

            // ======= 关键调试输出：每个字段的类型名 =======
            char debugBuf[512];
            sprintf_s(debugBuf, sizeof(debugBuf),
                "[GodSlayer Debug] %s -> class %s, field %s : type %s",
                contextLabel, clsName, fieldName, typeName);
            OutputDebugStringA(debugBuf);

            // 模糊匹配：类型名包含关键字
            bool match = (strstr(typeName, typeKeyword) != nullptr);

            env->ReleaseStringUTFChars(fieldNameStr, fieldName);
            env->DeleteLocalRef(fieldNameStr);
            env->ReleaseStringUTFChars(typeNameStr, typeName);
            env->DeleteLocalRef(typeNameStr);
            env->DeleteLocalRef(type);
            if (match) {
                env->DeleteLocalRef(fields);
                env->ReleaseStringUTFChars(clsNameStr, clsName);
                env->DeleteLocalRef(clsNameStr);
                env->DeleteLocalRef(fieldClass);
                env->DeleteLocalRef(classClass);
                return fieldObj; // 返回找到的 Field 对象
            }
            env->DeleteLocalRef(fieldObj);
        }

        env->DeleteLocalRef(fields);
        env->DeleteLocalRef(fieldClass);
        env->ReleaseStringUTFChars(clsNameStr, clsName);
        env->DeleteLocalRef(clsNameStr);

        // 继续向父类查找
        jmethodID getSuper = env->GetMethodID(classClass, "getSuperclass", "()Ljava/lang/Class;");
        cls = getSuper ? (jclass)env->CallObjectMethod(cls, getSuper) : nullptr;
        env->DeleteLocalRef(classClass);
    }
    return nullptr;
}
// ===================================================================
// 工具：查找 MethodID（通过名称 + 签名，并容忍多个候选）
// ===================================================================
static jmethodID findMethodBySig(JNIEnv* env, jclass clazz,
    const char* name, const char* sig) {
    // 直接尝试 JNI GetMethodID（大多数情况有效）
    jmethodID mid = env->GetMethodID(clazz, name, sig);
    if (mid && !env->ExceptionCheck()) return mid;
    env->ExceptionClear();

    // 退路：遍历 declared methods 手动匹配
    jmethodID getMethods = env->GetMethodID(
        env->FindClass("java/lang/Class"),
        "getDeclaredMethods",
        "()[Ljava/lang/reflect/Method;");
    if (!getMethods) return nullptr;
    jobjectArray methods = (jobjectArray)env->CallObjectMethod(clazz, getMethods);
    if (env->ExceptionCheck() || !methods) {
        env->ExceptionClear();
        return nullptr;
    }
    jsize len = env->GetArrayLength(methods);
    jclass methodClass = env->FindClass("java/lang/reflect/Method");
    jmethodID mGetName = env->GetMethodID(methodClass, "getName", "()Ljava/lang/String;");
    jmethodID mToString = env->GetMethodID(methodClass, "toString", "()Ljava/lang/String;");
    for (jsize i = 0; i < len; ++i) {
        jobject m = env->GetObjectArrayElement(methods, i);
        jstring jname = (jstring)env->CallObjectMethod(m, mGetName);
        const char* cname = env->GetStringUTFChars(jname, nullptr);
        if (strcmp(cname, name) == 0) {
            // 进一步验证签名（通过 toString 简单检查，因为 Method.toString 包含签名信息）
            jstring jsig = (jstring)env->CallObjectMethod(m, mToString);
            const char* csig = env->GetStringUTFChars(jsig, nullptr);
            // csig 形如 "public abstract java.lang.Object java.util.Map.remove(java.lang.Object)"
            // 简单判断是否包含 sig 中的特征，比如 "(Ljava/lang/Object;)Ljava/lang/Object;"
            bool match = (strstr(csig, sig) != nullptr);
            env->ReleaseStringUTFChars(jsig, csig);
            env->DeleteLocalRef(jsig);
            env->ReleaseStringUTFChars(jname, cname);
            env->DeleteLocalRef(jname);
            if (match) {
                // 由于 JNI 无法从 java.lang.reflect.Method 直接得到 MethodID，
                // 但我们已经确认了该方法存在，可以再次用 GetMethodID 获取
                jmethodID ret = env->GetMethodID(clazz, name, sig);
                env->DeleteLocalRef(methods);
                env->DeleteLocalRef(m);
                return ret;
            }
            env->ReleaseStringUTFChars(jname, cname);
        } else {
            env->ReleaseStringUTFChars(jname, cname);
        }
        env->DeleteLocalRef(m);
    }
    env->DeleteLocalRef(methods);
    return nullptr;
}

bool initJNICache(JNIEnv* env) {
    if (g_init_done) return true;

    // 1. 获取 Unsafe
    g_unsafeInstance = getUnsafe(env);
    if (!g_unsafeInstance) {
        LOG_DEBUG("initJNICache: Unsafe not available");
        return false;
    }
    jclass unsafeCls = env->GetObjectClass(g_unsafeInstance);
    jmethodID unsafe_objectFieldOffset =
        env->GetMethodID(unsafeCls, "objectFieldOffset",
            "(Ljava/lang/reflect/Field;)J");
    jmethodID unsafe_getObject =
        env->GetMethodID(unsafeCls, "getObject",
            "(Ljava/lang/Object;J)Ljava/lang/Object;");
    env->DeleteLocalRef(unsafeCls);

    // 2. 在 ServerLevel / Level 中查找实体容器字段
    jclass levelCls = nullptr;
    jobject containerField = nullptr;
    const char* candidateClasses[] = {
        "net/minecraft/server/level/ServerLevel",
        "net/minecraft/world/level/Level"
    };
    const char* containerKeywords[] = {
        "PersistentEntitySectionManager",
        "EntityStorage"
    };

    for (int ci = 0; ci < 2 && !containerField; ++ci) {
        levelCls = env->FindClass(candidateClasses[ci]);
        if (env->ExceptionCheck() || !levelCls) {
            env->ExceptionClear();
            continue;
        }
        for (int ki = 0; ki < 2 && !containerField; ++ki) {
            containerField = findFieldByTypeDebug(env, levelCls,
                containerKeywords[ki], candidateClasses[ci]);
        }
        if (!containerField) {
            env->DeleteLocalRef(levelCls);
            levelCls = nullptr;
        }
    }

    if (!containerField) {
        LOG_DEBUG("Entity container field not found in Level/ServerLevel");
        return false;
    }

    // 获取容器字段偏移量（Level -> PersistentEntitySectionManager）
    g_offset_level_entityStorage = env->CallLongMethod(g_unsafeInstance,
        unsafe_objectFieldOffset, containerField);

    // 3. 用字段类型获取实际的容器类（避免 FindClass 失败）
    jclass fieldClass = env->FindClass("java/lang/reflect/Field");
    jmethodID fieldGetType = env->GetMethodID(fieldClass, "getType", "()Ljava/lang/Class;");
    jclass containerCls = (jclass)env->CallObjectMethod(containerField, fieldGetType);
    env->DeleteLocalRef(fieldClass);
    env->DeleteLocalRef(containerField); // 不再需要

    // 保存 Level 类（ServerLevel）的全局引用
    g_levelClass = (jclass)env->NewGlobalRef(levelCls);
    env->DeleteLocalRef(levelCls);

    // 4. 在容器类中查找 EntityLookup 字段
    jobject lookupField = findFieldByTypeDebug(env, containerCls,
        "EntityLookup", "PersistentEntitySectionManager");
    if (!lookupField) {
        env->DeleteLocalRef(containerCls);
        LOG_DEBUG("EntityLookup field not found in PersistentEntitySectionManager");
        return false;
    }
    g_offset_manager_entityLookup = env->CallLongMethod(g_unsafeInstance,
        unsafe_objectFieldOffset, lookupField);

    // 5. 获取 EntityLookup 类的 Class 对象，并查找其中的实体 Map 字段
    jclass lookupCls = (jclass)env->CallObjectMethod(lookupField, fieldGetType);
    env->DeleteLocalRef(lookupField);
    // 注意：fieldGetType 之前已获取，可以用

    jobject mapFieldInLookup = findFieldByTypeDebug(env, lookupCls,
        "Map", "EntityLookup");
    if (!mapFieldInLookup) {
        env->DeleteLocalRef(lookupCls);
        env->DeleteLocalRef(containerCls);
        LOG_DEBUG("Entity Map field not found in EntityLookup");
        return false;
    }
    g_offset_entityLookup_map = env->CallLongMethod(g_unsafeInstance,
        unsafe_objectFieldOffset, mapFieldInLookup);
    env->DeleteLocalRef(mapFieldInLookup);
    env->DeleteLocalRef(lookupCls);
    env->DeleteLocalRef(containerCls);  // 都不再需要了

    // 6. 获取 Map.remove 方法（标准 Java Map 接口）
    jclass mapCls = env->FindClass("java/util/Map");
    if (mapCls) {
        g_method_map_remove = env->GetMethodID(mapCls, "remove",
            "(Ljava/lang/Object;)Ljava/lang/Object;");
        if (env->ExceptionCheck() || !g_method_map_remove) {
            env->ExceptionClear();
            LOG_DEBUG("Map.remove not found");
        }
        env->DeleteLocalRef(mapCls);
    }

    // 7. 获取 Entity.getId()（可选，保留用于日志）
    jclass entityCls = env->FindClass("net/minecraft/world/entity/Entity");
    if (entityCls && !env->ExceptionCheck()) {
        g_method_entity_getId = findMethodBySig(env, entityCls, "getId", "()I");
        if (!g_method_entity_getId)
            g_method_entity_getId = findMethodBySig(env, entityCls, "m_19847_", "()I");
        if (g_method_entity_getId)
            g_entityClass = (jclass)env->NewGlobalRef(entityCls);
        env->DeleteLocalRef(entityCls);
    } else {
        env->ExceptionClear();
    }

    g_init_done = true;
    LOG_DEBUG("JNI cache initialized (ServerLevel -> PESM -> EntityLookup -> Map)");
    return true;
}


static jobject getEntityMap(JNIEnv* env, jobject level) {
    if (!level || g_offset_level_entityStorage < 0 || g_offset_manager_entityLookup < 0 || g_offset_entityLookup_map < 0)
        return nullptr;

    jclass unsafeCls = env->GetObjectClass(g_unsafeInstance);
    jmethodID getObj = env->GetMethodID(unsafeCls, "getObject",
        "(Ljava/lang/Object;J)Ljava/lang/Object;");
    env->DeleteLocalRef(unsafeCls);

    // 第一跳：ServerLevel -> PersistentEntitySectionManager
    jobject manager = env->CallObjectMethod(g_unsafeInstance, getObj,
        level, g_offset_level_entityStorage);
    if (!manager || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    // 第二跳：PersistentEntitySectionManager -> EntityLookup
    jobject lookup = env->CallObjectMethod(g_unsafeInstance, getObj,
        manager, g_offset_manager_entityLookup);
    env->DeleteLocalRef(manager);
    if (!lookup || env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }

    // 第三跳：EntityLookup -> 实体 Map（Int2ObjectLinkedOpenHashMap 等）
    jobject map = env->CallObjectMethod(g_unsafeInstance, getObj,
        lookup, g_offset_entityLookup_map);
    env->DeleteLocalRef(lookup);
    return map;  // 由调用者释放局部引用
}

extern "C" {

// ===================================================================
// 导出函数：批量屠杀
// ===================================================================
JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeMassacre
(JNIEnv* env, jclass /*clazz*/, jobject level, jintArray skipIdsArray) {
    if (!level) {
        LOG_DEBUG("nativeMassacre: level is null");
        return;
    }
    if (!initJNICache(env) || !g_method_map_remove) {
        LOG_DEBUG("nativeMassacre: initialization incomplete");
        return;
    }

    // 读取跳过的 ID 集合
    std::set<int> skipIds;
    if (skipIdsArray) {
        jsize len = env->GetArrayLength(skipIdsArray);
        jint* ids = env->GetIntArrayElements(skipIdsArray, nullptr);
        for (int i = 0; i < len; ++i) skipIds.insert(ids[i]);
        env->ReleaseIntArrayElements(skipIdsArray, ids, JNI_ABORT);
    }

    bypassFlag.store(true);
    LOG_DEBUG("Massacre (Unsafe) started, skip set size = %zu", skipIds.size());

    jobject map = getEntityMap(env, level);
    if (!map) {
        LOG_DEBUG("entityMap is null");
        bypassFlag.store(false);
        return;
    }

    // 获取 keySet
    jclass mapClass = env->GetObjectClass(map);
    jmethodID keySetMid = env->GetMethodID(mapClass, "keySet", "()Ljava/util/Set;");
    if (!keySetMid) {
        env->DeleteLocalRef(mapClass);
        env->DeleteLocalRef(map);
        bypassFlag.store(false);
        return;
    }
    jobject keySet = env->CallObjectMethod(map, keySetMid);
    env->DeleteLocalRef(mapClass);
    if (!keySet) {
        env->DeleteLocalRef(map);
        bypassFlag.store(false);
        return;
    }

    jclass setClass = env->GetObjectClass(keySet);
    jmethodID toArrayMid = env->GetMethodID(setClass, "toArray", "()[Ljava/lang/Object;");
    if (!toArrayMid) {
        env->DeleteLocalRef(setClass);
        env->DeleteLocalRef(keySet);
        env->DeleteLocalRef(map);
        bypassFlag.store(false);
        return;
    }
    jobjectArray keysArray = (jobjectArray)env->CallObjectMethod(keySet, toArrayMid);
    env->DeleteLocalRef(setClass);
    env->DeleteLocalRef(keySet);
    if (!keysArray) {
        env->DeleteLocalRef(map);
        bypassFlag.store(false);
        return;
    }

    jsize count = env->GetArrayLength(keysArray);
    LOG_DEBUG("Total entities in map: %d", count);
    int killed = 0;

    jclass intClass = env->FindClass("java/lang/Integer");
    jmethodID intValueMid = env->GetMethodID(intClass, "intValue", "()I");

    for (jsize i = 0; i < count; ++i) {
        jobject keyObj = env->GetObjectArrayElement(keysArray, i);
        if (!keyObj) continue;
        jint id = env->CallIntMethod(keyObj, intValueMid);
        if (skipIds.find(id) != skipIds.end()) {
            env->DeleteLocalRef(keyObj);
            continue;
        }
        // 直接调用 Map.remove(Object) – 注意这里即使底层是 fastutil map 也会走接口调用
        env->CallObjectMethod(map, g_method_map_remove, keyObj);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOG_DEBUG("Failed to remove entity id %d", id);
        } else {
            ++killed;
        }
        env->DeleteLocalRef(keyObj);
    }

    env->DeleteLocalRef(intClass);
    env->DeleteLocalRef(keysArray);
    env->DeleteLocalRef(map);
    bypassFlag.store(false);
    LOG_DEBUG("Massacre finished, removed %d entities", killed);
}

// ===================================================================
// 导出函数：单体删除
// ===================================================================
JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeKillEntity
(JNIEnv* env, jclass /*clazz*/, jobject level, jint entityId) {
    if (!level) {
        LOG_DEBUG("nativeKillEntity: level is null");
        return;
    }
    if (!initJNICache(env) || !g_method_map_remove) {
        LOG_DEBUG("nativeKillEntity: initialization incomplete");
        return;
    }

    jobject map = getEntityMap(env, level);
    if (!map) {
        LOG_DEBUG("entityMap is null");
        return;
    }

    // 构造 Integer key
    jclass intClass = env->FindClass("java/lang/Integer");
    jmethodID valueOfMid = env->GetStaticMethodID(intClass, "valueOf",
        "(I)Ljava/lang/Integer;");
    jobject keyObj = env->CallStaticObjectMethod(intClass, valueOfMid, entityId);
    env->DeleteLocalRef(intClass);
    if (!keyObj) {
        env->DeleteLocalRef(map);
        return;
    }

    env->CallObjectMethod(map, g_method_map_remove, keyObj);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOG_DEBUG("Failed to remove entity %d", entityId);
    } else {
        LOG_DEBUG("Killed entity %d", entityId);
    }

    env->DeleteLocalRef(keyObj);
    env->DeleteLocalRef(map);
}

// ===================================================================
// 其余函数（保留接口，可根据需要同样重构）
// ===================================================================
JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeDisableThreats
(JNIEnv* env, jclass) {
    // 原逻辑可保留，这里仅占位
    LOG_DEBUG("nativeDisableThreats called (unimplemented)");
}

JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeTickGuard
(JNIEnv* env, jclass, jobject player) {
    // 保留原逻辑或置空
    LOG_DEBUG("nativeTickGuard called (unimplemented)");
}



}