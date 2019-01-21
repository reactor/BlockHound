/*
 * Copyright (c) 2018-2019 Pivotal Software Inc, All Rights Reserved.
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

    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL Java_reactor_BlockHoundRuntime_markMethod(JNIEnv *env, jobject, jclass clazz, jstring hookMethodName, jboolean allowed) {
    const char *hookMethodChars = env->GetStringUTFChars(hookMethodName, JNI_FALSE);

    jint methodCount;
    jmethodID *methodIds;
    jvmti->GetClassMethods(clazz, &methodCount, &methodIds);

    for (int i = 0; i < methodCount; i++) {
        jmethodID methodId = methodIds[i];

        char *methodName;
        jvmti->GetMethodName(methodId, &methodName, NULL, NULL);

        if (strcmp(methodName, hookMethodChars) == 0) {
            BlockingStackElement el = {};
            el.allowed = allowed == JNI_TRUE;
            hooks[methodId] = el;
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_reactor_BlockHoundRuntime_isBlocking(JNIEnv *env) {
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

        // Since we call back into Java code, it might call a blocking method.
        // However, since "isNonBlocking" is false by default,
        // it should be safe and not create a recursion problem.
        jclass runtimeClass = env->FindClass("Lreactor/BlockHoundRuntime;");
        jmethodID isBlockingThreadMethodId = env->GetStaticMethodID(runtimeClass, "isBlockingThread", "(Ljava/lang/Thread;)Z");
        t->isNonBlocking = env->CallStaticBooleanMethod(runtimeClass, isBlockingThreadMethodId, thread) == JNI_TRUE;
    }

    if (!t->isNonBlocking) {
        return JNI_FALSE;
    }

    const jint page_size = 32;
    jint start_depth = 1; // Skip current method
    jint frames_count = -1;
    jvmtiFrameInfo frames[page_size];

    do {
        jvmti->GetStackTrace(thread, start_depth, page_size, frames, &frames_count);

        for (int i = 0; i < frames_count; i++) {
            jmethodID methodId = frames[i].method;
            if (!methodId) {
                continue;
            }

            std::unordered_map<jmethodID, BlockingStackElement>::iterator hookIterator = hooks.find(methodId);
            if (hookIterator != hooks.end()) {
                BlockingStackElement hook = hookIterator++->second;
                return hook.allowed ? JNI_FALSE : JNI_TRUE;
            }
        }
        start_depth += page_size;
    } while (frames_count == page_size);

    return JNI_FALSE;
}