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
// 全局缓存
// ===================================================================
static jobject     g_unsafeInstance = nullptr;
static jclass      g_unsafeClass = nullptr;
static jmethodID   g_unsafe_objectFieldOffset = nullptr;
static jmethodID   g_unsafe_getObject = nullptr;
static jmethodID   g_unsafe_putBoolean = nullptr;

static jclass    g_class_EntitySection = nullptr;
static jmethodID g_method_EntitySection_getEntities = nullptr; // ()Ljava/lang/Iterable;

static jlong       g_offset_level_to_manager = -1;
static jlong       g_offset_manager_to_lookup = -1;
static jlong       g_offset_lookup_to_entityMap = -1;
static jmethodID   g_method_map_get = nullptr;
static jmethodID   g_method_map_remove = nullptr;
static jmethodID   g_method_collection_remove = nullptr;

static jlong g_offset_level_to_players = -1;

// 实体 removed 字段的 JNI fieldID
static jfieldID g_field_entity_removed = nullptr;


static jfieldID  g_field_entitySection_entities = nullptr;  // EntitySection.entities 字段 ID (保留但不使用)

// 新增缓存

static jmethodID g_mid_list_iterator = nullptr;        // List.iterator()
static jmethodID g_mid_iterator_hasNext = nullptr;     // Iterator.hasNext()
static jmethodID g_mid_iterator_next = nullptr;        // Iterator.next()
static jmethodID g_mid_iterator_remove = nullptr;      // Iterator.remove()
static jmethodID g_mid_entity_getId = nullptr;         // Entity.getId()
static jmethodID g_mid_set_add = nullptr;               // Set.add() (备用)

static jlong g_offset_level_entityList = -1;   // ServerLevel 中 List<Entity> 的 Unsafe 偏移

// 额外容器路径
struct RemovalPath {
    std::vector<jlong> offsets;
    bool isMap;
};
static std::vector<RemovalPath> g_removalPaths;

// ChunkMap / 网络清除
static jlong g_offset_level_to_chunkSource = -1;
static jlong g_offset_chunkSource_to_chunkMap = -1;
static jlong g_offset_chunkMap_to_entityMap = -1;
static jlong g_offset_entityTracker_to_players = -1;

static jclass   g_class_removeEntitiesPacket = nullptr;
static jmethodID g_ctor_removeEntitiesPacket = nullptr;
static jmethodID g_method_connection_send = nullptr;
static jfieldID g_field_serverPlayer_connection = nullptr; // 缓存连接字段ID

// Entity 相关
static jmethodID g_method_entity_remove = nullptr;
static jobject   g_killedRemovalReason = nullptr;
static jlong     g_offset_entity_removed = -1;

// EntitySection 相关
static jlong g_offset_pesm_to_sectionStorage = -1;
static jlong g_offset_sectionStorage_to_sectionsMap = -1;
static jlong g_offset_entitySection_to_entities = -1;

// EntityIndex
static jlong g_offset_level_to_entityIndex = -1;
static jlong g_offset_entityIndex_to_map = -1;

static bool g_init_done = false;
std::atomic<bool> bypassFlag{ false };

// 安全辅助：检查对象是否有效（不引发异常）
inline bool isObjectValid(JNIEnv* env, jobject obj) {
    if (!obj) return false;
    jclass cls = env->GetObjectClass(obj);
    if (env->ExceptionCheck() || !cls) {
        env->ExceptionClear();
        return false;
    }
    env->DeleteLocalRef(cls);
    return true;
}

// 安全调用 helper（带日志）
static bool safeCallVoidMethod(JNIEnv* env, jobject obj, jmethodID mid) {
    if (!mid || !isObjectValid(env, obj)) return false;
    env->CallVoidMethod(obj, mid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return true;
}

static bool safeCallBooleanMethod(JNIEnv* env, jobject obj, jmethodID mid) {
    if (!mid || !isObjectValid(env, obj)) return false;
    jboolean res = env->CallBooleanMethod(obj, mid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return res != 0;
}

static jobject safeCallObjectMethod(JNIEnv* env, jobject obj, jmethodID mid) {
    if (!mid || !isObjectValid(env, obj)) return nullptr;
    jobject res = env->CallObjectMethod(obj, mid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); if(res) env->DeleteLocalRef(res); return nullptr; }
    return res;
}

static jobject getUnsafe(JNIEnv* env) {
    jclass unsafeCls = env->FindClass("sun/misc/Unsafe");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    jfieldID fid = env->GetStaticFieldID(unsafeCls, "theUnsafe", "Lsun/misc/Unsafe;");
    if (env->ExceptionCheck() || !fid) {
        env->ExceptionClear();
        env->DeleteLocalRef(unsafeCls);
        return nullptr;
    }
    jobject unsafeObj = env->GetStaticObjectField(unsafeCls, fid);
    if (env->ExceptionCheck() || !unsafeObj) {
        env->ExceptionClear();
        env->DeleteLocalRef(unsafeCls);
        return nullptr;
    }
    jobject globalUnsafe = env->NewGlobalRef(unsafeObj);
    env->DeleteLocalRef(unsafeObj);
    env->DeleteLocalRef(unsafeCls);
    return globalUnsafe;
}

// ===================================================================
// 工具：根据字段名获取对象字段偏移（通过反射+Unsafe）
// ===================================================================
static jlong getFieldOffsetByName(JNIEnv* env, jclass clazz, const char* fieldName) {
    if (!clazz || !fieldName) return -1;
    jclass currentClass = clazz;
    while (currentClass) {
        jclass classClass = env->FindClass("java/lang/Class");
        if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
        jmethodID getDeclaredField = env->GetMethodID(classClass, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
        if (env->ExceptionCheck() || !getDeclaredField) { env->ExceptionClear(); env->DeleteLocalRef(classClass); return -1; }
        jstring jname = env->NewStringUTF(fieldName);
        jobject fieldObj = env->CallObjectMethod(currentClass, getDeclaredField, jname);
        env->DeleteLocalRef(jname);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            jmethodID getSuper = env->GetMethodID(classClass, "getSuperclass", "()Ljava/lang/Class;");
            if (getSuper) {
                currentClass = (jclass)env->CallObjectMethod(currentClass, getSuper);
                if (env->ExceptionCheck()) { env->ExceptionClear(); currentClass = nullptr; }
            } else {
                currentClass = nullptr;
            }
            env->DeleteLocalRef(classClass);
            continue;
        }
        // 获取偏移
        if (!g_unsafeInstance || !g_unsafe_objectFieldOffset) {
            env->DeleteLocalRef(fieldObj);
            env->DeleteLocalRef(classClass);
            return -1;
        }
        jlong offset = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, fieldObj);
        env->DeleteLocalRef(fieldObj);
        env->DeleteLocalRef(classClass);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return -1;
        }
        return offset;
    }
    return -1;
}

