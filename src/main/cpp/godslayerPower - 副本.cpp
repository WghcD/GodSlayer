/*
* godslayerPower_final.cpp
*
* 上帝模式：无需 -javaagent，在普通 JNI 环境下强行构造全能力 Instrumentation 对象。
* 支持 JDK 17+ HotSpot (Windows x64)。
*
* 编译指令 (MSVC):
* cl /LD /EHsc godslayerPower_final.cpp /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /link jvm.lib
*/


#include <Windows.h>
#include <stdio.h>
#include <stdint.h>
#include <jni.h>
#include <jvmti.h>
#include <string>

static char g_DebugBuf[1024];
#define LOG_DEBUG(fmt, ...) \
    snprintf(g_DebugBuf, sizeof(g_DebugBuf), "[GodSlayer] " fmt "\n", ##__VA_ARGS__); \
    OutputDebugStringA(g_DebugBuf)

static JavaVM* g_jvm = NULL;
static jvmtiEnv* g_jvmti = NULL;
static intptr_t g_fakeAgentAddress = 0;

/* JPLISAgent 结构体模拟 */
typedef struct {
    JavaVM* mJVM;
    jvmtiEnv* mNormalEnvironment;
    jvmtiEnv* mRetransformEnvironment;
    jboolean mRedefineAdded;
    jboolean mNativeMethodPrefixAdded;
    jboolean mHasTransformers;
    jboolean mHasRetransformableTransformers;
    jboolean mRetransformEnvironmentSet;
} FakeJPLISAgent;

static_assert(sizeof(FakeJPLISAgent) >= 32, "Size check");

// ================== 核心逻辑：能力提升 ==================

static bool grantAllCapabilities(jvmtiEnv* jvmti) {
    jvmtiCapabilities curCaps;
    memset(&curCaps, 0, sizeof(curCaps));
    jvmtiError err = jvmti->GetCapabilities(&curCaps);
    if (err != JVMTI_ERROR_NONE) return false;

    if (curCaps.can_redefine_classes) return true; // 已经拥有权限

    unsigned char* pCurCaps = (unsigned char*)&curCaps;
    char* base = (char*)jvmti;

    for (size_t off = 0; off < 4096; off++) {
        if (off % 8 != 0) continue;

        if (memcmp(base + off, pCurCaps, sizeof(curCaps)) == 0) {
            // 备份
            char backup[sizeof(curCaps)];
            memcpy(backup, base + off, sizeof(backup));

            // 尝试开启 can_redefine_classes
            jvmtiCapabilities testCaps = curCaps;
            testCaps.can_redefine_classes = 1;
            memcpy(base + off, &testCaps, sizeof(testCaps));

            jboolean isMod;
            jvmtiError testErr = jvmti->IsModifiableClass(NULL, &isMod);

            // 恢复
            memcpy(base + off, backup, sizeof(backup));

            if (testErr != JVMTI_ERROR_MUST_POSSESS_CAPABILITY) {
                // 验证成功，永久开启所有能力
                memset(&curCaps, 0xFF, sizeof(curCaps));
                memcpy(base + off, &curCaps, sizeof(curCaps));
                LOG_DEBUG("Capabilities granted at offset 0x%llx", (unsigned long long)off);
                return true;
            }
        }
    }
    return false;
}

// ================== 辅助逻辑：强制加载 Instrument 库 ==================
// 使用 Win32 API 加载，避免 Java ClassLoader 冲突

static bool ensureInstrumentLibraryLoaded(JNIEnv* env) {
    // 1. 检查是否已经在 JVM 进程中加载
    if (GetModuleHandleA("instrument.dll") != NULL) {
        LOG_DEBUG("instrument.dll already in process memory.");
        return true;
    }

    // 2. 获取 java.home 路径
    jclass sysCls = env->FindClass("java/lang/System");
    if (!sysCls) return false;
    jmethodID getProperty = env->GetStaticMethodID(sysCls, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring homeKey = env->NewStringUTF("java.home");
    jstring javaHome = (jstring)env->CallStaticObjectMethod(sysCls, getProperty, homeKey);
    
    if (!javaHome) return false;
    const char* homeStr = env->GetStringUTFChars(javaHome, NULL);
    std::string path(homeStr);
    env->ReleaseStringUTFChars(javaHome, homeStr);

    // 3. 拼接路径
    path.append("\\bin\\instrument.dll");

    LOG_DEBUG("Loading library via Win32: %s", path.c_str());

    // 4. 使用 Win32 API 加载
    HMODULE hMod = LoadLibraryA(path.c_str());
    if (hMod == NULL) {
        DWORD err = GetLastError();
        LOG_DEBUG("LoadLibraryA failed with error: %lu", err);
        return false;
    }

    LOG_DEBUG("instrument.dll loaded successfully via OS.");
    return true;
}

// ================== 入口函数 ==================

extern "C" JNIEXPORT jobject JNICALL Java_com_godslayer_GodSlayerNative_initializePower(JNIEnv* env, jclass clazz) {
    if (!g_jvm) {
        JavaVM* vms[1];
        jint num;
        if (JNI_GetCreatedJavaVMs(&vms[0], 1, &num) != JNI_OK || num == 0) return NULL;
        g_jvm = vms[0];
    }

    if (!g_jvmti) {
        g_jvm->GetEnv((void**)&g_jvmti, JVMTI_VERSION_1_2);
        if (!g_jvmti) return NULL;
        if (!grantAllCapabilities(g_jvmti)) {
            LOG_DEBUG("Failed to grant capabilities.");
            return NULL;
        }
    }

    // 强制加载 instrument 库
    if (!ensureInstrumentLibraryLoaded(env)) {
        LOG_DEBUG("Failed to load instrument library.");
        // 不返回NULL，尝试继续，也许已经在PATH里
    }

    // 构造 Fake Agent
    if (!g_fakeAgentAddress) {
        FakeJPLISAgent* agent = (FakeJPLISAgent*)malloc(sizeof(FakeJPLISAgent));
        memset(agent, 0, sizeof(FakeJPLISAgent));
        agent->mJVM = g_jvm;
        agent->mNormalEnvironment = g_jvmti;
        agent->mRetransformEnvironment = g_jvmti;
        agent->mRedefineAdded = JNI_TRUE;
        g_fakeAgentAddress = (intptr_t)agent;
    }

    // 构造 Java 对象
    jclass instCls = env->FindClass("sun/instrument/InstrumentationImpl");
    if (!instCls) {
        LOG_DEBUG("Class InstrumentationImpl not found.");
        return NULL;
    }

    // 尝试直接调用构造器
    jmethodID ctor = env->GetMethodID(instCls, "<init>", "(JZZZ)V");
    
    jobject instrumentation = NULL;

    if (ctor && !env->ExceptionCheck()) {
        instrumentation = env->NewObject(instCls, ctor, (jlong)g_fakeAgentAddress, JNI_TRUE, JNI_TRUE, JNI_TRUE);
    }

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOG_DEBUG("Constructor failed (AccessDenied?), trying Unsafe fallback...");

        // 回退方案：使用 Unsafe 绕过构造器
        jclass unsafeCls = env->FindClass("sun/misc/Unsafe"); // JDK 8 位置，JDK 17 在 jdk/internal/misc/Unsafe
        if (!unsafeCls) {
             unsafeCls = env->FindClass("jdk/internal/misc/Unsafe");
        }
        
        if (unsafeCls) {
            // 获取 Unsafe 实例
            jfieldID theUnsafeField = env->GetStaticFieldID(unsafeCls, "theUnsafe", "Lsun/misc/Unsafe;");
            if (!theUnsafeField) theUnsafeField = env->GetStaticFieldID(unsafeCls, "theUnsafe", "Ljdk/internal/misc/Unsafe;");
            
            if (theUnsafeField) {
                jobject unsafeObj = env->GetStaticObjectField(unsafeCls, theUnsafeField);
                if (unsafeObj) {
                    // 分配对象实例，不调用构造函数
                    jmethodID allocMID = env->GetMethodID(unsafeCls, "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;");
                    if (allocMID) {
                        instrumentation = env->CallObjectMethod(unsafeObj, allocMID, instCls);
                        
                        if (instrumentation) {
                            // 手动设置 agent 字段 (假设偏移量，通常第一个 long 字段在 header 后，即偏移 16)
                            // 简单起见，我们直接用反射设置字段，Unsafe 的 putObject 更稳
                            // 这里我们利用 jvmti 能够写入内存的能力，或者直接用 unsafe.putLong
                            jmethodID putLongMID = env->GetMethodID(unsafeCls, "putLong", "(Ljava/lang/Object;JJ)V");
                            
                            // 获取字段偏移
                            jmethodID objectFieldOffsetMID = env->GetMethodID(unsafeCls, "objectFieldOffset", "(Ljava/lang/reflect/Field;)J");
                            
                            // 我们需要获取 agent 字段的 Field 对象
                            jclass fieldCls = env->FindClass("java/lang/reflect/Field");
                            jmethodID getFieldMID = env->GetMethodID(fieldCls, "getInt", "(Ljava/lang/Object;)I"); // 占位，实际上我们要用 DeclaredField
                            
                            // 简化方案：直接计算内存偏移写入
                            // InstrumentationImpl 的 agent 字段是第一个 long 字段。
                            // 对象头 12字节 + 4字节padding = 16字节偏移
                            jlong fieldOffset = 16; 
                            
                            env->CallVoidMethod(unsafeObj, putLongMID, instrumentation, fieldOffset, (jlong)g_fakeAgentAddress);
                            LOG_DEBUG("Object created via Unsafe fallback.");
                        }
                    }
                }
            }
        }
    }

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return NULL;
    }

    return instrumentation;
}

