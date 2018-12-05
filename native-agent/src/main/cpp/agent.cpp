/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "jvmti.h"

#include <cstddef>
#include <cstring>
#include <map>
#include <unordered_map>
#include <set>
#include <vector>

struct BlockingStackElement {
    char *declaringClassName;
    char *methodName;
    bool allowed;
};

typedef struct ThreadTag {
    bool isNonBlocking;
} ThreadTag;

struct cmp_str {
    bool operator()(char const *a, char const *b) const {
        return std::strcmp(a, b) < 0;
    }
};

static std::map<char *, std::set<char *, cmp_str>, cmp_str> breakpoints;
static std::map<char *, std::map<char *, bool, cmp_str>, cmp_str> hookMethods;

static std::unordered_map<jmethodID, BlockingStackElement> hooks;
static std::unordered_map<jmethodID, void *> originalMethods;
static std::unordered_map<jmethodID, void *> replacements;


static jvmtiEnv *jvmti;

static char *fixClassName(char *className) {
    // Strip 'L' and ';' from class signature
    className[strlen(className) - 1] = 0;

    for (int i = 0; i < strlen(className); i++) {
        if (className[i] == '/') {
            className[i] = '.';
        }
    }
    return className + 1;
}

static void JNICALL callbackThreadStartEvent(jvmtiEnv *jvmti, JNIEnv *env, jthread thread) {
    auto threadClass = env->GetObjectClass(thread);

    char *threadClassName;
    jvmti->GetClassSignature(threadClass, &threadClassName, NULL);

    jint interface_count;
    jclass *classIds;
    jvmti->GetImplementedInterfaces(threadClass, &interface_count, &classIds);

    for (int i = 0; i < interface_count; i++) {
        char *interfaceName;
        jvmti->GetClassSignature(classIds[i], &interfaceName, NULL);

        if (strcmp(interfaceName, "Lreactor/core/scheduler/NonBlocking;") == 0) {
            auto t = new ThreadTag();
            t->isNonBlocking = true;
            jvmti->SetTag(thread, (ptrdiff_t) (void *) t);
            break;
        }
    }
}

static void JNICALL callbackClassPrepareEvent(jvmtiEnv *jvmti, JNIEnv *env, jthread thread, jclass klass) {
    char *className;
    jvmti->GetClassSignature(klass, &className, NULL);

    auto hookMethodsIterator = hookMethods.find(className);
    if (hookMethodsIterator != hookMethods.end()) {
        jint method_count;
        jmethodID *methodIds;
        if (jvmti->GetClassMethods(klass, &method_count, &methodIds) == JVMTI_ERROR_CLASS_NOT_PREPARED) {
            return;
        }

        auto methods = hookMethodsIterator++->second;

        for (int i = 0; i < method_count; i++) {
            char *methodName;
            char *sig, *gsig;
            auto methodId = methodIds[i];
            jvmti->GetMethodName(methodId, &methodName, &sig, &gsig);

            auto methodIterator = methods.find(methodName);
            if (methodIterator == methods.end()) {
                continue;
            }

            BlockingStackElement el = {
                    declaringClassName : className,
                    methodName : methodName,
                    allowed : methodIterator++->second
            };
            hooks[methodId] = el;
        }
        return;
    }

    auto blockingClassIterator = breakpoints.find(className);
    if (blockingClassIterator != breakpoints.end()) {
        auto blockingClassMethods = blockingClassIterator++->second;

        jint method_count;
        jmethodID *methodIds;
        if (jvmti->GetClassMethods(klass, &method_count, &methodIds) == JVMTI_ERROR_CLASS_NOT_PREPARED) {
            return;
        }

        for (int i = 0; i < method_count; i++) {
            char *methodName;
            char *sig, *gsig;
            auto methodId = methodIds[i];
            jvmti->GetMethodName(methodId, &methodName, &sig, &gsig);

            auto methodIterator = blockingClassMethods.find(methodName);
            if (methodIterator != blockingClassMethods.end()) {
                jint methodModifiers;
                jvmti->GetMethodModifiers(methodId, &methodModifiers);

                jlocation start_loc;
                jlocation end_loc;
                jvmti->GetMethodLocation(methodId, &start_loc, &end_loc);

                jvmti->SetBreakpoint(methodId, start_loc);
            }
        }
        return;
    }
}