// ===================================================================
// 工具：根据类型关键词查找字段（遍历所有字段，用于备用）
// ===================================================================
static jobject findFieldByTypeDebug(JNIEnv* env, jclass clazz,
                                    const char* typeKeyword,
                                    const char* contextLabel)
{
    jclass cls = clazz;
    while (cls) {
        jclass classClass = env->FindClass("java/lang/Class");
        if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
        jmethodID clsGetName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
        if (env->ExceptionCheck() || !clsGetName) { env->ExceptionClear(); env->DeleteLocalRef(classClass); return nullptr; }
        jstring clsNameStr = (jstring)env->CallObjectMethod(cls, clsGetName);
        if (env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(classClass); return nullptr; }
        const char* clsName = env->GetStringUTFChars(clsNameStr, nullptr);

        jmethodID getDeclaredFields = env->GetMethodID(classClass,
            "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
        if (!getDeclaredFields || env->ExceptionCheck()) {
            env->ExceptionClear();
            env->ReleaseStringUTFChars(clsNameStr, clsName);
            env->DeleteLocalRef(clsNameStr);
            break;
        }
        jobjectArray fields = (jobjectArray)env->CallObjectMethod(cls, getDeclaredFields);
        if (env->ExceptionCheck() || !fields) {
            env->ExceptionClear();
            env->ReleaseStringUTFChars(clsNameStr, clsName);
            env->DeleteLocalRef(clsNameStr);
            jmethodID getSuper = env->GetMethodID(classClass, "getSuperclass", "()Ljava/lang/Class;");
            cls = getSuper ? (jclass)env->CallObjectMethod(cls, getSuper) : nullptr;
            if (env->ExceptionCheck()) { env->ExceptionClear(); cls = nullptr; }
            env->DeleteLocalRef(classClass);
            continue;
        }

        jsize len = env->GetArrayLength(fields);
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        if (env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(fields); env->ReleaseStringUTFChars(clsNameStr, clsName); env->DeleteLocalRef(clsNameStr); env->DeleteLocalRef(classClass); return nullptr; }
        jmethodID getType = env->GetMethodID(fieldClass, "getType", "()Ljava/lang/Class;");
        jmethodID getName = env->GetMethodID(fieldClass, "getName", "()Ljava/lang/String;");
        if (env->ExceptionCheck() || !getType || !getName) {
            env->ExceptionClear();
            env->DeleteLocalRef(fieldClass);
            env->DeleteLocalRef(fields);
            env->ReleaseStringUTFChars(clsNameStr, clsName);
            env->DeleteLocalRef(clsNameStr);
            env->DeleteLocalRef(classClass);
            return nullptr;
        }

        for (jsize i = 0; i < len; ++i) {
            jobject fieldObj = env->GetObjectArrayElement(fields, i);
            jclass type = (jclass)env->CallObjectMethod(fieldObj, getType);
            jstring typeNameStr = (jstring)env->CallObjectMethod(type, clsGetName);
            const char* typeName = env->GetStringUTFChars(typeNameStr, nullptr);

            jstring fieldNameStr = (jstring)env->CallObjectMethod(fieldObj, getName);
            const char* fieldName = env->GetStringUTFChars(fieldNameStr, nullptr);

            char debugBuf[512];
            sprintf_s(debugBuf, sizeof(debugBuf),
                "[GodSlayer Debug] %s -> class %s, field %s : type %s",
                contextLabel, clsName, fieldName, typeName);
            OutputDebugStringA(debugBuf);

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
                return fieldObj;
            }
            env->DeleteLocalRef(fieldObj);
        }

        env->DeleteLocalRef(fields);
        env->DeleteLocalRef(fieldClass);
        env->ReleaseStringUTFChars(clsNameStr, clsName);
        env->DeleteLocalRef(clsNameStr);

        jmethodID getSuper = env->GetMethodID(classClass, "getSuperclass", "()Ljava/lang/Class;");
        cls = getSuper ? (jclass)env->CallObjectMethod(cls, getSuper) : nullptr;
        if (env->ExceptionCheck()) { env->ExceptionClear(); cls = nullptr; }
        env->DeleteLocalRef(classClass);
    }
    return nullptr;
}

// ===================================================================
// 工具：通过反射查找方法（名称 + 参数类型子串）
// ===================================================================
static jmethodID findMethodByReflection(JNIEnv* env, jclass clazz, const char* methodName, const char* paramTypeSubstr) {
    jclass classClass = env->FindClass("java/lang/Class");
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    jmethodID getMethods = env->GetMethodID(classClass, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    if (env->ExceptionCheck() || !getMethods) { env->ExceptionClear(); env->DeleteLocalRef(classClass); return nullptr; }
    jobjectArray methods = (jobjectArray)env->CallObjectMethod(clazz, getMethods);
    if (env->ExceptionCheck() || !methods) { env->ExceptionClear(); env->DeleteLocalRef(classClass); return nullptr; }

    jclass methodClass = env->FindClass("java/lang/reflect/Method");
    if (env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(methods); env->DeleteLocalRef(classClass); return nullptr; }
    jmethodID mGetName = env->GetMethodID(methodClass, "getName", "()Ljava/lang/String;");
    jmethodID mGetParamTypes = env->GetMethodID(methodClass, "getParameterTypes", "()[Ljava/lang/Class;");
    jsize len = env->GetArrayLength(methods);

    for (jsize i = 0; i < len; ++i) {
        jobject m = env->GetObjectArrayElement(methods, i);
        jstring jname = (jstring)env->CallObjectMethod(m, mGetName);
        const char* name = env->GetStringUTFChars(jname, nullptr);
        if (strcmp(name, methodName) == 0) {
            jobjectArray paramTypes = (jobjectArray)env->CallObjectMethod(m, mGetParamTypes);
            if (paramTypes) {
                jsize pCount = env->GetArrayLength(paramTypes);
                if (pCount == 1 && paramTypeSubstr) {
                    jclass pCls = (jclass)env->GetObjectArrayElement(paramTypes, 0);
                    jstring pName = (jstring)env->CallObjectMethod(pCls, env->GetMethodID(classClass, "getName", "()Ljava/lang/String;"));
                    const char* pStr = env->GetStringUTFChars(pName, nullptr);
                    bool match = (strstr(pStr, paramTypeSubstr) != nullptr);
                    env->ReleaseStringUTFChars(pName, pStr);
                    env->DeleteLocalRef(pName);
                    env->DeleteLocalRef(pCls);
                    if (match) {
                        jmethodID mid = env->FromReflectedMethod(m);
                        env->ReleaseStringUTFChars(jname, name);
                        env->DeleteLocalRef(jname);
                        env->DeleteLocalRef(m);
                        env->DeleteLocalRef(methods);
                        env->DeleteLocalRef(methodClass);
                        env->DeleteLocalRef(classClass);
                        return mid;
                    }
                }
            }
        }
        env->ReleaseStringUTFChars(jname, name);
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(m);
    }
    env->DeleteLocalRef(methods);
    env->DeleteLocalRef(methodClass);
    env->DeleteLocalRef(classClass);
    return nullptr;
}

// ===================================================================
// 获取主实体映射
// ===================================================================
static jobject getEntityMap(JNIEnv* env, jobject level) {
    if (!level || g_offset_level_to_manager < 0 || g_offset_manager_to_lookup < 0 || g_offset_lookup_to_entityMap < 0)
        return nullptr;
    if (!g_unsafeInstance || !g_unsafe_getObject) return nullptr;
    jobject manager = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, level, g_offset_level_to_manager);
    if (!manager || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    jobject lookup = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, manager, g_offset_manager_to_lookup);
    env->DeleteLocalRef(manager);
    if (!lookup || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    jobject map = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, lookup, g_offset_lookup_to_entityMap);
    env->DeleteLocalRef(lookup);
    if (env->ExceptionCheck()) { env->ExceptionClear(); if(map) env->DeleteLocalRef(map); return nullptr; }
    return map;
}

