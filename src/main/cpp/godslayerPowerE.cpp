#include <jni.h>
#include <jvmti.h>
#include <string.h>
#include <iostream>
#include <stdlib.h>
#include <string>
#include <unordered_set>

// ==================== 全局状态 ====================
static jvmtiEnv* global_jvmti = nullptr;
static void* fake_agent_ptr = nullptr;

// Java 层 Instrumentation 实例的全局引用和方法ID
static jobject g_instrument_impl_ref = nullptr;
static jmethodID g_transform_mid = nullptr;

// 类名过滤器（只有被添加到这个集合中的类，才会被转发到 Java 层）
static std::unordered_set<std::string> g_transform_filter;

// 防重入标志：防止在处理 transform 时又触发了类加载导致死锁
static thread_local bool g_in_hook = false;

// ==================== 结构体定义 (与 OpenJDK 一致) ====================
struct _JPLISEnvironment {
    jvmtiEnv *      mJVMTIEnv;
    struct _JPLISAgent *    mAgent;
    jboolean        mIsRetransformer;
};

struct _JPLISAgent {
    JavaVM *            mJVM;
    struct _JPLISEnvironment    mNormalEnvironment;
    struct _JPLISEnvironment    mRetransformEnvironment;
    jobject             mInstrumentationImpl;
    jmethodID           mPremainCaller;
    jmethodID           mAgentmainCaller;
    jmethodID           mTransform;
    jboolean            mRedefineAvailable;
    jboolean            mRedefineAdded;
    jboolean            mNativeMethodPrefixAvailable;
    jboolean            mNativeMethodPrefixAdded;
    char const *        mAgentClassName;
    char const *        mOptionsString;
    const char *        mJarfile;
};

typedef struct _JPLISAgent JPLISAgent;
typedef struct _JPLISEnvironment JPLISEnvironment;

// ==================== 工具函数 ====================
static jlong ptr_to_jlong(void* ptr) {
    return (jlong)(intptr_t)ptr;
}

// 获取 Class 的 Module（用于调用 transform 方法）
static jobject getModuleObject(JNIEnv* env, jclass classBeingRedefined, jobject loader) {
    if (classBeingRedefined != nullptr) {
        jclass jlClass = env->FindClass("java/lang/Class");
        if (!jlClass) { env->ExceptionClear(); return nullptr; }
        jmethodID getModule = env->GetMethodID(jlClass, "getModule", "()Ljava/lang/Module;");
        if (!getModule) { env->ExceptionClear(); return nullptr; }
        return env->CallObjectMethod(classBeingRedefined, getModule);
    }
    if (loader != nullptr) {
        jclass jlClassLoader = env->GetObjectClass(loader);
        jmethodID getUnnamedModule = env->GetMethodID(jlClassLoader, "getUnnamedModule", "()Ljava/lang/Module;");
        if (!getUnnamedModule) { env->ExceptionClear(); return nullptr; }
        return env->CallObjectMethod(loader, getUnnamedModule);
    }
    return nullptr;
}

