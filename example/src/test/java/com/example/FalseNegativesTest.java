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

package com.example;

import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class FalseNegativesTest {

    static {
        BlockHound.install();
    }

    @Rule
    public Timeout timeout = new Timeout(20, TimeUnit.SECONDS);

    @Test
    public void shouldNotReportClassLoader() throws Exception {
        ClassLoader classLoader = new ClassLoader() {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                try {
                    Thread.sleep(10);
                    return null;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Throwable e = Assertions.catchThrowable(() -> {
            Mono.fromCallable(() -> {
                return classLoader.loadClass("does.not.exist");
            }).hide().subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(10));
        });

        if (e != null) {
            e.printStackTrace(System.out);
        }

        assertThat(e).isNull();
    }

}