// ===================================================================
// 初始化缓存
// ===================================================================
bool initJNICache(JNIEnv* env) {
    if (g_init_done) return true;

    LOG_DEBUG("Start to initJNICache");

    // 获取 Unsafe
    g_unsafeInstance = getUnsafe(env);
    if (!g_unsafeInstance) {
        LOG_DEBUG("FATAL: Unsafe not available");
        return false;
    }
    g_unsafeClass = (jclass)env->NewGlobalRef(env->GetObjectClass(g_unsafeInstance));
    g_unsafe_objectFieldOffset = env->GetMethodID(g_unsafeClass, "objectFieldOffset", "(Ljava/lang/reflect/Field;)J");
    g_unsafe_getObject = env->GetMethodID(g_unsafeClass, "getObject", "(Ljava/lang/Object;J)Ljava/lang/Object;");
    g_unsafe_putBoolean = env->GetMethodID(g_unsafeClass, "putBoolean", "(Ljava/lang/Object;JZ)V");

    // ServerLevel 类
    jclass serverLevelCls = env->FindClass("net/minecraft/server/level/ServerLevel");
    if (!serverLevelCls) {
        LOG_DEBUG("FATAL: ServerLevel class not found");
        return false;
    }

    // PersistentEntitySectionManager 偏移
    g_offset_level_to_manager = getFieldOffsetByName(env, serverLevelCls, "f_143244_");
    if (g_offset_level_to_manager < 0) {
        jobject f = findFieldByTypeDebug(env, serverLevelCls, "PersistentEntitySectionManager", "PESM");
        if (f) {
            g_offset_level_to_manager = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, f);
            env->DeleteLocalRef(f);
        }
    }
    LOG_DEBUG("g_offset_level_to_manager = %lld", g_offset_level_to_manager);
    if (g_offset_level_to_manager < 0) { env->DeleteLocalRef(serverLevelCls); return false; }

    jclass managerCls = env->FindClass("net/minecraft/world/level/entity/PersistentEntitySectionManager");
    if (!managerCls) { env->DeleteLocalRef(serverLevelCls); return false; }

    // EntityLookup 偏移
    g_offset_manager_to_lookup = getFieldOffsetByName(env, managerCls, "f_157494_");
    if (g_offset_manager_to_lookup < 0) {
        jobject f = findFieldByTypeDebug(env, managerCls, "EntityLookup", "PESM");
        if (f) {
            g_offset_manager_to_lookup = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, f);
            env->DeleteLocalRef(f);
        }
    }
    LOG_DEBUG("g_offset_manager_to_lookup = %lld", g_offset_manager_to_lookup);

    // EntityLookup -> Int2ObjectMap
    jclass lookupCls = env->FindClass("net/minecraft/world/level/entity/EntityLookup");
    if (lookupCls) {
        g_offset_lookup_to_entityMap = getFieldOffsetByName(env, lookupCls, "f_156807_");
        if (g_offset_lookup_to_entityMap < 0) {
            jobject f = findFieldByTypeDebug(env, lookupCls, "Int2Object", "EntityLookup");
            if (f) {
                g_offset_lookup_to_entityMap = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, f);
                env->DeleteLocalRef(f);
            }
        }
        env->DeleteLocalRef(lookupCls);
    }
    LOG_DEBUG("g_offset_lookup_to_entityMap = %lld", g_offset_lookup_to_entityMap);

    // 收集额外容器
    g_removalPaths.clear();
    // entityList (f_8546_)
    jobject listField = findFieldByTypeDebug(env, serverLevelCls, "java/util/List", "ServerLevel.entityList");
    if (!listField) listField = findFieldByTypeDebug(env, serverLevelCls, "java/util/Collection", "ServerLevel.entityCollection");
    if (listField) {
        jlong listOff = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, listField);
        env->DeleteLocalRef(listField);
        if (listOff >= 0) g_removalPaths.push_back({{listOff}, false});
    }

    // EntityTickList 内部 Map
    jlong tickOff = getFieldOffsetByName(env, serverLevelCls, "f_143243_");
    if (tickOff >= 0) {
        jclass tickCls = env->FindClass("net/minecraft/world/level/entity/EntityTickList");
        if (tickCls) {
            jclass clsClass = env->FindClass("java/lang/Class");
            jmethodID getFields = env->GetMethodID(clsClass, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
            jobjectArray fields = (jobjectArray)env->CallObjectMethod(tickCls, getFields);
            if (fields && !env->ExceptionCheck()) {
                jsize len = env->GetArrayLength(fields);
                jclass fieldCls = env->FindClass("java/lang/reflect/Field");
                jmethodID getType = env->GetMethodID(fieldCls, "getType", "()Ljava/lang/Class;");
                jmethodID getName = env->GetMethodID(clsClass, "getName", "()Ljava/lang/String;");
                for (jsize i=0; i<len; ++i) {
                    jobject f = env->GetObjectArrayElement(fields, i);
                    jclass fType = (jclass)env->CallObjectMethod(f, getType);
                    jstring tName = (jstring)env->CallObjectMethod(fType, getName);
                    const char* tn = env->GetStringUTFChars(tName, nullptr);
                    if (strstr(tn, "Int2Object")) {
                        jlong inner = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, f);
                        g_removalPaths.push_back({{tickOff, inner}, true});
                        LOG_DEBUG("Added EntityTickList Int2ObjectMap at offset %lld", inner);
                    }
                    env->ReleaseStringUTFChars(tName, tn);
                    env->DeleteLocalRef(tName);
                    env->DeleteLocalRef(fType);
                    env->DeleteLocalRef(f);
                }
                env->DeleteLocalRef(fieldCls);
                env->DeleteLocalRef(fields);
            } else env->ExceptionClear();
            env->DeleteLocalRef(clsClass);
            env->DeleteLocalRef(tickCls);
        }
    }

    // PESM Set (f_157491_)
    jlong setOff = getFieldOffsetByName(env, managerCls, "f_157491_");
    if (setOff >= 0) g_removalPaths.push_back({{g_offset_level_to_manager, setOff}, false});

    // EntityIndex
    jobject eiField = findFieldByTypeDebug(env, serverLevelCls, "EntityIndex", "EntityIndex");
    if (!eiField) eiField = findFieldByTypeDebug(env, serverLevelCls, "Int2Object", "EntityIndex");
    if (eiField) {
        g_offset_level_to_entityIndex = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, eiField);
        jclass eiCls = (jclass)env->CallObjectMethod(eiField, env->GetMethodID(env->FindClass("java/lang/reflect/Field"), "getType", "()Ljava/lang/Class;"));
        jobject mf = findFieldByTypeDebug(env, eiCls, "Int2Object", "EntityIndexMap");
        if (mf) {
            g_offset_entityIndex_to_map = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, mf);
            env->DeleteLocalRef(mf);
        }
        env->DeleteLocalRef(eiCls);
        env->DeleteLocalRef(eiField);
        LOG_DEBUG("EntityIndex offset: %lld, map offset: %lld", g_offset_level_to_entityIndex, g_offset_entityIndex_to_map);
    }

    // ChunkMap 路径
    g_offset_level_to_chunkSource = getFieldOffsetByName(env, serverLevelCls, "f_8547_");
    if (g_offset_level_to_chunkSource >= 0) {
        jclass chunkSourceCls = env->FindClass("net/minecraft/server/level/ServerChunkCache");
        if (chunkSourceCls) {
            g_offset_chunkSource_to_chunkMap = getFieldOffsetByName(env, chunkSourceCls, "f_8325_");
            if (g_offset_chunkSource_to_chunkMap >= 0) {
                jclass chunkMapCls = env->FindClass("net/minecraft/server/level/ChunkMap");
                if (chunkMapCls) {
                    g_offset_chunkMap_to_entityMap = getFieldOffsetByName(env, chunkMapCls, "f_140150_");
                    jclass etCls = env->FindClass("net/minecraft/server/level/ChunkMap$EntityTracker");
                    if (etCls) {
                        jobject playersField = findFieldByTypeDebug(env, etCls, "Set", "EntityTracker.players");
                        if (playersField) {
                            g_offset_entityTracker_to_players = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, playersField);
                            env->DeleteLocalRef(playersField);
                        }
                        env->DeleteLocalRef(etCls);
                    }
                    env->DeleteLocalRef(chunkMapCls);
                }
            }
            env->DeleteLocalRef(chunkSourceCls);
        }
    }
    LOG_DEBUG("ChunkMap chain: %lld %lld %lld %lld",
        g_offset_level_to_chunkSource, g_offset_chunkSource_to_chunkMap,
        g_offset_chunkMap_to_entityMap, g_offset_entityTracker_to_players);

    // ClientboundRemoveEntitiesPacket
    g_class_removeEntitiesPacket = (jclass)env->NewGlobalRef(env->FindClass("net/minecraft/network/protocol/game/ClientboundRemoveEntitiesPacket"));
    if (g_class_removeEntitiesPacket) {
        g_ctor_removeEntitiesPacket = env->GetMethodID(g_class_removeEntitiesPacket, "<init>", "([I)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_ctor_removeEntitiesPacket = nullptr;
        }
    }
    // 缓存连接字段和方法
    jclass spCls = env->FindClass("net/minecraft/server/level/ServerPlayer");
    if (spCls) {
        g_field_serverPlayer_connection = env->GetFieldID(spCls, "f_8906_", "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_field_serverPlayer_connection = env->GetFieldID(spCls, "connection", "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;");
        }
        env->DeleteLocalRef(spCls);
    }
    jclass connCls = env->FindClass("net/minecraft/server/network/ServerGamePacketListenerImpl");
    if (connCls) {
        g_method_connection_send = env->GetMethodID(connCls, "send", "(Lnet/minecraft/network/protocol/Packet;)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_method_connection_send = nullptr;
        }
        env->DeleteLocalRef(connCls);
    }
    LOG_DEBUG("RemoveEntitiesPacket: class=%p, ctor=%p, send=%p, connField=%p",
        g_class_removeEntitiesPacket, g_ctor_removeEntitiesPacket,
        g_method_connection_send, g_field_serverPlayer_connection);

    // Entity.remove(RemovalReason)
    jclass entityCls = env->FindClass("net/minecraft/world/entity/Entity");
    if (entityCls) {
        g_method_entity_remove = findMethodByReflection(env, entityCls, "remove", "RemovalReason");
        if (g_method_entity_remove) {
            jclass rrCls = env->FindClass("net/minecraft/world/entity/Entity$RemovalReason");
            if (rrCls) {
                jfieldID killedF = env->GetStaticFieldID(rrCls, "KILLED", "Lnet/minecraft/world/entity/Entity$RemovalReason;");
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    killedF = env->GetStaticFieldID(rrCls, "DISCARDED", "Lnet/minecraft/world/entity/Entity$RemovalReason;");
                }
                if (killedF) {
                    jobject local = env->GetStaticObjectField(rrCls, killedF);
                    g_killedRemovalReason = env->NewGlobalRef(local);
                    env->DeleteLocalRef(local);
                }
                env->DeleteLocalRef(rrCls);
            }
        }
        g_offset_entity_removed = getFieldOffsetByName(env, entityCls, "f_146812_");
        if (g_offset_entity_removed < 0) g_offset_entity_removed = getFieldOffsetByName(env, entityCls, "removed");
        env->DeleteLocalRef(entityCls);
    }
    LOG_DEBUG("Entity.remove=%p, reason=%p, removed offset=%lld",
        g_method_entity_remove, g_killedRemovalReason, g_offset_entity_removed);

    // EntitySection 相关
    jlong storageOff = getFieldOffsetByName(env, managerCls, "f_157495_");
    if (storageOff >= 0) {
        g_offset_pesm_to_sectionStorage = storageOff;
        jclass storageCls = env->FindClass("net/minecraft/world/level/entity/EntitySectionStorage");
        if (storageCls) {
            jobject mapField = findFieldByTypeDebug(env, storageCls, "Long2Object", "SectionStorage");
            if (mapField) {
                g_offset_sectionStorage_to_sectionsMap = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, mapField);
                env->DeleteLocalRef(mapField);
            }
            env->DeleteLocalRef(storageCls);
        }
        jclass esCls = env->FindClass("net/minecraft/world/level/entity/EntitySection");
        if (esCls) {
            g_offset_entitySection_to_entities = getFieldOffsetByName(env, esCls, "f_156827_");
            env->DeleteLocalRef(esCls);
        }
    }
    LOG_DEBUG("Section storage offsets: %lld %lld %lld",
        g_offset_pesm_to_sectionStorage, g_offset_sectionStorage_to_sectionsMap,
        g_offset_entitySection_to_entities);

    // 公共方法
    jclass mapCls = env->FindClass("java/util/Map");
    if (mapCls) {
        g_method_map_get = env->GetMethodID(mapCls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
        g_method_map_remove = env->GetMethodID(mapCls, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;");
        env->DeleteLocalRef(mapCls);
    }
    jclass collCls = env->FindClass("java/util/Collection");
    if (collCls) {
        g_method_collection_remove = env->GetMethodID(collCls, "remove", "(Ljava/lang/Object;)Z");
        env->DeleteLocalRef(collCls);
    }

    env->DeleteLocalRef(managerCls);
    env->DeleteLocalRef(serverLevelCls);

    LOG_DEBUG("Initialization complete. ChunkMap chain: %s, Packets: %s",
        (g_offset_level_to_chunkSource>=0 && g_offset_chunkSource_to_chunkMap>=0 &&
         g_offset_chunkMap_to_entityMap>=0 && g_offset_entityTracker_to_players>=0) ? "OK" : "BROKEN",
        (g_class_removeEntitiesPacket && g_ctor_removeEntitiesPacket && g_method_connection_send) ? "OK" : "MISSING");

    // --- 缓存容器操作方法 ---
    jclass listCls = env->FindClass("java/util/List");
    if (listCls) {
        g_mid_list_iterator = env->GetMethodID(listCls, "iterator", "()Ljava/util/Iterator;");
        env->DeleteLocalRef(listCls);
    }
    jclass iterCls = env->FindClass("java/util/Iterator");
    if (iterCls) {
        g_mid_iterator_hasNext = env->GetMethodID(iterCls, "hasNext", "()Z");
        g_mid_iterator_next = env->GetMethodID(iterCls, "next", "()Ljava/lang/Object;");
        g_mid_iterator_remove = env->GetMethodID(iterCls, "remove", "()V");
        env->DeleteLocalRef(iterCls);
    }

    // 获取 ServerLevel.players 字段偏移（用于发送销毁包）
    jclass serverLevelClsFinal = env->FindClass("net/minecraft/server/level/ServerLevel");
    if (serverLevelClsFinal) {
        jlong playersOff = getFieldOffsetByName(env, serverLevelClsFinal, "players");
        if (playersOff < 0) playersOff = getFieldOffsetByName(env, serverLevelClsFinal, "f_8558_");
        if (playersOff < 0) playersOff = getFieldOffsetByName(env, serverLevelClsFinal, "field_8558_");
        if (playersOff < 0) {
            jobject f = findFieldByTypeDebug(env, serverLevelClsFinal, "java/util/List", "players");
            if (f) {
                playersOff = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, f);
                env->DeleteLocalRef(f);
            }
        }
        if (playersOff >= 0) {
            LOG_DEBUG("ServerLevel.players offset = %lld", playersOff);
        } else {
            LOG_DEBUG("WARNING: Could not find players field, packet sending disabled.");
        }
        env->DeleteLocalRef(serverLevelClsFinal);
        g_offset_level_to_players = playersOff;
    } else {
        LOG_DEBUG("No serverLevelClsFinal Was Found. Init Failed!");
        return false;
    }

    // ---------- 补充：获取 entityList 的 Unsafe 偏移（使用混淆名） ----------
    jclass serverLevelForList = env->FindClass("net/minecraft/server/level/ServerLevel");
    if (serverLevelForList) {
        jlong off = getFieldOffsetByName(env, serverLevelForList, "f_8546_");
        if (off < 0) off = getFieldOffsetByName(env, serverLevelForList, "entityList");
        if (off >= 0) {
            g_offset_level_entityList = off;
            LOG_DEBUG("ServerLevel.entityList offset = %lld", off);
        } else {
            LOG_DEBUG("WARNING: entityList field not found, List removal may fail.");
        }
        env->DeleteLocalRef(serverLevelForList);
    }

    // ---------- 补充：获取 Entity.getId() 方法 ----------
    jclass entityClsForId = env->FindClass("net/minecraft/world/entity/Entity");
    if (entityClsForId) {
        g_mid_entity_getId = env->GetMethodID(entityClsForId, "getId", "()I");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_mid_entity_getId = env->GetMethodID(entityClsForId, "m_19879_", "()I");
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOG_DEBUG("WARNING: Entity.getId() method not found!");
            g_mid_entity_getId = nullptr;
        } else {
            LOG_DEBUG("Entity.getId() method cached: %p", g_mid_entity_getId);
        }
        env->DeleteLocalRef(entityClsForId);
    }

    // ---------- EntitySection 安全移除方法 ----------
    jclass esCls = env->FindClass("net/minecraft/world/level/entity/EntitySection");
    if (esCls) {
        g_class_EntitySection = (jclass)env->NewGlobalRef(esCls);
        g_method_EntitySection_getEntities = env->GetMethodID(esCls, "getEntities", "()Ljava/lang/Iterable;");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_method_EntitySection_getEntities = env->GetMethodID(esCls, "m_156834_", "()Ljava/lang/Iterable;");
        }
        if (env->ExceptionCheck() || !g_method_EntitySection_getEntities) {
            env->ExceptionClear();
            jclass clsClass = env->FindClass("java/lang/Class");
            jmethodID getMethods = env->GetMethodID(clsClass, "getMethods", "()[Ljava/lang/reflect/Method;");
            jobjectArray methods = (jobjectArray)env->CallObjectMethod(esCls, getMethods);
            if (methods && !env->ExceptionCheck()) {
                jsize len = env->GetArrayLength(methods);
                jclass methodClass = env->FindClass("java/lang/reflect/Method");
                jmethodID mGetReturnType = env->GetMethodID(methodClass, "getReturnType", "()Ljava/lang/Class;");
                jmethodID mGetParameterCount = env->GetMethodID(methodClass, "getParameterCount", "()I");
                jmethodID clsGetName = env->GetMethodID(clsClass, "getName", "()Ljava/lang/String;");
                for (jsize i = 0; i < len; ++i) {
                    jobject m = env->GetObjectArrayElement(methods, i);
                    if (env->CallIntMethod(m, mGetParameterCount) == 0) {
                        jclass retType = (jclass)env->CallObjectMethod(m, mGetReturnType);
                        jstring retName = (jstring)env->CallObjectMethod(retType, clsGetName);
                        const char* retStr = env->GetStringUTFChars(retName, nullptr);
                        if (strstr(retStr, "Iterable")) {
                            g_method_EntitySection_getEntities = env->FromReflectedMethod(m);
                            env->ReleaseStringUTFChars(retName, retStr);
                            env->DeleteLocalRef(retName);
                            env->DeleteLocalRef(retType);
                            env->DeleteLocalRef(m);
                            break;
                        }
                        env->ReleaseStringUTFChars(retName, retStr);
                        env->DeleteLocalRef(retName);
                        env->DeleteLocalRef(retType);
                    }
                    env->DeleteLocalRef(m);
                }
                env->DeleteLocalRef(methodClass);
                env->DeleteLocalRef(methods);
                env->DeleteLocalRef(clsClass);
            }
        }
        if (g_method_EntitySection_getEntities) {
            LOG_DEBUG("EntitySection.getEntities() methodID = %p", g_method_EntitySection_getEntities);
        } else {
            LOG_DEBUG("ERROR: EntitySection.getEntities() not found, section removal disabled.");
        }
    } else {
        LOG_DEBUG("EntitySection class not found");
    }

    // 获取 Entity.removed 字段
    jclass entityClsFinal = env->FindClass("net/minecraft/world/entity/Entity");
    if (entityClsFinal) {
        g_field_entity_removed = env->GetFieldID(entityClsFinal, "f_146812_", "Z");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_field_entity_removed = env->GetFieldID(entityClsFinal, "removed", "Z");
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_field_entity_removed = env->GetFieldID(entityClsFinal, "field_7625_a", "Z");
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOG_DEBUG("WARNING: Entity.removed field not found, mark as removed may fail.");
            g_field_entity_removed = nullptr;
        } else {
            LOG_DEBUG("Entity.removed fieldID = %p", g_field_entity_removed);
        }
        env->DeleteLocalRef(entityClsFinal);
    }

    // ---------- 修复 ClientboundRemoveEntitiesPacket 构造 ----------
    if (g_class_removeEntitiesPacket) {
        g_ctor_removeEntitiesPacket = env->GetMethodID(g_class_removeEntitiesPacket, "<init>", "([I)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_ctor_removeEntitiesPacket = env->GetMethodID(g_class_removeEntitiesPacket, "<init>",
                                                           "(Lit/unimi/dsi/fastutil/ints/IntList;)V");
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOG_DEBUG("ClientboundRemoveEntitiesPacket constructor not found!");
            g_ctor_removeEntitiesPacket = nullptr;
        } else {
            LOG_DEBUG("ClientboundRemoveEntitiesPacket constructor = %p", g_ctor_removeEntitiesPacket);
        }
    }

    // 缓存 Connection.send 方法
    jclass connClsFinal = env->FindClass("net/minecraft/server/network/ServerGamePacketListenerImpl");
    if (connClsFinal) {
        g_method_connection_send = env->GetMethodID(connClsFinal, "send", "(Lnet/minecraft/network/protocol/Packet;)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_method_connection_send = env->GetMethodID(connClsFinal, "m_141995_", "(Lnet/minecraft/network/protocol/Packet;)V");
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            g_method_connection_send = findMethodByReflection(env, connClsFinal, "send", "Packet");
            if (!g_method_connection_send)
                g_method_connection_send = findMethodByReflection(env, connClsFinal, "sendPacket", "Packet");
        }
        if (env->ExceptionCheck() || !g_method_connection_send) {
            env->ExceptionClear();
            LOG_DEBUG("WARNING: Connection.send() method not found, packet will not be sent.");
            g_method_connection_send = nullptr;
        } else {
            LOG_DEBUG("Connection.send() methodID = %p", g_method_connection_send);
        }
        env->DeleteLocalRef(connClsFinal);
    }

    g_init_done = true;
    return true;
}

