/*
 * Copyright (c) 2018-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.blockhound;

import java.util.function.Consumer;
import java.util.function.Predicate;

// Warning!!! This class MUST NOT be loaded by any classloader other than the bootstrap one.
// Otherwise, non-bootstrap classes will be referring to it, but only the bootstrap one gets
// initialized and linked to the native library.
public class BlockHoundRuntime {

    @SuppressWarnings("unused")
    public static void init(String nativeLibraryFileName) {
        System.load(nativeLibraryFileName);
    }

    @SuppressWarnings("unused")
    public static native void markMethod(Class clazz, String methodName, boolean allowed);

    private static native boolean isBlocking();

    @SuppressWarnings("unused")
    private static volatile Consumer<Object[]> blockingMethodConsumer;

    @SuppressWarnings("unused")
    private static volatile Predicate<Thread> threadPredicate;

    private static final ThreadLocal<Boolean> IS_NON_BLOCKING = ThreadLocal.withInitial(() -> {
        return threadPredicate.test(Thread.currentThread());
    });

    @SuppressWarnings("unused")
    public static void checkBlocking(String className, String methodName, int modifiers) {
        if (!IS_NON_BLOCKING.get()) {
            return;
        }
        if (isBlocking()) {
            blockingMethodConsumer.accept(new Object[] { className, methodName, modifiers });
        }
    }
}
