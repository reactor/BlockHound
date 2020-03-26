/*
 * Copyright (c) 2020-Present Pivotal Software Inc, All Rights Reserved.
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

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * This is an internal class for performing the instrumentation test
 */
class TestThread extends Thread {

    volatile boolean blockingCallDetected = false;

    final FutureTask<Void> task = new FutureTask<>(() -> {
        Thread.sleep(0);
        return null;
    });

    TestThread() {
        super();
        setName("blockhound-test-thread");
        setDaemon(true);
    }

    @Override
    public void run() {
        task.run();
    }

    public void startAndWait() {
        start();
        try {
            task.get(5, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }
}