// ===================================================================
//  从ChunkMap 的 entityMap 中移除，并手动通过 ServerLevel.players 发送销毁包，
// ===================================================================
static bool directChunkMapRemoval(JNIEnv* env, jobject level, jobject entity, jint entityId) {
    if (!g_init_done) {
        LOG_DEBUG("directChunkMapRemoval: init not done");
        return false;
    }
    if (g_offset_level_to_chunkSource < 0 || g_offset_chunkSource_to_chunkMap < 0 ||
        g_offset_chunkMap_to_entityMap < 0) {
        LOG_DEBUG("directChunkMapRemoval: ChunkMap chain offsets invalid");
        return false;
    }
    if (!g_method_map_remove) {
        LOG_DEBUG("directChunkMapRemoval: Map.remove method not found");
        return false;
    }

    LOG_DEBUG("directChunkMapRemoval: removing entity %d from ChunkMap entityMap", entityId);

    jobject chunkSource = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, level, g_offset_level_to_chunkSource);
    if (env->ExceptionCheck() || !chunkSource) {
        env->ExceptionClear();
        return false;
    }
    jobject chunkMap = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, chunkSource, g_offset_chunkSource_to_chunkMap);
    env->DeleteLocalRef(chunkSource);
    if (env->ExceptionCheck() || !chunkMap) {
        env->ExceptionClear();
        return false;
    }
    jobject entityMap = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, chunkMap, g_offset_chunkMap_to_entityMap);
    env->DeleteLocalRef(chunkMap);
    if (env->ExceptionCheck() || !entityMap) {
        env->ExceptionClear();
        return false;
    }

    jclass intCls = env->FindClass("java/lang/Integer");
    if (intCls) {
        jmethodID valueOf = env->GetStaticMethodID(intCls, "valueOf", "(I)Ljava/lang/Integer;");
        if (valueOf) {
            jobject key = env->CallStaticObjectMethod(intCls, valueOf, entityId);
            if (key && !env->ExceptionCheck()) {
                env->CallObjectMethod(entityMap, g_method_map_remove, key);
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(key);
            } else env->ExceptionClear();
        }
        env->DeleteLocalRef(intCls);
    }
    env->DeleteLocalRef(entityMap);
    return true;
}

