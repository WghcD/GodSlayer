#include <jni.h>
#include <jvmti.h>
#include <string.h>
#include <iostream>
#include <stdlib.h>

static jvmtiEnv* global_jvmti = nullptr;
static void* fake_agent_ptr = nullptr;

static jlong ptr_to_jlong(void* ptr) {
    return (jlong)(intptr_t)ptr;
}

// ==========================================
// OpenJDK 源码结构体定义
// ==========================================
struct _JPLISEnvironment {
    jvmtiEnv *      mJVMTIEnv;              /* the JVM TI environment */
    struct _JPLISAgent *    mAgent;         /* corresponding agent */
    jboolean        mIsRetransformer;       /* indicates if special environment */
};

struct _JPLISAgent {
    JavaVM *            mJVM;                       /* handle to the JVM */
    struct _JPLISEnvironment    mNormalEnvironment;         /* for every thing but retransform stuff */
    struct _JPLISEnvironment    mRetransformEnvironment;    /* for retransform stuff only */
    jobject             mInstrumentationImpl;       /* handle to the Instrumentation instance */
    jmethodID           mPremainCaller;             /* method on the InstrumentationImpl that does the premain stuff */
    jmethodID           mAgentmainCaller;           /* method on the InstrumentationImpl for agents loaded via attach mechanism */
    jmethodID           mTransform;                 /* method on the InstrumentationImpl that does the class file transform */
    jboolean            mRedefineAvailable;         /* cached answer to "does this agent support redefine" */
    jboolean            mRedefineAdded;             /* indicates if can_redefine_classes capability has been added */
    jboolean            mNativeMethodPrefixAvailable; /* cached answer to "does this agent support prefixing" */
    jboolean            mNativeMethodPrefixAdded;   /* indicates if can_set_native_method_prefix capability has been added */
    char const *        mAgentClassName;            /* agent class name */
    char const *        mOptionsString;             /* -javaagent options string */
    const char *        mJarfile;                   /* agent jar file name */
};


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
    // 这个回调函数是必需的，但实际的转换逻辑由Java层的Transformer处理
    // 这里我们不需要做任何事，只需要确保回调存在
    // JVMTI会遍历所有注册了此事件的环境
}

typedef struct _JPLISAgent JPLISAgent;
typedef struct _JPLISEnvironment JPLISEnvironment;

// ==========================================
// 强制开启所有能力
// ==========================================
void force_god_mode(jvmtiEnv* jvmti) {
    std::cout << "[GodSlayer] Activating God Mode (All Capabilities)..." << std::endl;

    // 1. 准备全 0xFF 数据 (所有位设为 1)
    // 这将开启包括 can_generate_all_class_hook_events 在内的所有功能
    unsigned char god_mode_data[128];
    memset(god_mode_data, 0xFF, sizeof(god_mode_data));

    unsigned char* mem = (unsigned char*)jvmti;
    bool success = false;

    // 2. 搜索内存并注入
    // 使用之前已验证有效的搜索范围
    for (int offset = 360; offset < 400; offset++) {
        // 简单检查：通常能力字段初始化为 0
        bool is_zero_region = true;
        for (int k = 0; k < sizeof(jvmtiCapabilities); k++) {
            if (mem[offset + k] != 0) { is_zero_region = false; break; }
        }
        if (!is_zero_region) continue;

        // 备份现场
        unsigned char backup[128];
        memcpy(backup, mem + offset, sizeof(jvmtiCapabilities));

        // 尝试写入全能力
        memcpy(mem + offset, god_mode_data, sizeof(jvmtiCapabilities));

        // 验证是否生效
        jvmtiCapabilities check;
        memset(&check, 0, sizeof(check));
        jvmtiError err = jvmti->GetCapabilities(&check);

        // 只要读到了非零值，说明找到了能力字段
        // (can_tag_objects 是第一个位，如果是 1 就代表写入成功)
        if (err == JVMTI_ERROR_NONE && check.can_tag_objects == 1) {
            std::cout << "[GodSlayer] SUCCESS! God Mode activated at offset: " << offset << std::endl;
            
            // 再次确认关键能力
            if (check.can_retransform_classes == 1 && check.can_generate_all_class_hook_events == 1) {
                std::cout << "[GodSlayer] Verified: Retransform and System Hooks enabled." << std::endl;
                success = true;
                break;
            }
        } else {
            // 失败则恢复，继续寻找
            memcpy(mem + offset, backup, sizeof(jvmtiCapabilities));
        }
    }

    if (!success) {
        std::cerr << "[GodSlayer] FATAL: Could not patch capabilities." << std::endl;
    }
}

