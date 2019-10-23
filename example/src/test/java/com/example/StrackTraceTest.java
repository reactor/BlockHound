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

import org.assertj.core.api.Assertions;
import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public class StrackTraceTest {

    static {
        BlockHound.install();
    }

    @Test
    public void shouldHideInternalFrames() {
        Throwable e = Assertions.catchThrowable(() -> {
            Mono
                    .fromCallable(() -> {
                        Thread.sleep(0);
                        return null;
                    })
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofMillis(100));
        });

        assertThat(e)
                .as("exception")
                .isNotNull()
                .satisfies(it -> {
                    assertThat(it.getCause().getStackTrace())
                            .as("Cause's stacktrace")
                            .extracting(StackTraceElement::getClassName, StackTraceElement::getMethodName)
                            .startsWith(
                                    tuple("java.lang.Thread", "sleep")
                            );
                });
    }
}