// ===================================================================
// 彻底移除实体（包含所有容器 + 后备调用 Entity.remove）
// ===================================================================
static void forceRemoveEntity(JNIEnv* env, jobject level, jobject entity, jint entityId) {
    LOG_DEBUG("Force removing entity %d", entityId);

    // ---- 1. 主映射 (EntityLookup.Int2ObjectMap) 按 ID 移除 ----
    if (g_method_map_remove) {
        jobject mainMap = getEntityMap(env, level);
        if (mainMap) {
            jclass intCls = env->FindClass("java/lang/Integer");
            if (intCls) {
                jmethodID valueOf = env->GetStaticMethodID(intCls, "valueOf", "(I)Ljava/lang/Integer;");
                if (valueOf) {
                    jobject key = env->CallStaticObjectMethod(intCls, valueOf, entityId);
                    if (key && !env->ExceptionCheck()) {
                        env->CallObjectMethod(mainMap, g_method_map_remove, key);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        env->DeleteLocalRef(key);
                    } else env->ExceptionClear();
                }
                env->DeleteLocalRef(intCls);
            }
            env->DeleteLocalRef(mainMap);
        }
    }

    // ---- 2. ServerLevel.entityList (List<Entity>) 迭代移除 ----
    if (g_offset_level_entityList >= 0 && g_mid_list_iterator && g_mid_iterator_hasNext && g_mid_iterator_next && g_mid_iterator_remove && g_mid_entity_getId) {
        jobject entityList = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, level, g_offset_level_entityList);
        if (entityList && !env->ExceptionCheck()) {
            jobject iter = env->CallObjectMethod(entityList, g_mid_list_iterator);
            if (iter && !env->ExceptionCheck()) {
                while (env->CallBooleanMethod(iter, g_mid_iterator_hasNext) && !env->ExceptionCheck()) {
                    jobject e = env->CallObjectMethod(iter, g_mid_iterator_next);
                    if (e && !env->ExceptionCheck()) {
                        jint id = env->CallIntMethod(e, g_mid_entity_getId);
                        if (!env->ExceptionCheck() && id == entityId) {
                            env->CallVoidMethod(iter, g_mid_iterator_remove);
                            env->DeleteLocalRef(e);
                            break;
                        }
                        env->ExceptionClear();
                        env->DeleteLocalRef(e);
                    } else {
                        env->ExceptionClear();
                        if (e) env->DeleteLocalRef(e);
                    }
                }
                env->DeleteLocalRef(iter);
            }
            env->ExceptionClear();
            env->DeleteLocalRef(entityList);
        } else env->ExceptionClear();
    }

    // ---- 3. EntityTickList 内部 Map (已在 g_removalPaths 中) 按 ID 移除 ----
    for (auto& path : g_removalPaths) {
        jobject current = level;
        for (size_t i = 0; i < path.offsets.size(); ++i) {
            jobject next = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, current, path.offsets[i]);
            if (i > 0) env->DeleteLocalRef(current);
            if (!next || env->ExceptionCheck()) {
                env->ExceptionClear();
                current = nullptr;
                break;
            }
            current = next;
        }
        if (!current) continue;
        if (path.isMap) {
            if (g_method_map_remove) {
                jclass intCls = env->FindClass("java/lang/Integer");
                if (intCls) {
                    jmethodID valueOf = env->GetStaticMethodID(intCls, "valueOf", "(I)Ljava/lang/Integer;");
                    if (valueOf) {
                        jobject key = env->CallStaticObjectMethod(intCls, valueOf, entityId);
                        if (key && !env->ExceptionCheck()) {
                            env->CallObjectMethod(current, g_method_map_remove, key);
                            if (env->ExceptionCheck()) env->ExceptionClear();
                            env->DeleteLocalRef(key);
                        } else env->ExceptionClear();
                    }
                    env->DeleteLocalRef(intCls);
                }
            }
        } else {
            // 列表类型，使用迭代器移除
            if (g_mid_list_iterator && g_mid_iterator_hasNext && g_mid_iterator_next && g_mid_iterator_remove && g_mid_entity_getId) {
                jobject iter = env->CallObjectMethod(current, g_mid_list_iterator);
                if (iter && !env->ExceptionCheck()) {
                    while (env->CallBooleanMethod(iter, g_mid_iterator_hasNext) && !env->ExceptionCheck()) {
                        jobject e = env->CallObjectMethod(iter, g_mid_iterator_next);
                        if (e && !env->ExceptionCheck()) {
                            jint id = env->CallIntMethod(e, g_mid_entity_getId);
                            if (!env->ExceptionCheck() && id == entityId) {
                                env->CallVoidMethod(iter, g_mid_iterator_remove);
                                env->DeleteLocalRef(e);
                                break;
                            }
                            env->ExceptionClear();
                            env->DeleteLocalRef(e);
                        } else {
                            env->ExceptionClear();
                            if (e) env->DeleteLocalRef(e);
                        }
                    }
                    env->DeleteLocalRef(iter);
                }
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(current);
    }

    // ---- 4. EntitySectionStorage 安全移除（通过 getEntities() 迭代器） ----
    if (g_class_EntitySection && g_method_EntitySection_getEntities &&
        g_offset_pesm_to_sectionStorage >= 0 && g_offset_sectionStorage_to_sectionsMap >= 0 &&
        g_mid_entity_getId) {
        jobject manager = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, level, g_offset_level_to_manager);
        if (!env->ExceptionCheck() && manager) {
            jobject storage = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, manager, g_offset_pesm_to_sectionStorage);
            env->DeleteLocalRef(manager);
            if (!env->ExceptionCheck() && storage) {
                jobject sectionsMap = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, storage, g_offset_sectionStorage_to_sectionsMap);
                env->DeleteLocalRef(storage);
                if (!env->ExceptionCheck() && sectionsMap) {
                    jclass mapCls = env->GetObjectClass(sectionsMap);
                    jmethodID valuesMid = env->GetMethodID(mapCls, "values", "()Ljava/util/Collection;");
                    if (valuesMid) {
                        jobject values = env->CallObjectMethod(sectionsMap, valuesMid);
                        if (!env->ExceptionCheck() && values) {
                            jclass collCls = env->GetObjectClass(values);
                            jmethodID toArrayMid = env->GetMethodID(collCls, "toArray", "()[Ljava/lang/Object;");
                            if (toArrayMid) {
                                jobjectArray arr = (jobjectArray)env->CallObjectMethod(values, toArrayMid);
                                if (!env->ExceptionCheck() && arr) {
                                    jsize count = env->GetArrayLength(arr);
                                    for (jsize i = 0; i < count; ++i) {
                                        jobject section = env->GetObjectArrayElement(arr, i);
                                        if (!section) continue;

                                        jobject iterable = env->CallObjectMethod(section, g_method_EntitySection_getEntities);
                                        if (env->ExceptionCheck() || !iterable) {
                                            env->ExceptionClear();
                                            env->DeleteLocalRef(section);
                                            continue;
                                        }

                                        jclass iterableCls = env->GetObjectClass(iterable);
                                        jmethodID iteratorMid = env->GetMethodID(iterableCls, "iterator", "()Ljava/util/Iterator;");
                                        if (!iteratorMid) { env->ExceptionClear(); env->DeleteLocalRef(iterable); env->DeleteLocalRef(section); continue; }
                                        jobject iter = env->CallObjectMethod(iterable, iteratorMid);
                                        env->DeleteLocalRef(iterableCls);
                                        env->DeleteLocalRef(iterable);
                                        if (env->ExceptionCheck() || !iter) {
                                            env->ExceptionClear();
                                            env->DeleteLocalRef(section);
                                            continue;
                                        }

                                        jclass iterCls = env->GetObjectClass(iter);
                                        jmethodID hasNextMid = env->GetMethodID(iterCls, "hasNext", "()Z");
                                        jmethodID nextMid = env->GetMethodID(iterCls, "next", "()Ljava/lang/Object;");
                                        jmethodID removeMid = env->GetMethodID(iterCls, "remove", "()V");
                                        env->DeleteLocalRef(iterCls);
                                        if (hasNextMid && nextMid && removeMid) {
                                            while (env->CallBooleanMethod(iter, hasNextMid) && !env->ExceptionCheck()) {
                                                jobject e = env->CallObjectMethod(iter, nextMid);
                                                if (e && !env->ExceptionCheck()) {
                                                    jint id = env->CallIntMethod(e, g_mid_entity_getId);
                                                    if (!env->ExceptionCheck() && id == entityId) {
                                                        env->CallVoidMethod(iter, removeMid);
                                                        env->DeleteLocalRef(e);
                                                        if (!env->ExceptionCheck()) break;
                                                        env->ExceptionClear();
                                                    } else {
                                                        env->ExceptionClear();
                                                    }
                                                    env->DeleteLocalRef(e);
                                                } else {
                                                    env->ExceptionClear();
                                                    if (e) env->DeleteLocalRef(e);
                                                    break;
                                                }
                                            }
                                        }
                                        env->DeleteLocalRef(iter);
                                        env->DeleteLocalRef(section);
                                    }
                                    env->DeleteLocalRef(arr);
                                } else env->ExceptionClear();
                            }
                            env->DeleteLocalRef(collCls);
                            env->DeleteLocalRef(values);
                        } else env->ExceptionClear();
                    }
                    env->DeleteLocalRef(mapCls);
                    env->DeleteLocalRef(sectionsMap);
                } else env->ExceptionClear();
            }
        }
        env->ExceptionClear();
    }

    // ---- 5. EntityIndex 按 ID 移除 ----
    if (g_offset_level_to_entityIndex >= 0 && g_offset_entityIndex_to_map >= 0 && g_method_map_remove) {
        jobject ei = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, level, g_offset_level_to_entityIndex);
        if (ei && !env->ExceptionCheck()) {
            jobject idxMap = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, ei, g_offset_entityIndex_to_map);
            env->DeleteLocalRef(ei);
            if (idxMap && !env->ExceptionCheck()) {
                jclass intCls = env->FindClass("java/lang/Integer");
                if (intCls) {
                    jmethodID valueOf = env->GetStaticMethodID(intCls, "valueOf", "(I)Ljava/lang/Integer;");
                    if (valueOf) {
                        jobject key = env->CallStaticObjectMethod(intCls, valueOf, entityId);
                        if (key && !env->ExceptionCheck()) {
                            env->CallObjectMethod(idxMap, g_method_map_remove, key);
                            env->ExceptionClear();
                            env->DeleteLocalRef(key);
                        } else env->ExceptionClear();
                    }
                    env->DeleteLocalRef(intCls);
                }
                env->DeleteLocalRef(idxMap);
            } else env->ExceptionClear();
        }
        env->ExceptionClear();
    }

    // ---- 6. ChunkMap 移除 + 网络包 ----
    directChunkMapRemoval(env, level, entity, entityId);

    // ---- 确保从 PESM 的 Set<Entity> 中移除 (f_157491_) ----
    if (g_offset_level_to_manager >= 0 && g_mid_list_iterator && g_mid_iterator_hasNext && g_mid_iterator_next && g_mid_iterator_remove && g_mid_entity_getId) {
        jobject manager = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, level, g_offset_level_to_manager);
        if (manager && !env->ExceptionCheck()) {
            jlong setOffset = getFieldOffsetByName(env, env->GetObjectClass(manager), "f_157491_");
            if (setOffset < 0) {
                jobject setField = findFieldByTypeDebug(env, env->GetObjectClass(manager), "java/util/Set", "PESM.set");
                if (setField) {
                    setOffset = env->CallLongMethod(g_unsafeInstance, g_unsafe_objectFieldOffset, setField);
                    env->DeleteLocalRef(setField);
                }
            }
            if (setOffset >= 0) {
                jobject entitySet = env->CallObjectMethod(g_unsafeInstance, g_unsafe_getObject, manager, setOffset);
                if (entitySet && !env->ExceptionCheck()) {
                    jobject iter = env->CallObjectMethod(entitySet, g_mid_list_iterator);
                    if (iter && !env->ExceptionCheck()) {
                        while (env->CallBooleanMethod(iter, g_mid_iterator_hasNext) && !env->ExceptionCheck()) {
                            jobject e = env->CallObjectMethod(iter, g_mid_iterator_next);
                            if (e && !env->ExceptionCheck()) {
                                jint id = env->CallIntMethod(e, g_mid_entity_getId);
                                if (!env->ExceptionCheck() && id == entityId) {
                                    env->CallVoidMethod(iter, g_mid_iterator_remove);
                                    env->DeleteLocalRef(e);
                                    LOG_DEBUG("Removed entity %d from PESM entitySet", entityId);
                                    break;
                                }
                                env->ExceptionClear();
                                env->DeleteLocalRef(e);
                            } else {
                                env->ExceptionClear();
                                if (e) env->DeleteLocalRef(e);
                            }
                        }
                        env->DeleteLocalRef(iter);
                    }
                    env->ExceptionClear();
                    env->DeleteLocalRef(entitySet);
                }
            }
            env->DeleteLocalRef(manager);
        }
        env->ExceptionClear();
    }

    // ---- 7. 强制设置 removed 标志（使用 JNI 字段访问） ----
    if (g_field_entity_removed) {
        if (entity && isObjectValid(env, entity)) {
            env->SetBooleanField(entity, g_field_entity_removed, JNI_TRUE);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            } else {
                LOG_DEBUG("Set entity.removed = true on passed entity");
            }
        } else {
            jobject map = getEntityMap(env, level);
            if (map && g_method_map_get) {
                jclass intCls = env->FindClass("java/lang/Integer");
                if (intCls) {
                    jmethodID valueOf = env->GetStaticMethodID(intCls, "valueOf", "(I)Ljava/lang/Integer;");
                    if (valueOf) {
                        jobject key = env->CallStaticObjectMethod(intCls, valueOf, entityId);
                        if (key && !env->ExceptionCheck()) {
                            jobject validEntity = env->CallObjectMethod(map, g_method_map_get, key);
                            if (validEntity && !env->ExceptionCheck() && isObjectValid(env, validEntity)) {
                                env->SetBooleanField(validEntity, g_field_entity_removed, JNI_TRUE);
                                if (!env->ExceptionCheck()) {
                                    LOG_DEBUG("Set entity.removed = true via re-get from map");
                                } else {
                                    env->ExceptionClear();
                                }
                            }
                            env->ExceptionClear();
                            if (validEntity) env->DeleteLocalRef(validEntity);
                            env->DeleteLocalRef(key);
                        }
                        env->ExceptionClear();
                    }
                    env->DeleteLocalRef(intCls);
                }
                env->DeleteLocalRef(map);
            }
        }
    } else {
        LOG_DEBUG("WARNING: Entity.removed field not available, entity may persist.");
    }

    LOG_DEBUG("Entity %d removed successfully (direct container removal).", entityId);
}

