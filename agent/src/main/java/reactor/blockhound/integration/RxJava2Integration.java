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
import io.reactivex.functions.Function;
import io.reactivex.internal.schedulers.NonBlockingThread;
import io.reactivex.plugins.RxJavaPlugins;
import reactor.blockhound.BlockHound;

@AutoService(BlockHoundIntegration.class)
public class RxJava2Integration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        try {
            Class.forName("io.reactivex.Flowable");
        }
        catch (ClassNotFoundException ignored) {
            return;
        }

        Function<? super Runnable, ? extends Runnable> oldHandler = RxJavaPlugins.getScheduleHandler();

        RxJavaPlugins.setScheduleHandler(
                oldHandler != null
                        ? r -> new MarkerRunnable(oldHandler.apply(r))
                        : MarkerRunnable::new
        );

        builder.nonBlockingThreadPredicate(current -> current.or(NonBlockingThread.class::isInstance));

        // TODO more places?
        builder.disallowBlockingCallsInside(MarkerRunnable.class.getName(), "run");
    }

    static class MarkerRunnable implements Runnable {

        final Runnable runnable;

        public MarkerRunnable(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            runnable.run();
        }
    }

}