// ==================== 核心：ClassFileLoadHook 回调 ====================
static void JNICALL classFileLoadHookHandler(
    jvmtiEnv *jvmti_env,
    JNIEnv *jni_env,
    jclass class_being_redefined,
    jobject loader,
    const char *name,
    jobject protection_domain,
    jint class_data_len,
    const unsigned char *class_data,
    jint *new_class_data_len,
    unsigned char **new_class_data
) {
    // 基础检查
    if (class_data_len <= 0 || name == nullptr) return;
    
    // 检查 Java 层是否已初始化
    if (g_instrument_impl_ref == nullptr || g_transform_mid == nullptr) return;

    // 防重入：如果已经在处理 transform 过程中，直接返回
    if (g_in_hook) return;

    // 类名过滤：如果不在白名单中，直接返回（性能关键！）
    if (g_transform_filter.find(name) == g_transform_filter.end()) return;

    g_in_hook = true;
    JNIEnv* env = jni_env;

    // 准备调用 Java 层的 transform 方法
    jstring classNameStr = env->NewStringUTF(name);
    jbyteArray buffer = env->NewByteArray(class_data_len);
    jobject moduleObj = getModuleObject(env, class_being_redefined, loader);

    jbyteArray result = nullptr;

    if (classNameStr != nullptr && buffer != nullptr) {
        // 将 native 字节数组拷贝到 Java byte[]
        env->SetByteArrayRegion(buffer, 0, class_data_len, (const jbyte*)class_data);
        
        // ★★★ 关键调用：InstrumentationImpl.transform() ★★★
        // 签名: (Ljava/lang/Module;Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;Ljava/security/ProtectionDomain;[BZ)[B
        // 最后一个参数 boolean isRetransformer 必须传 JNI_TRUE，
        // 这样才会触发通过 addTransformer(transformer, true) 注册的 Retransform Transformer
        result = (jbyteArray)env->CallObjectMethod(
            g_instrument_impl_ref, 
            g_transform_mid,
            moduleObj,
            loader,
            classNameStr,
            class_being_redefined,
            protection_domain,
            buffer,
            JNI_TRUE
        );
    }

    // 异常检查
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    } 
    // 如果 Java 层返回了修改后的字节码
    else if (result != nullptr) {
        jsize len = env->GetArrayLength(result);
        if (len > 0) {
            unsigned char* buf = nullptr;
            // 使用 JVMTI 分配内存，JVM 会自动管理其生命周期
            if (jvmti_env->Allocate(len, &buf) == JVMTI_ERROR_NONE) {
                env->GetByteArrayRegion(result, 0, len, (jbyte*)buf);
                *new_class_data_len = len;
                *new_class_data = buf;
                std::cout << "[GodSlayer] Transform applied to: " << name << std::endl;
            }
        }
    }

    // 清理局部引用
    if (moduleObj) env->DeleteLocalRef(moduleObj);
    if (classNameStr) env->DeleteLocalRef(classNameStr);
    if (buffer) env->DeleteLocalRef(buffer);
    if (result) env->DeleteLocalRef(result);

    g_in_hook = false;
}

// ==================== 强制开启全能力 ====================
void force_god_mode(jvmtiEnv* jvmti) {
    std::cout << "[GodSlayer] Activating God Mode (All Capabilities)..." << std::endl;

    unsigned char god_mode_data[128];
    memset(god_mode_data, 0xFF, sizeof(god_mode_data));

    unsigned char* mem = (unsigned char*)jvmti;
    bool success = false;

    for (int offset = 360; offset < 400; offset++) {
        bool is_zero_region = true;
        for (int k = 0; k < sizeof(jvmtiCapabilities); k++) {
            if (mem[offset + k] != 0) { is_zero_region = false; break; }
        }
        if (!is_zero_region) continue;

        unsigned char backup[128];
        memcpy(backup, mem + offset, sizeof(jvmtiCapabilities));
        memcpy(mem + offset, god_mode_data, sizeof(jvmtiCapabilities));

        jvmtiCapabilities check;
        memset(&check, 0, sizeof(check));
        jvmtiError err = jvmti->GetCapabilities(&check);

        if (err == JVMTI_ERROR_NONE && check.can_tag_objects == 1) {
            std::cout << "[GodSlayer] SUCCESS! God Mode activated at offset: " << offset << std::endl;
            if (check.can_retransform_classes == 1 && check.can_generate_all_class_hook_events == 1) {
                std::cout << "[GodSlayer] Verified: Retransform and System Hooks enabled." << std::endl;
                success = true;
                break;
            }
        } else {
            memcpy(mem + offset, backup, sizeof(jvmtiCapabilities));
        }
    }

    if (!success) {
        std::cerr << "[GodSlayer] FATAL: Could not patch capabilities." << std::endl;
    }
}

// ==================== 设置事件回调 ====================
void setup_event_callbacks(jvmtiEnv* jvmti) {
    std::cout << "[GodSlayer] Setting up JVMTI event callbacks..." << std::endl;
    
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = &classFileLoadHookHandler; // 注册我们上面写的完整回调
    
    jvmtiError err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        std::cerr << "[GodSlayer] FATAL: SetEventCallbacks failed: " << err << std::endl;
        return;
    }
    
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL);
    if (err != JVMTI_ERROR_NONE) {
        std::cerr << "[GodSlayer] FATAL: SetEventNotificationMode failed: " << err << std::endl;
    } else {
        std::cout << "[GodSlayer] ✓ ClassFileLoadHook callback registered and enabled." << std::endl;
    }
}

