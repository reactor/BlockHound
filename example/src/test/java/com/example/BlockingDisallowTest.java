/*
 * Copyright (c) 2023-Present Pivotal Software Inc, All Rights Reserved.
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
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class BlockingDisallowTest {

    static {
        BlockHound.install(b -> b
                .allowBlockingCallsInside(NonBlockingClass.class.getName(), "outer")
                .disallowBlockingCallsInside(NonBlockingClass.class.getName(), "inner")
        );
    }

    @Test
    public void shouldDisallow() throws InterruptedException {
        NonBlockingClass nbc = new NonBlockingClass();
        AtomicReference<BlockingOperationError> boeRef = new AtomicReference<>();

        //to trip BlockHound in the first place, we must be in a nonblocking thread
        CountDownLatch latch = new CountDownLatch(1);
        Schedulers.parallel().schedule(() -> {
            try {
                nbc.outer();
            } catch (BlockingOperationError boe) {
                boeRef.set(boe);
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);

        //given the configuration we expect that yield is allowed, but the sleep inside example2 isn't
        assertThat(boeRef.get())
                .isNotNull()
                .hasMessage("Blocking call! java.lang.Thread.sleep")
                .hasStackTraceContaining("at com.example.BlockingDisallowTest$NonBlockingClass.inner")
                .hasStackTraceContaining("at com.example.BlockingDisallowTest$NonBlockingClass.outer");
    }

    static class NonBlockingClass {

        String inner() {
            try {
                //if this trips BlockHound, the test fails (inner not in the stacktrace)
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "example";
        }

        String outer() {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Thread.yield();
            return inner();
        }
    }
}
