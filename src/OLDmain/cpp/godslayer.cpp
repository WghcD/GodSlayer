#include "godslayer.h"
#include <windows.h>
#include <debugapi.h>
#include <cstring>
#include <vector>
#include <set>

#define LOG_DEBUG(fmt, ...) { \
    char buf[512]; \
    sprintf_s(buf, sizeof(buf), "[GodSlayer Native] " fmt "\n", ##__VA_ARGS__); \
    OutputDebugStringA(buf); \
}

// -------- 全局变量定义 --------
jclass levelClass = nullptr;
jmethodID level_getAllEntities = nullptr;

jclass listClass = nullptr;
jmethodID list_iterator = nullptr;
jclass iteratorClass = nullptr;
jmethodID iterator_hasNext = nullptr;
jmethodID iterator_next = nullptr;

jclass entityClass = nullptr;
jmethodID entity_getId = nullptr;
jmethodID entity_remove = nullptr;
jobject removalReasonDiscarded = nullptr;

jclass livingEntityClass = nullptr;
jmethodID livingEntity_hurt = nullptr;
jobject damageSourceGeneric = nullptr;
jfieldID healthFid = nullptr;
jmethodID getMaxHealthMid = nullptr;
jfieldID deathTimeFid = nullptr;

std::atomic<bool> bypassFlag{false};
bool g_init_done = false;

// -------- 辅助函数：安全查找 JDK 类 --------
static jclass safeFindJDKClass(JNIEnv* env, const char* name) {
    jclass cls = env->FindClass(name);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOG_DEBUG("Failed to find JDK class: %s", name);
        return nullptr;
    }
    return cls;
}

// -------- 辅助函数：安全获取方法 ID，尝试多个候选名 --------
static jmethodID safeGetMethodID(JNIEnv* env, jclass clazz, const char** names, int nameCount, const char* sig) {
    for (int i = 0; i < nameCount; ++i) {
        jmethodID mid = env->GetMethodID(clazz, names[i], sig);
        if (!env->ExceptionCheck() && mid != nullptr) {
            LOG_DEBUG("Found method %s with sig %s", names[i], sig);
            return mid;
        }
        env->ExceptionClear(); // 清除异常，继续尝试
    }
    LOG_DEBUG("Failed to find method with any candidate names for sig %s", sig);
    return nullptr;
}

// -------- 辅助函数：安全获取实例字段 ID --------
static jfieldID safeGetFieldID(JNIEnv* env, jclass clazz, const char** names, int nameCount, const char* sig) {
    for (int i = 0; i < nameCount; ++i) {
        jfieldID fid = env->GetFieldID(clazz, names[i], sig);
        if (!env->ExceptionCheck() && fid != nullptr) {
            LOG_DEBUG("Found field %s with sig %s", names[i], sig);
            return fid;
        }
        env->ExceptionClear();
    }
    LOG_DEBUG("Failed to find field with any candidate names for sig %s", sig);
    return nullptr;
}

// -------- 辅助函数：安全获取静态字段 ID --------
static jfieldID safeGetStaticFieldID(JNIEnv* env, jclass clazz, const char** names, int nameCount, const char* sig) {
    for (int i = 0; i < nameCount; ++i) {
        jfieldID fid = env->GetStaticFieldID(clazz, names[i], sig);
        if (!env->ExceptionCheck() && fid != nullptr) {
            LOG_DEBUG("Found static field %s with sig %s", names[i], sig);
            return fid;
        }
        env->ExceptionClear();
    }
    LOG_DEBUG("Failed to find static field with any candidate names for sig %s", sig);
    return nullptr;
}

