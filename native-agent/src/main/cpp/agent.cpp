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
#include "jni.h"
#include "jvmti.h"

#include <cstddef>
#include <cstring>
#include <unordered_map>

struct BlockingStackElement {
    bool allowed;
};

typedef struct ThreadTag {
    bool isNonBlocking;
} ThreadTag;

static std::unordered_map<jmethodID, BlockingStackElement> hooks;

static jvmtiEnv *jvmti;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *) {
    jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_2);

    jvmtiCapabilities capabilities = {};
    capabilities.can_tag_objects = 1;
    jvmti->AddCapabilities(&capabilities);

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL Java_reactor_BlockHoundRuntime_markMethod(JNIEnv *env, jobject, jclass clazz, jstring hookMethodName, jboolean allowed) {
    auto hookMethodChars = env->GetStringUTFChars(hookMethodName, JNI_FALSE);

    jint methodCount;
    jmethodID *methodIds;
    jvmti->GetClassMethods(clazz, &methodCount, &methodIds);

    for (int i = 0; i < methodCount; i++) {
        auto methodId = methodIds[i];

        char *methodName;
        jvmti->GetMethodName(methodId, &methodName, NULL, NULL);

        if (strcmp(methodName, hookMethodChars) == 0) {
            BlockingStackElement el = {};
            el.allowed = allowed == JNI_TRUE;
            hooks[methodId] = el;
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_reactor_BlockHoundRuntime_hook(JNIEnv *env) {
    jthread thread;
    jvmti->GetCurrentThread(&thread);

    ThreadTag *t = NULL;
    jlong tag;
    jvmti->GetTag(thread, &tag);
    if (tag) {
        t = (ThreadTag *) (ptrdiff_t) tag;
    }

    if (!t) {
        t = new ThreadTag();
        jvmti->SetTag(thread, (ptrdiff_t) (void *) t);

        jint interface_count;
        jclass *classIds;
        jvmti->GetImplementedInterfaces(env->GetObjectClass(thread), &interface_count, &classIds);

        for (int i = 0; i < interface_count; i++) {
            char *interfaceName;
            jvmti->GetClassSignature(classIds[i], &interfaceName, NULL);

            if (strcmp(interfaceName, "Lreactor/core/scheduler/NonBlocking;") == 0) {
                t->isNonBlocking = true;
                break;
            }
        }
    }

    if (!t->isNonBlocking) {
        return JNI_FALSE;
    }

    jint frames_count = -1;
    jvmtiFrameInfo frames[128];
    jvmti->GetStackTrace(thread, 0, 128, frames, &frames_count);

    bool allowed = true;
    for (int i = 1; i < frames_count; i++) {
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
                return JNI_FALSE;
            } else {
                allowed = false;
            }
        }
    }

    return static_cast<jboolean>(!allowed);
}