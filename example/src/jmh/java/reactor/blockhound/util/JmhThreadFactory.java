/*
 * Copyright (c) 2018-2019 Pivotal Software Inc, All Rights Reserved.
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
package reactor.blockhound.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class JmhThreadFactory implements ThreadFactory {

    private final boolean isBlockingAllowed;
    private final String namePrefix;
    private final AtomicLong threadCounter;

    private JmhThreadFactory(boolean isBlockingAllowed) {
        this.isBlockingAllowed = isBlockingAllowed;
        this.namePrefix = isBlockingAllowed ? "blocking-" : "nonBlocking-";
        this.threadCounter  = new AtomicLong(0);
    }

    public static ThreadFactory blockingAllowedThreadFactory() {
        return new JmhThreadFactory(true);
    }

    public static ThreadFactory blockingNotAllowedThreadFactory() {
        return new JmhThreadFactory(false);
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = isBlockingAllowed ?
                new Thread(r, namePrefix + threadCounter.incrementAndGet()) :
                new NonBlockingThread(r, namePrefix + threadCounter.incrementAndGet());
        thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());  //TODO
        return thread;
    }

    static class NonBlockingThread extends Thread implements JmhNonBlocking {
        NonBlockingThread(Runnable target, String name) {
            super(target, name);
        }
    }

}