// -------- 初始化 JNI 缓存（使用多种候选名） --------
bool initJNICache(JNIEnv* env, jobject level) {
    if (g_init_done) return true;
    if (level == nullptr) {
        LOG_DEBUG("initJNICache: level is null");
        return false;
    }

    env->ExceptionClear();

    // 1. 获取 Level 类，并打印其名称以供调试
    jclass localLevelClass = env->GetObjectClass(level);
    if (localLevelClass == nullptr) {
        LOG_DEBUG("initJNICache: failed to get Level class");
        return false;
    }
    // 获取类名
    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass != nullptr) {
        jmethodID getClassMid = env->GetMethodID(objectClass, "getClass", "()Ljava/lang/Class;");
        if (getClassMid != nullptr) {
            jobject clsObj = env->CallObjectMethod(level, getClassMid);
            if (clsObj != nullptr) {
                jclass classClass = env->FindClass("java/lang/Class");
                jmethodID getNameMid = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
                if (getNameMid != nullptr) {
                    jstring nameStr = (jstring)env->CallObjectMethod(clsObj, getNameMid);
                    const char* nameC = env->GetStringUTFChars(nameStr, nullptr);
                    LOG_DEBUG("Level object class name: %s", nameC);
                    env->ReleaseStringUTFChars(nameStr, nameC);
                    env->DeleteLocalRef(nameStr);
                }
                env->DeleteLocalRef(clsObj);
                env->DeleteLocalRef(classClass);
            }
        }
        env->DeleteLocalRef(objectClass);
    }

    // 2. 获取 Level.getAllEntities() 方法 (候选名列表)
    const char* getAllNames[] = {
        "getAllEntities",
        "getEntities",
        "getEntityList",
        "m_156811_",
        "func_156811_",
        "method_156811_"
    };
    jmethodID localLevel_getAllEntities = safeGetMethodID(env, localLevelClass, getAllNames, 6, "()Ljava/util/List;");
    if (localLevel_getAllEntities == nullptr) {
        LOG_DEBUG("initJNICache: getAllEntities method not found");
        return false;
    }

    // 3. JDK 类 (List, Iterator)
    jclass localListClass = safeFindJDKClass(env, "java/util/List");
    if (localListClass == nullptr) return false;
    jmethodID localList_iterator = env->GetMethodID(localListClass, "iterator", "()Ljava/util/Iterator;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG_DEBUG("GetMethodID iterator failed"); return false; }

    jclass localIteratorClass = safeFindJDKClass(env, "java/util/Iterator");
    if (localIteratorClass == nullptr) return false;
    jmethodID localIterator_hasNext = env->GetMethodID(localIteratorClass, "hasNext", "()Z");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG_DEBUG("GetMethodID hasNext failed"); return false; }
    jmethodID localIterator_next = env->GetMethodID(localIteratorClass, "next", "()Ljava/lang/Object;");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG_DEBUG("GetMethodID next failed"); return false; }

    // 4. Entity 类
    jclass localEntityClass = env->FindClass("net/minecraft/world/entity/Entity");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG_DEBUG("FindClass Entity failed"); return false; }

    // 4a. Entity.getId()
    const char* getIdNames[] = { "getId", "m_19847_", "func_19847_", "method_19847_" };
    jmethodID localEntity_getId = safeGetMethodID(env, localEntityClass, getIdNames, 4, "()I");
    if (localEntity_getId == nullptr) {
        LOG_DEBUG("initJNICache: getId method not found");
        return false;
    }

    // 4b. Entity.remove(RemovalReason)
    const char* removeNames[] = { "remove", "m_146874_", "func_146874_", "method_146874_" };
    jmethodID localEntity_remove = safeGetMethodID(env, localEntityClass, removeNames, 4, "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V");

    // 4c. Entity.RemovalReason.DISCARDED
    jobject localRemovalReasonDiscarded = nullptr;
    if (localEntity_remove != nullptr) {
        jclass removalReasonClass = env->FindClass("net/minecraft/world/entity/Entity$RemovalReason");
        if (!env->ExceptionCheck() && removalReasonClass != nullptr) {
            const char* discardedNames[] = { "DISCARDED", "f_219310_", "func_219310_" };
            jfieldID discardedFid = safeGetStaticFieldID(env, removalReasonClass, discardedNames, 3, "Lnet/minecraft/world/entity/Entity$RemovalReason;");
            if (discardedFid != nullptr) {
                localRemovalReasonDiscarded = env->GetStaticObjectField(removalReasonClass, discardedFid);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    localRemovalReasonDiscarded = nullptr;
                }
            }
            env->DeleteLocalRef(removalReasonClass);
        } else {
            env->ExceptionClear();
        }
    }

    // 5. LivingEntity 类
    jclass localLivingEntityClass = env->FindClass("net/minecraft/world/entity/LivingEntity");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG_DEBUG("FindClass LivingEntity failed"); return false; }

    // 5a. health field (实例字段)
    const char* healthNames[] = { "health", "f_20922_", "func_20922_" };
    jfieldID localHealthFid = safeGetFieldID(env, localLivingEntityClass, healthNames, 3, "F");
    if (localHealthFid == nullptr) {
        LOG_DEBUG("initJNICache: health field not found");
        return false;
    }

    // getMaxHealth
    const char* maxHealthNames[] = { "getMaxHealth", "m_21233_", "func_21233_", "method_21233_" };
    jmethodID localGetMaxHealthMid = safeGetMethodID(env, localLivingEntityClass, maxHealthNames, 4, "()F");
    if (localGetMaxHealthMid == nullptr) {
        LOG_DEBUG("initJNICache: getMaxHealth method not found");
        return false;
    }

    // deathTime field
    const char* deathTimeNames[] = { "deathTime", "f_20919_", "func_20919_" };
    jfieldID localDeathTimeFid = safeGetFieldID(env, localLivingEntityClass, deathTimeNames, 3, "I");
    if (localDeathTimeFid == nullptr) {
        LOG_DEBUG("initJNICache: deathTime field not found");
        return false;
    }

    // hurt
    const char* hurtNames[] = { "hurt", "m_6473_", "func_6473_", "method_6473_" };
    jmethodID localLivingEntity_hurt = safeGetMethodID(env, localLivingEntityClass, hurtNames, 4, "(Lnet/minecraft/world/damagesource/DamageSource;F)Z");
    if (localLivingEntity_hurt == nullptr) {
        LOG_DEBUG("initJNICache: hurt method not found");
        return false;
    }

    // DamageSource.GENERIC
    jclass damageSourceClass = env->FindClass("net/minecraft/world/damagesource/DamageSource");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG_DEBUG("FindClass DamageSource failed"); return false; }
    const char* genericNames[] = { "GENERIC", "f_19382_", "func_19382_" };
    jfieldID genericFid = safeGetStaticFieldID(env, damageSourceClass, genericNames, 3, "Lnet/minecraft/world/damagesource/DamageSource;");
    if (genericFid == nullptr) {
        LOG_DEBUG("initJNICache: GENERIC field not found");
        return false;
    }
    jobject localDamageSourceGeneric = env->GetStaticObjectField(damageSourceClass, genericFid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG_DEBUG("GetStaticObjectField GENERIC failed"); return false; }

    // -------- 存储全局引用 --------
    levelClass = (jclass)env->NewGlobalRef(localLevelClass);
    level_getAllEntities = localLevel_getAllEntities;

    listClass = (jclass)env->NewGlobalRef(localListClass);
    list_iterator = localList_iterator;
    iteratorClass = (jclass)env->NewGlobalRef(localIteratorClass);
    iterator_hasNext = localIterator_hasNext;
    iterator_next = localIterator_next;

    entityClass = (jclass)env->NewGlobalRef(localEntityClass);
    entity_getId = localEntity_getId;
    if (localEntity_remove != nullptr) {
        entity_remove = localEntity_remove;
        removalReasonDiscarded = env->NewGlobalRef(localRemovalReasonDiscarded);
    } else {
        entity_remove = nullptr;
        removalReasonDiscarded = nullptr;
    }

    livingEntityClass = (jclass)env->NewGlobalRef(localLivingEntityClass);
    livingEntity_hurt = localLivingEntity_hurt;
    damageSourceGeneric = env->NewGlobalRef(localDamageSourceGeneric);
    healthFid = localHealthFid;
    getMaxHealthMid = localGetMaxHealthMid;
    deathTimeFid = localDeathTimeFid;

    g_init_done = true;
    LOG_DEBUG("JNI cache initialized successfully.");
    return true;
}