void setup_event_callbacks(jvmtiEnv* jvmti) {
    std::cout << "[GodSlayer] Setting up JVMTI event callbacks..." << std::endl;
    
    // 关键：注册 ClassFileLoadHook 回调
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = &classFileLoadHookHandler;
    
    jvmtiError err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        std::cerr << "[GodSlayer] FATAL: SetEventCallbacks failed: " << err << std::endl;
        return;
    }
    
    // 启用 ClassFileLoadHook 事件通知
    err = jvmti->SetEventNotificationMode(
        JVMTI_ENABLE, 
        JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, 
        NULL
    );
    
    if (err != JVMTI_ERROR_NONE) {
        std::cerr << "[GodSlayer] FATAL: SetEventNotificationMode failed: " << err << std::endl;
    } else {
        std::cout << "[GodSlayer] ClassFileLoadHook callback registered and enabled." << std::endl;
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    // 获取 JVMTI 环境
    jint res = vm->GetEnv((void**)&global_jvmti, JVMTI_VERSION_1_2);
    if (res != JNI_OK || global_jvmti == nullptr) {
        std::cerr << "[GodSlayer] Failed to get JVMTI Env" << std::endl;
        return JNI_VERSION_1_8;
    }
    
    // 开启全能力
    force_god_mode(global_jvmti);

    // 构造伪造的 Agent 结构体
    JPLISAgent* agent = (JPLISAgent*)malloc(sizeof(JPLISAgent));
    if (agent) {
        memset(agent, 0, sizeof(JPLISAgent));

        agent->mJVM = vm;

        // Normal Environment (用于 Redefine)
        agent->mNormalEnvironment.mJVMTIEnv = global_jvmti;
        agent->mNormalEnvironment.mAgent = agent;
        agent->mNormalEnvironment.mIsRetransformer = JNI_FALSE;

        // Retransform Environment (用于 Retransform, 核心关键)
        agent->mRetransformEnvironment.mJVMTIEnv = global_jvmti;
        agent->mRetransformEnvironment.mAgent = agent;
        agent->mRetransformEnvironment.mIsRetransformer = JNI_TRUE; // 必须为 TRUE

        // 设置 Agent 端的可用性标志
        agent->mRedefineAvailable = JNI_TRUE;
        
        fake_agent_ptr = agent;
        std::cout << "[GodSlayer] Agent structure initialized." << std::endl;
    }
	
	setup_event_callbacks(global_jvmti);//New

    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_godslayer_GodSlayerNative_initializePower(JNIEnv* env, jclass clazz) {
    if (!global_jvmti || !fake_agent_ptr) {
        std::cerr << "[GodSlayer] Native not initialized." << std::endl;
        return nullptr;
    }

    // 查找 Java 类
    const char* className = "sun/instrument/InstrumentationImpl";
    jclass implClass = env->FindClass(className);
    if (!implClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        std::cerr << "[GodSlayer] Class not found: " << className << std::endl;
        return nullptr;
    }

    // 获取构造方法 ID
    jmethodID ctor = env->GetMethodID(implClass, "<init>", "(JZZ)V");
    if (!ctor) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        std::cerr << "[GodSlayer] Constructor not found." << std::endl;
        return nullptr;
    }

    // 准备参数
    jvalue args[3];
    args[0].j = ptr_to_jlong(fake_agent_ptr); // 指向我们的 Fake Agent
    args[1].z = JNI_TRUE;                      // environmentSupportsRedefineClasses
    args[2].z = JNI_TRUE;                      // environmentSupportsRetransformClasses

    // 实例化对象
    jobject instObj = env->NewObjectA(implClass, ctor, args);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        std::cerr << "[GodSlayer] Failed to create object." << std::endl;
        return nullptr;
    }

    std::cout << "[GodSlayer] Instrumentation object created successfully." << std::endl;
    return instObj;
}
