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
// 直接复制 OpenJDK 源码结构体定义
// ==========================================

struct _JPLISEnvironment {
    jvmtiEnv *      mJVMTIEnv;              /* the JVM TI environment */
    struct _JPLISAgent *    mAgent;                 /* corresponding agent */
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

// 类型别名，方便使用
typedef struct _JPLISAgent JPLISAgent;
typedef struct _JPLISEnvironment JPLISEnvironment;

// ==========================================
// 能力修改逻辑
// ==========================================
void force_enable_capabilities(jvmtiEnv* jvmti) {
    std::cout << "[GodSlayer] Patching capabilities..." << std::endl;

    jvmtiCapabilities target_caps;
    memset(&target_caps, 0, sizeof(target_caps));
    target_caps.can_redefine_classes = 1;
    target_caps.can_retransform_classes = 1;
    target_caps.can_set_native_method_prefix = 1;

    unsigned char probe_data[128]; 
    memset(probe_data, 0xFF, sizeof(probe_data));

    unsigned char* mem = (unsigned char*)jvmti;
    bool success = false;

    // 搜索能力字段
    for (int offset = 360; offset < 400; offset++) {
        bool is_zero_region = true;
        for (int k = 0; k < sizeof(jvmtiCapabilities); k++) {
            if (mem[offset + k] != 0) { is_zero_region = false; break; }
        }
        if (!is_zero_region) continue;

        unsigned char backup[128];
        memcpy(backup, mem + offset, sizeof(target_caps));
        
        memcpy(mem + offset, probe_data, sizeof(target_caps));
        jvmtiCapabilities check;
        memset(&check, 0, sizeof(check));
        jvmtiError err = jvmti->GetCapabilities(&check);

        if (err == JVMTI_ERROR_NONE && check.can_tag_objects == 1) {
            memcpy(mem + offset, &target_caps, sizeof(target_caps));
            std::cout << "[GodSlayer] Capabilities patched at offset: " << offset << std::endl;
            success = true;
            break;
        } else {
            memcpy(mem + offset, backup, sizeof(target_caps));
        }
    }
    if(!success) std::cerr << "[GodSlayer] Patch failed." << std::endl;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    jint res = vm->GetEnv((void**)&global_jvmti, JVMTI_VERSION_1_2);
    if (res != JNI_OK || global_jvmti == nullptr) return JNI_VERSION_1_8;
    
    force_enable_capabilities(global_jvmti);

    // 1. 分配准确的源码结构体
    JPLISAgent* agent = (JPLISAgent*)malloc(sizeof(JPLISAgent));
    memset(agent, 0, sizeof(JPLISAgent));

    // 2. 填充 mJVM
    agent->mJVM = vm;

    // 3. 填充 mNormalEnvironment (用于 Redefine)
    agent->mNormalEnvironment.mJVMTIEnv = global_jvmti;
    agent->mNormalEnvironment.mAgent = agent; // 自引用
    agent->mNormalEnvironment.mIsRetransformer = JNI_FALSE; 

    // 4. 填充 mRetransformEnvironment (用于 Retransform) - 关键！
    // 偏移量应该是 32，但编译器会自动处理，我们直接赋值
    agent->mRetransformEnvironment.mJVMTIEnv = global_jvmti;
    agent->mRetransformEnvironment.mAgent = agent; // 自引用
    agent->mRetransformEnvironment.mIsRetransformer = JNI_TRUE; // 标记为 Retransform 环境

    // 5. 设置可用性标志位
    agent->mRedefineAvailable = JNI_TRUE;
    // 注意：源码结构体里没有 mRetransformSupported，那个是 JPLISAgent 内部缓存的
    // instrument.dll 会根据 mRetransformEnvironment.mIsRetransformer 来判断
    
    fake_agent_ptr = agent;
    
    std::cout << "[GodSlayer] JPLISAgent constructed with OpenJDK layout." << std::endl;
    std::cout << "[GodSlayer] Retransform Env Offset: " << (long long)(&(agent->mRetransformEnvironment)) - (long long)agent<< std::endl;

    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_godslayer_GodSlayerNative_initializePower(JNIEnv* env, jclass clazz) {
    if (!global_jvmti || !fake_agent_ptr) return nullptr;

    jclass implClass = env->FindClass("sun/instrument/InstrumentationImpl");
    if (!implClass) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jmethodID ctor = env->GetMethodID(implClass, "<init>", "(JZZ)V");
    if (!ctor) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    // 使用 NewObjectA 稳定传参
    jvalue args[3];
    args[0].j = ptr_to_jlong(fake_agent_ptr); 
    args[1].z = JNI_TRUE; // environmentSupportsRedefineClasses
    args[2].z = JNI_TRUE; // environmentSupportsRetransformClasses

    jobject instObj = env->NewObjectA(implClass, ctor, args);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }

    std::cout << "[GodSlayer] Instrumentation object created." << std::endl;
    return instObj;
}
