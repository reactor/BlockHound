/*
 * Copyright (c) 2019-Present Pivotal Software Inc, All Rights Reserved.
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

import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ConcurrentHashMap;

public class JDKTest {

    static {
        BlockHound.install();
    }

    @Test
    public void shouldAllowConcurrentHashMapInit() {
        for (int i = 0; i < 100; i++) {
            var map = new ConcurrentHashMap<Integer, Boolean>();
            Flux.range(0, Runtime.getRuntime().availableProcessors())
                    .parallel()
                    .runOn(Schedulers.parallel())
                    .doOnNext(it -> map.put(it, true))
                    .sequential()
                    .blockLast();
        }
    }
}
