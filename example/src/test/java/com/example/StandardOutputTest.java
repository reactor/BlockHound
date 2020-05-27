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

import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class StandardOutputTest {

    static {
        BlockHound.install();
    }

    @Test
    public void shouldNotReportStdout() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            System.out.println("Hello");
            return null;
        });
        Schedulers.parallel().schedule(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void shouldNotReportStderr() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            System.err.println("Hello");
            return null;
        });
        Schedulers.parallel().schedule(task);
        task.get(10, TimeUnit.SECONDS);
    }
}