inline static bool isBlockingCall(jvmtiEnv *jvmti, jthread thread) {
    ThreadTag *t = NULL;
    jlong tag;
    jvmti->GetTag(thread, &tag);
    if (tag) {
        t = (ThreadTag *) (ptrdiff_t) tag;
    }

    if (!t || !t->isNonBlocking) {
        return false;
    }

    jint frames_count = -1;
    jvmtiFrameInfo frames[512];
    jvmti->GetStackTrace(thread, 0, 512, frames, &frames_count);

    bool allowed = true;
    for (int i = 0; i < frames_count; i++) {
        auto methodId = frames[i].method;

        jclass methodDeclaringClass;
        jvmti->GetMethodDeclaringClass(methodId, &methodDeclaringClass);

        char *declaringClassName;
        jvmti->GetClassSignature(methodDeclaringClass, &declaringClassName, NULL);

        char *methodName;
        char *sig, *gsig;
        jvmti->GetMethodName(methodId, &methodName, &sig, &gsig);

        auto hookIterator = hooks.find(methodId);
        if (hookIterator != hooks.end()) {
            auto hook = hookIterator++->second;
            if (hook.allowed) {
                return false;
            } else {
                allowed = false;
            }
        }
    }
    return !allowed;
}

inline static bool isBlockingCall(jvmtiEnv *jvmti) {
    jthread thread;
    jvmti->GetCurrentThread(&thread);
    return isBlockingCall(jvmti, thread);
}

inline static void reportBlockingCall(jvmtiEnv *jvmti, JNIEnv *env, jmethodID method) {
    jclass methodDeclaringClass;
    jvmti->GetMethodDeclaringClass(method, &methodDeclaringClass);

    char *declaringClassName;
    jvmti->GetClassSignature(methodDeclaringClass, &declaringClassName, NULL);

    jint methodModifiers;
    jvmti->GetMethodModifiers(method, &methodModifiers);

    char *methodName;
    char *sig, *gsig;
    jvmti->GetMethodName(method, &methodName, &sig, &gsig);

    declaringClassName = fixClassName(declaringClassName);
    char message[15 + strlen(declaringClassName) + 1 + strlen(methodName)];
    strcpy(message, "Blocking call! ");
    strcat(message, declaringClassName);
    strcat(message, ((methodModifiers & 8) == 0) ? "#" : ".");
    strcat(message, methodName);

    // printf("Blocking call detected. %s \n", message);
    env->ThrowNew(env->FindClass("java/lang/Error"), message);
}

static void JNICALL callbackBreakpointEvent(jvmtiEnv *jvmti, JNIEnv *env, jthread thread, jmethodID method, jlocation location) {
    if (isBlockingCall(jvmti, thread)) {
        reportBlockingCall(jvmti, env, method);
    }
}

#define IIF(cond) IIF_ ## cond
#define IIF_false(trueBranch, falseBranch) falseBranch
#define IIF_true(trueBranch, falseBranch) trueBranch

#define WRAP_METHOD(methodName, isStatic, sig, returnType, parameters...) ({ \
    static const auto methodId = env->IIF(isStatic)(GetStaticMethodID, GetMethodID)(clazz, methodName, sig); \
    returnType(*wrapper)(JNIEnv*, jobject, ##parameters) = [](auto env, auto self, auto...params) { \
        if (isBlockingCall(jvmti)) { \
            reportBlockingCall(jvmti, env, methodId); \
        } else { \
            static const auto method = (returnType(*)(JNIEnv*, jobject, ...)) originalMethods[methodId]; \
            return method(env, self, params...); \
        } \
    }; \
    replacements[methodId] = (void *) wrapper; \
    if (originalMethods.find(methodId) != originalMethods.end()) { \
        overrides.push_back({ \
            name : methodName, \
            signature : sig, \
            fnPtr : (void *) wrapper \
        }); \
    } \
})

static void JNICALL callbackNativeMethodBindEvent(jvmtiEnv *, JNIEnv*, jthread, jmethodID method, void* address, void** newAddress) {
    if (originalMethods.find(method) == originalMethods.end()) {
        originalMethods[method] = address;
    }

    auto replacementIterator = replacements.find(method);
    if (replacementIterator != replacements.end()) {
        *newAddress = replacementIterator++->second;
    }
}