extern "C" {
JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeTickGuard
(JNIEnv* env, jclass clazz, jobject player) {
    if (bypassFlag.load()) return;
    if (player == nullptr) return;
    if (!g_init_done) {
        LOG_DEBUG("nativeTickGuard: cache not initialized, please call massacre first");
        return;
    }
    jfloat maxHealth = env->CallFloatMethod(player, getMaxHealthMid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    env->SetFloatField(player, healthFid, maxHealth);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    env->SetIntField(player, deathTimeFid, 0);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeMassacre
(JNIEnv* env, jclass clazz, jobject level, jintArray skipIdsArray) {
    if (level == nullptr) {
        LOG_DEBUG("nativeMassacre: level is null");
        return;
    }
    if (!initJNICache(env, level)) {
        LOG_DEBUG("nativeMassacre: cache init failed");
        return;
    }

    std::set<int> skipIds;
    if (skipIdsArray != nullptr) {
        jsize len = env->GetArrayLength(skipIdsArray);
        jint* ids = env->GetIntArrayElements(skipIdsArray, nullptr);
        for (int i = 0; i < len; ++i) skipIds.insert(ids[i]);
        env->ReleaseIntArrayElements(skipIdsArray, ids, JNI_ABORT);
    }

    bypassFlag.store(true);
    LOG_DEBUG("Massacre started, skipping %zu entities", skipIds.size());

    jobject entityList = env->CallObjectMethod(level, level_getAllEntities);
    if (entityList == nullptr) {
        LOG_DEBUG("entityList is null");
        bypassFlag.store(false);
        return;
    }

    jobject iter = env->CallObjectMethod(entityList, list_iterator);
    if (iter == nullptr) {
        LOG_DEBUG("iterator is null");
        env->DeleteLocalRef(entityList);
        bypassFlag.store(false);
        return;
    }

    int killed = 0;
    while (env->CallBooleanMethod(iter, iterator_hasNext)) {
        jobject entity = env->CallObjectMethod(iter, iterator_next);
        if (entity == nullptr) continue;

        jint id = env->CallIntMethod(entity, entity_getId);
        if (skipIds.find(id) != skipIds.end()) {
            env->DeleteLocalRef(entity);
            continue;
        }

        if (env->IsInstanceOf(entity, livingEntityClass)) {
            env->CallBooleanMethod(entity, livingEntity_hurt, damageSourceGeneric, 3.4028235e38f);
            killed++;
        } else {
            if (entity_remove != nullptr && removalReasonDiscarded != nullptr) {
                env->CallVoidMethod(entity, entity_remove, removalReasonDiscarded);
                killed++;
            }
        }
        env->DeleteLocalRef(entity);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    env->DeleteLocalRef(iter);
    env->DeleteLocalRef(entityList);
    bypassFlag.store(false);
    LOG_DEBUG("Massacre finished, killed %d entities", killed);
}

JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeKillEntity
(JNIEnv* env, jclass clazz, jobject level, jint entityId) {
    if (level == nullptr) {
        LOG_DEBUG("nativeKillEntity: level is null");
        return;
    }
    if (!initJNICache(env, level)) {
        LOG_DEBUG("nativeKillEntity: cache init failed");
        return;
    }

    jobject entityList = env->CallObjectMethod(level, level_getAllEntities);
    if (entityList == nullptr) {
        LOG_DEBUG("entityList is null");
        return;
    }

    jobject iter = env->CallObjectMethod(entityList, list_iterator);
    if (iter == nullptr) {
        env->DeleteLocalRef(entityList);
        LOG_DEBUG("iterator is null");
        return;
    }

    jobject target = nullptr;
    while (env->CallBooleanMethod(iter, iterator_hasNext)) {
        jobject entity = env->CallObjectMethod(iter, iterator_next);
        if (entity == nullptr) continue;
        jint id = env->CallIntMethod(entity, entity_getId);
        if (id == entityId) {
            target = entity;
            break;
        }
        env->DeleteLocalRef(entity);
    }

    if (target != nullptr) {
        if (env->IsInstanceOf(target, livingEntityClass)) {
            env->CallBooleanMethod(target, livingEntity_hurt, damageSourceGeneric, 3.4028235e38f);
            env->SetFloatField(target, healthFid, 0.0f);
            env->SetIntField(target, deathTimeFid, 20);
        } else if (entity_remove != nullptr && removalReasonDiscarded != nullptr) {
            env->CallVoidMethod(target, entity_remove, removalReasonDiscarded);
        }
        env->DeleteLocalRef(target);
        LOG_DEBUG("Killed entity %d", entityId);
    } else {
        LOG_DEBUG("Entity %d not found", entityId);
    }

    env->DeleteLocalRef(iter);
    env->DeleteLocalRef(entityList);
}

JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeDisableThreats
(JNIEnv* env, jclass clazz) {
    LOG_DEBUG("nativeDisableThreats called");
    auto safeFindClass = [&](const char* name) -> jclass {
        jclass cls = env->FindClass(name);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
        return cls;
    };

    const char* dangerClasses[] = {
        "com/wzz/dangerlib/ClassUtil",
        "com/wzz/dangerlib/api/IFMLDangerLoadingPlugin"
    };
    for (const char* className : dangerClasses) {
        jclass cls = safeFindClass(className);
        if (cls != nullptr) {
            jfieldID unsafeField = env->GetStaticFieldID(cls, "UNSAFE", "Lsun/misc/Unsafe;");
            if (!env->ExceptionCheck() && unsafeField != nullptr) {
                env->SetStaticObjectField(cls, unsafeField, nullptr);
                LOG_DEBUG("Disabled DangerAPI in %s", className);
            }
            env->ExceptionClear();
            env->DeleteLocalRef(cls);
        }
    }

    const char* mikuClasses[] = {
        "miku/MikuLib",
        "miku/MikuKillCommand"
    };
    for (const char* className : mikuClasses) {
        jclass cls = safeFindClass(className);
        if (cls != nullptr) {
            jfieldID enabledField = env->GetStaticFieldID(cls, "ENABLED", "Z");
            if (!env->ExceptionCheck() && enabledField != nullptr) {
                env->SetStaticBooleanField(cls, enabledField, JNI_FALSE);
                LOG_DEBUG("Disabled MikuLib in %s", className);
            }
            env->ExceptionClear();
            env->DeleteLocalRef(cls);
        }
    }
}
}