extern "C" {

JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeKillEntity
(JNIEnv* env, jclass, jobject level, jint entityId) {
    LOG_DEBUG("nativeKillEntity Method Called.");
    if (!level) {
        LOG_DEBUG("nativeKillEntity: level is null");
        return;
    }
    if (!initJNICache(env)) {
        LOG_DEBUG("nativeKillEntity: initJNICache failed");
        return;
    }

    jobject map = getEntityMap(env, level);
    if (!map) {
        LOG_DEBUG("nativeKillEntity: entityMap is null");
        return;
    }

    jclass intCls = env->FindClass("java/lang/Integer");
    if (env->ExceptionCheck() || !intCls) {
        env->ExceptionClear();
        env->DeleteLocalRef(map);
        return;
    }
    jmethodID valueOf = env->GetStaticMethodID(intCls, "valueOf", "(I)Ljava/lang/Integer;");
    if (env->ExceptionCheck() || !valueOf) {
        env->ExceptionClear();
        env->DeleteLocalRef(intCls);
        env->DeleteLocalRef(map);
        return;
    }
    jobject key = env->CallStaticObjectMethod(intCls, valueOf, entityId);
    env->DeleteLocalRef(intCls);
    if (env->ExceptionCheck() || !key) {
        env->ExceptionClear();
        env->DeleteLocalRef(map);
        return;
    }

    jobject entity = nullptr;
    if (g_method_map_get) {
        entity = env->CallObjectMethod(map, g_method_map_get, key);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            entity = nullptr;
        }
    }
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(map);

    if (entity && isObjectValid(env, entity)) {
        forceRemoveEntity(env, level, entity, entityId);
        env->DeleteLocalRef(entity);
        LOG_DEBUG("Killed entity %d", entityId);
    } else {
        LOG_DEBUG("Entity %d not found or invalid", entityId);
        if (entity) env->DeleteLocalRef(entity);
    }
}

JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeMassacre
(JNIEnv* env, jclass, jobject level) {
    if (!level || !initJNICache(env)) return;
    jclass nativeGuardClass = env->FindClass("com/godslayer/NativeGuard");
    if (!nativeGuardClass) return;
    jmethodID isHolding = env->GetStaticMethodID(nativeGuardClass, "isHoldingGodSlayer", "(Lnet/minecraft/world/entity/Entity;)Z");
    if (!isHolding) { env->DeleteLocalRef(nativeGuardClass); return; }

    bypassFlag.store(true);
    jobject map = getEntityMap(env, level);
    if (!map) { bypassFlag.store(false); env->DeleteLocalRef(nativeGuardClass); return; }
    jclass mapCls = env->GetObjectClass(map);
    jmethodID keySetMid = env->GetMethodID(mapCls, "keySet", "()Ljava/util/Set;");
    if (!keySetMid) { env->DeleteLocalRef(mapCls); env->DeleteLocalRef(map); bypassFlag.store(false); env->DeleteLocalRef(nativeGuardClass); return; }
    jobject keySet = env->CallObjectMethod(map, keySetMid);
    env->DeleteLocalRef(mapCls);
    if (!keySet || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(map); bypassFlag.store(false); env->DeleteLocalRef(nativeGuardClass); return; }
    jclass setCls = env->GetObjectClass(keySet);
    jmethodID toArrayMid = env->GetMethodID(setCls, "toArray", "()[Ljava/lang/Object;");
    if (!toArrayMid) { env->ExceptionClear(); env->DeleteLocalRef(setCls); env->DeleteLocalRef(keySet); env->DeleteLocalRef(map); bypassFlag.store(false); env->DeleteLocalRef(nativeGuardClass); return; }
    jobjectArray keysArray = (jobjectArray)env->CallObjectMethod(keySet, toArrayMid);
    env->DeleteLocalRef(setCls);
    env->DeleteLocalRef(keySet);
    if (!keysArray || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(map); bypassFlag.store(false); env->DeleteLocalRef(nativeGuardClass); return; }

    jsize count = env->GetArrayLength(keysArray);
    jclass intCls = env->FindClass("java/lang/Integer");
    if (!intCls) { env->DeleteLocalRef(keysArray); env->DeleteLocalRef(map); bypassFlag.store(false); env->DeleteLocalRef(nativeGuardClass); return; }
    jmethodID intValue = env->GetMethodID(intCls, "intValue", "()I");
    if (!intValue) { env->ExceptionClear(); env->DeleteLocalRef(intCls); env->DeleteLocalRef(keysArray); env->DeleteLocalRef(map); bypassFlag.store(false); env->DeleteLocalRef(nativeGuardClass); return; }
    int killed = 0;
    for (jsize i = 0; i < count; ++i) {
        jobject keyObj = env->GetObjectArrayElement(keysArray, i);
        if (!keyObj) continue;
        jint id = env->CallIntMethod(keyObj, intValue);
        if (env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(keyObj); continue; }
        jobject entity = nullptr;
        if (g_method_map_get) {
            entity = env->CallObjectMethod(map, g_method_map_get, keyObj);
            if (env->ExceptionCheck()) { env->ExceptionClear(); entity = nullptr; }
        }
        bool skip = false;
        if (entity && isObjectValid(env, entity)) {
            jboolean holding = env->CallStaticBooleanMethod(nativeGuardClass, isHolding, entity);
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (holding) skip = true;
        }
        if (!skip) {
            forceRemoveEntity(env, level, entity, id);
            killed++;
        }
        if (entity) env->DeleteLocalRef(entity);
        env->DeleteLocalRef(keyObj);
    }
    env->DeleteLocalRef(intCls);
    env->DeleteLocalRef(keysArray);
    env->DeleteLocalRef(map);
    env->DeleteLocalRef(nativeGuardClass);
    bypassFlag.store(false);
    LOG_DEBUG("Massacre finished, removed %d entities", killed);
}

JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeDisableThreats(JNIEnv*, jclass) {}
JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_nativeTickGuard(JNIEnv*, jclass, jobject) {}

} // extern "C"