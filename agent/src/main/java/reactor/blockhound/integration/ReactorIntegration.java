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

package reactor.blockhound.integration;

import com.google.auto.service.AutoService;
import reactor.blockhound.BlockHound;
import reactor.core.scheduler.NonBlocking;
import reactor.core.scheduler.Schedulers;
import reactor.blockhound.integration.util.TaskWrappingScheduledExecutorService;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@AutoService(BlockHoundIntegration.class)
public class ReactorIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        try {
            Class.forName("reactor.core.publisher.Flux");
        }
        catch (ClassNotFoundException ignored) {
            return;
        }

        try {
            // Reactor 3.3.x comes with built-in integration
            Class.forName("reactor.core.CorePublisher");
            return;
        }
        catch (ClassNotFoundException ignored) {
        }

        // `ScheduledThreadPoolExecutor$DelayedWorkQueue.offer` parks the Thread with Unsafe#park.
        builder.allowBlockingCallsInside(ScheduledThreadPoolExecutor.class.getName(), "scheduleAtFixedRate");

        builder.nonBlockingThreadPredicate(current -> current.or(NonBlocking.class::isInstance));

        for (String className : new String[]{"Flux", "Mono", "ParallelFlux"}) {
            builder.disallowBlockingCallsInside("reactor.core.publisher." + className, "subscribe");
            builder.disallowBlockingCallsInside("reactor.core.publisher." + className, "onNext");
            builder.disallowBlockingCallsInside("reactor.core.publisher." + className, "onError");
            builder.disallowBlockingCallsInside("reactor.core.publisher." + className, "onComplete");
        }

        try {
            Schedulers.addExecutorServiceDecorator("BlockHound", (scheduler, scheduledExecutorService) -> {
                return new TaskWrappingScheduledExecutorService(scheduledExecutorService) {
                    @Override
                    protected Runnable wrap(Runnable runnable) {
                        return new Wrapper<>(runnable);
                    }

                    @Override
                    protected <V> Callable<V> wrap(Callable<V> callable) {
                        return new Wrapper<V>(callable);
                    }
                };
            });
            builder.disallowBlockingCallsInside(Wrapper.class.getName(), "call");
        }
        catch (NoSuchMethodError e) {
            builder.disallowBlockingCallsInside("reactor.core.scheduler.SchedulerTask", "call");
            builder.disallowBlockingCallsInside("reactor.core.scheduler.WorkerTask", "call");
            builder.disallowBlockingCallsInside("reactor.core.scheduler.PeriodicWorkerTask", "call");
            builder.disallowBlockingCallsInside("reactor.core.scheduler.InstantPeriodicWorkerTask", "call");
        }
    }

    static class Wrapper<V> implements Runnable, Callable<V> {

        Runnable runnable;

        Callable<V> callable;

        public Wrapper(Runnable runnable) {
            this.runnable = runnable;
        }

        public Wrapper(Callable<V> callable) {
            this.callable = callable;
        }

        @Override
        public void run() {
            runnable.run();
        }

        @Override
        public V call() throws Exception {
            return callable.call();
        }
    }
}
