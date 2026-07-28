#ifndef GODSLAYER_H
#define GODSLAYER_H

#include <jni.h>
#include <atomic>
#include <set>

#ifdef __cplusplus
extern "C" {
#endif

// -------- JNI 全局缓存变量 --------
// Level 相关
extern jclass levelClass;
extern jmethodID level_getAllEntities;        // Level.getAllEntities() -> List<Entity>   (searge: m_156811_)

// List / Iterator (JDK)
extern jclass listClass;
extern jmethodID list_iterator;
extern jclass iteratorClass;
extern jmethodID iterator_hasNext;
extern jmethodID iterator_next;

// Entity 相关
extern jclass entityClass;
extern jmethodID entity_getId;                // Entity.getId() -> int  (尝试 "getId" 或 searge)
extern jmethodID entity_remove;               // Entity.remove(RemovalReason)  (searge: m_146874_)
extern jobject removalReasonDiscarded;        // Entity.RemovalReason.DISCARDED  (尝试获取静态字段)

// LivingEntity 相关
extern jclass livingEntityClass;
extern jmethodID livingEntity_hurt;           // LivingEntity.hurt(DamageSource, float)  (searge: m_6473_)
extern jobject damageSourceGeneric;           // DamageSource.GENERIC  (searge: f_19382_)
extern jfieldID healthFid;                    // LivingEntity.health  (searge: f_20922_)
extern jmethodID getMaxHealthMid;             // LivingEntity.getMaxHealth()  (searge: m_21233_)
extern jfieldID deathTimeFid;                 // LivingEntity.deathTime  (searge: f_20919_)

extern std::atomic<bool> bypassFlag;


// 初始化函数
bool initJNICache(JNIEnv* env, jobject level);

#ifdef __cplusplus
}
#endif

#endif // GODSLAYER_H