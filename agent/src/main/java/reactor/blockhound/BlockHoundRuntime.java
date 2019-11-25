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
class BlockHoundRuntime {

    public static final class State {

        final boolean dynamic;

        boolean allowed = false;

        public State(boolean dynamic) {
            this(dynamic, false);
        }

        State(boolean dynamic, boolean allowed) {
            this.dynamic = dynamic;
            this.allowed = allowed;
        }

        public boolean isDynamic() {
            return dynamic;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public void setAllowed(boolean allowed) {
            this.allowed = allowed;
        }
    }

    public static volatile Consumer<Object[]> blockingMethodConsumer;

    public static volatile Predicate<Thread> threadPredicate;

    public static volatile Predicate<Thread> dynamicThreadPredicate;

    public static final ThreadLocal<State> STATE = ThreadLocal.withInitial(() -> {
        boolean isDynamic = dynamicThreadPredicate.test(Thread.currentThread());
        if (isDynamic) {
            return new State(true);
        }

        boolean isNonBlocking = threadPredicate.test(Thread.currentThread());
        if (isNonBlocking) {
            return new State(false);
        }

        // Optimization: return `null` if not dynamic and `not non-blocking`
        return null;
    });

    @SuppressWarnings("unused")
    public static void checkBlocking(String internalClassName, String methodName, int modifiers) {
        State state = STATE.get();
        if (state == null || state.isAllowed()) {
            return;
        }

        if (state.isDynamic()) {
            boolean isNonBlocking = threadPredicate.test(Thread.currentThread());
            if (!isNonBlocking) {
                return;
            }
        }
        blockingMethodConsumer.accept(new Object[] {
                internalClassName.replace("/", "."),
                methodName,
                modifiers
        });
    }
}
