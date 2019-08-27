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
// initialized.
public class BlockHoundRuntime {

    @SuppressWarnings("unused")
    private static volatile Consumer<Object[]> blockingMethodConsumer;

    @SuppressWarnings("unused")
    private static volatile Predicate<Thread> threadPredicate;

    public static final ThreadLocal<Boolean> IS_ALLOWED = ThreadLocal.withInitial(() -> {
        if (threadPredicate.test(Thread.currentThread())) {
            return false;
        } else {
            // Optimization: use Three-state (true, false, null) where `null` is `not non-blocking`
            return null;
        }
    });

    @SuppressWarnings("unused")
    public static void checkBlocking(String internalClassName, String methodName, int modifiers) {
        if (Boolean.FALSE == IS_ALLOWED.get()) {
            blockingMethodConsumer.accept(new Object[] {
                    internalClassName.replace("/", "."),
                    methodName,
                    modifiers
            });
        }
    }
}