void JNICALL callbackVMInitEvent(jvmtiEnv *, JNIEnv* env, jthread thread) {
    int javaVersion;
    jvmti->GetVersionNumber(&javaVersion);
    javaVersion = (javaVersion & JVMTI_VERSION_MASK_MAJOR) >> JVMTI_VERSION_SHIFT_MAJOR;
    // printf("VM (version: %d) initiated\n", javaVersion);

    {
        auto clazz = env->FindClass("java/lang/Thread");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("sleep", true, "(J)V", void, jlong);
        WRAP_METHOD("yield", true, "()V", void);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/lang/Object");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("wait", false, "(J)V", void, jlong);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/io/RandomAccessFile");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("read0", false, "()I", int);
        WRAP_METHOD("readBytes", false, "([BII)I", int, jbyteArray,jint,jint);
        WRAP_METHOD("write0", false, "(I)V", void, jint);
        WRAP_METHOD("writeBytes", false, "([BII)V", void, jbyteArray,jint,jint);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/net/PlainDatagramSocketImpl");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("connect0", false, "(Ljava/net/InetAddress;I)V", void, jobject,jint);
        WRAP_METHOD("peekData", false, "(Ljava/net/DatagramPacket;)I", int, jobject);
        WRAP_METHOD("send", false, "(Ljava/net/DatagramPacket;)V", void, jobject);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/net/PlainSocketImpl");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("socketAccept", false, "(Ljava/net/SocketImpl;)V", void, jobject,jint);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass(javaVersion >= 9 ? "java/lang/ProcessImpl" : "java/lang/UNIXProcess");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("forkAndExec", false, "(I[B[B[BI[BI[B[IZ)I", int, int,jbyteArray,jbyteArray,jbyteArray,int,jbyteArray,int,jbyteArray,jintArray,jboolean);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/net/SocketInputStream");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("socketRead0", false, "(Ljava/io/FileDescriptor;[BIII)I", int, jobject,jbyteArray,jint,jint,jint);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/net/SocketOutputStream");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("socketWrite0", false, "(Ljava/io/FileDescriptor;[BII)V", void, jobject,jbyteArray,jint,jint);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/io/FileInputStream");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("read0", false, "()I", int);
        WRAP_METHOD("readBytes", false, "([BII)I", int, jbyteArray,jint,jint);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass("java/io/FileOutputStream");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("write", false, "(IZ)V", int, jint,jboolean);
        WRAP_METHOD("writeBytes", false, "([BIIZ)V", int, jbyteArray,jint,jint,jboolean);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    {
        auto clazz = env->FindClass(javaVersion >= 9 ? "jdk/internal/misc/Unsafe" : "sun/misc/Unsafe");
        std::vector<JNINativeMethod> overrides;
        WRAP_METHOD("park", false, "(ZJ)V", void, jboolean,jlong);
        env->RegisterNatives(clazz, overrides.data(), overrides.size());
    }

    // printf("Native methods registered\n");
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
    jint result = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_2);

    hookMethods["Lreactor/core/publisher/Flux;"]["subscribe"] = false;
    hookMethods["Lreactor/core/publisher/Flux;"]["onNext"] = false;
    hookMethods["Lreactor/core/publisher/Flux;"]["onError"] = false;
    hookMethods["Lreactor/core/publisher/Flux;"]["onComplete"] = false;

    hookMethods["Lreactor/core/publisher/Mono;"]["subscribe"] = false;
    hookMethods["Lreactor/core/publisher/Mono;"]["onNext"] = false;
    hookMethods["Lreactor/core/publisher/Mono;"]["onError"] = false;
    hookMethods["Lreactor/core/publisher/Mono;"]["onComplete"] = false;

    hookMethods["Lreactor/core/scheduler/Schedulers;"]["workerSchedule"] = true;
    hookMethods["Lreactor/core/scheduler/Schedulers;"]["workerSchedulePeriodically"] = true;

    hookMethods["Ljava/lang/ClassLoader;"]["loadClass"] = true;

    hookMethods["Ljava/security/SecureRandom;"]["nextBytes"] = true;

    hookMethods["Lorg/gradle/internal/io/LineBufferingOutputStream;"]["write"] = true;

    hookMethods["Lch/qos/logback/classic/Logger;"]["callAppenders"] = true;

    //
    breakpoints["Ljava/lang/Thread;"].insert({"onSpinWait"});
    breakpoints["Ljava/net/DatagramSocket;"].insert({"connect"});
    breakpoints["Ljava/net/Socket;"].insert({"connect"});

    if (result != JNI_OK) {
        // printf("\n Unable to access JVMTI");
        return result;
    }

    jvmtiCapabilities capabilities = {
            can_tag_objects : 1,
            can_generate_breakpoint_events : 1,
            can_generate_native_method_bind_events : 1,
    };
    jvmti->AddCapabilities(&capabilities);

    jvmtiEventCallbacks eventCallbacks = {
            ThreadStart : &callbackThreadStartEvent,
            ClassPrepare : &callbackClassPrepareEvent,
            ClassLoad : &callbackClassPrepareEvent,
            Breakpoint : &callbackBreakpointEvent,
            VMInit : &callbackVMInitEvent,
            NativeMethodBind : &callbackNativeMethodBindEvent,
    };

    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, (jthread) NULL);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, (jthread) NULL);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_LOAD, (jthread) NULL);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, (jthread) NULL);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_START, (jthread) NULL);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, (jthread) NULL);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_NATIVE_METHOD_BIND, (jthread) NULL);

    jvmti->SetEventCallbacks(&eventCallbacks, sizeof(eventCallbacks));

    // printf("Initialized!\n");

    return JNI_OK;

}
