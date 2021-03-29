/*
 * Copyright (c) 2020-Present Pivotal Software Inc, All Rights Reserved.
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

import org.assertj.core.api.Assertions;
import org.junit.Test;

import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class BlockingAllowSpecTest {

    static {
        BlockHound.install(b -> b
                // class A
                .allowBlockingCallsInside(BlockingClassA.class.getName()).forStaticInitializer()
                .allowBlockingCallsInside(BlockingClassA.class.getName()).forMethods("block")
                // then B
                .allowBlockingCallsInside(BlockingClassB.class.getName()).forMethods("block1", "block2")
        );
    }

    @Test
    public void shouldInstrumentBlockingClassA() {
        Mono.fromCallable(BlockingClassA::new)
            .map(BlockingClassA::block)
            .subscribeOn(Schedulers.parallel())
            .block();
    }

    @Test
    public void shouldInstrumentBlockingClassB() {
        Mono.fromCallable(() -> {
            BlockingClassB b = new BlockingClassB();
            b.block1();
            b.block2();
            return b;
        }).subscribeOn(Schedulers.parallel()).block();
    }

    @Test
    public void usingAllowSpecWithoutCallingMethodIsIgnored() {
        //getting the BuilderAllowSpec and not acting on it DOESN'T equate to allowing any method: it does nothing
        BlockHound.install(b -> b.allowBlockingCallsInside(BlockingClassC.class.getName()));

        Mono<String> mono = Mono.fromCallable(BlockingClassC::new)
                                .publishOn(Schedulers.parallel())
                                .map(BlockingClassC::block1);

        Assertions.assertThatExceptionOfType(RuntimeException.class)
                  .isThrownBy(mono::block)
                  .havingCause()
                  .isInstanceOf(BlockingOperationError.class)
                  .withMessage("Blocking call! java.lang.Thread.yield")
                  .withStackTraceContaining("at com.example.BlockingAllowSpecTest$BlockingClassC.block1");
    }

    static class BlockingClassA {
        static {
            try {
                Thread.sleep(0);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        String block() {
            Thread.yield();
            return "hello A";
        }
    }

    static class BlockingClassB {

        void block1() {
            try {
                Thread.sleep(0);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        void block2() {
            Thread.yield();
        }
    }

    static class BlockingClassC {

        String block1() {
            Thread.yield();
            return "hello C";
        }
    }
}
