/*
 * Copyright (c) 2019-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example;

import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;

public class DynamicThreadsTest {

    static {
        BlockHound.install(b -> {
            b.addDynamicThreadPredicate(DynamicThread.class::isInstance);

            b.nonBlockingThreadPredicate(p -> p.or(thread -> {
                return thread instanceof DynamicThread && ((DynamicThread) thread).isNonBlocking;
            }));
        });
    }

    @Test
    public void shouldNotCacheDynamicThreads() throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        DynamicThread thread = new DynamicThread() {
            @Override
            public void run() {
                try {
                    try {
                        isNonBlocking = true;
                        Thread.sleep(0);
                        fail("should fail");
                    }
                    catch (BlockingOperationError ignored) {
                    }

                    isNonBlocking = false;
                    Thread.sleep(0);

                    try {
                        isNonBlocking = true;
                        Thread.sleep(0);
                        fail("should fail");
                    }
                    catch (BlockingOperationError ignored) {
                    }

                    future.complete(null);
                }
                catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            }
        };

        thread.start();

        future.get(5, TimeUnit.SECONDS);
    }

    static class DynamicThread extends Thread {

        boolean isNonBlocking = true;
    }
}