// ==================== JNI 入口 ====================
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    jint res = vm->GetEnv((void**)&global_jvmti, JVMTI_VERSION_1_2);
    if (res != JNI_OK || global_jvmti == nullptr) {
        std::cerr << "[GodSlayer] Failed to get JVMTI Env" << std::endl;
        return JNI_VERSION_1_8;
    }
    
    // 1. 暴力开启全能力
    force_god_mode(global_jvmti);
    
    // 2. 注册包含完整调用链的事件回调
    setup_event_callbacks(global_jvmti);
    
    // 3. 伪造 Agent 结构体
    JPLISAgent* agent = (JPLISAgent*)malloc(sizeof(JPLISAgent));
    if (agent) {
        memset(agent, 0, sizeof(JPLISAgent));
        agent->mJVM = vm;
        agent->mNormalEnvironment.mJVMTIEnv = global_jvmti;
        agent->mNormalEnvironment.mAgent = agent;
        agent->mNormalEnvironment.mIsRetransformer = JNI_FALSE;

        // Retransform 环境必须为 TRUE，对应 canRetransform=true
        agent->mRetransformEnvironment.mJVMTIEnv = global_jvmti;
        agent->mRetransformEnvironment.mAgent = agent;
        agent->mRetransformEnvironment.mIsRetransformer = JNI_TRUE;

        agent->mRedefineAvailable = JNI_TRUE;
        fake_agent_ptr = agent;
        std::cout << "[GodSlayer] Agent structure initialized." << std::endl;
    }

    return JNI_VERSION_1_8;
}

// ==================== Java 层调用的初始化方法 ====================
extern "C" JNIEXPORT jobject JNICALL Java_com_godslayer_core_EarlyNativeBridge_initializePower(JNIEnv* env, jclass clazz) {
    if (!global_jvmti || !fake_agent_ptr) {
        std::cerr << "[GodSlayer] Native not initialized." << std::endl;
        return nullptr;
    }

    const char* className = "sun/instrument/InstrumentationImpl";
    jclass implClass = env->FindClass(className);
    if (!implClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        std::cerr << "[GodSlayer] Class not found: " << className << std::endl;
        return nullptr;
    }

    jmethodID ctor = env->GetMethodID(implClass, "<init>", "(JZZ)V");
    if (!ctor) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        std::cerr << "[GodSlayer] Constructor not found." << std::endl;
        return nullptr;
    }

    jvalue args[3];
    args[0].j = ptr_to_jlong(fake_agent_ptr); // 指向我们的 Fake Agent
    args[1].z = JNI_TRUE;                      // environmentSupportsRedefineClasses
    args[2].z = JNI_TRUE;                      // environmentSupportsRetransformClasses

    jobject instObj = env->NewObjectA(implClass, ctor, args);

    if (env->ExceptionCheck() || !instObj) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        std::cerr << "[GodSlayer] Failed to create object." << std::endl;
        return nullptr;
    }

    // ★ 缓存 Instrumentation 实例和 transform 方法ID，供 C 层回调使用 ★
    g_instrument_impl_ref = env->NewGlobalRef(instObj);
    g_transform_mid = env->GetMethodID(implClass, "transform", 
        "(Ljava/lang/Module;Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;Ljava/security/ProtectionDomain;[BZ)[B");
    
    if (!g_transform_mid) {
        env->ExceptionClear();
        // 兼容旧版签名（无 Module 参数的情况，JDK 8 及以下）
        g_transform_mid = env->GetMethodID(implClass, "transform", 
            "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;Ljava/security/ProtectionDomain;[BZ)[B");
    }

    if (!g_transform_mid) {
        std::cerr << "[GodSlayer] FATAL: Could not cache transform method ID!" << std::endl;
    } else {
        std::cout << "[GodSlayer] ✓ Transform methodID cached successfully." << std::endl;
    }

    std::cout << "[GodSlayer] Instrumentation object created successfully." << std::endl;
    return instObj;
}

// ==================== 供 Java 层添加类名过滤的方法 ====================
extern "C" JNIEXPORT void JNICALL Java_com_godslayer_GodSlayerNative_addTransformFilter(JNIEnv* env, jclass clazz, jstring name) {
    if (name == nullptr) return;
    const char* utf = env->GetStringUTFChars(name, nullptr);
    if (utf) {
        g_transform_filter.insert(utf);
        std::cout << "[GodSlayer] Filter added: " << utf << std::endl;
        env->ReleaseStringUTFChars(name, utf);
    }
}
