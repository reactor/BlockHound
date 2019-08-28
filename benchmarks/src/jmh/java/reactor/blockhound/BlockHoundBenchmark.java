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
package reactor.blockhound;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("WeakerAccess")
@Warmup(iterations = 5)
@Measurement(iterations = 3)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@OperationsPerInvocation(BlockHoundBenchmark.OPERATIONS_PER_INVOCATION)
@Fork(3)
public class BlockHoundBenchmark {

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> e.printStackTrace());
    }

    static final int OPERATIONS_PER_INVOCATION = 1_000;

    static AtomicLong counter = new AtomicLong();

    static void nonBlockingCall() {
        counter.incrementAndGet();
    }

    static void blockingCall() {
        try {
            Thread.sleep(0);
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    static void allowsBlockingCalls() {
        blockingCall();
    }

    @State(Scope.Benchmark)
    public static class BlockHoundInstalledState {

        @Setup
        public void prepare() {
            System.out.println("Installing BlockHound");
            BlockHound.builder()
                    .nonBlockingThreadPredicate(p -> p.or(NonBlockingThread.class::isInstance))
                    .allowBlockingCallsInside(BlockHoundBenchmark.class.getName(), "allowsBlockingCalls")
                    .blockingMethodCallback(m -> {}) // Do not throw
                    .install();
        }
    }

//    @Benchmark
//    public void baselineNonBlockingCall() throws Exception {
//        Thread thread = new Thread(runMultipleTimes(BlockHoundBenchmark::nonBlockingCall));
//        thread.start();
//        thread.join(5_000);
//    }
//
//    @Benchmark
//    public void measureNonBlockingCall(BlockHoundInstalledState state) throws Exception {
//        Thread thread = new NonBlockingThread(runMultipleTimes(BlockHoundBenchmark::nonBlockingCall));
//        thread.start();
//        thread.join(5_000);
//    }
//
    @Benchmark
    public void baselineBlockingCallInBlockingThread() throws Exception {
        Thread thread = new Thread(runMultipleTimes(BlockHoundBenchmark::blockingCall));
        thread.start();
        thread.join(5_000);
    }

    @Benchmark
    public void measureBlockingCallInBlockingThread(BlockHoundInstalledState state) throws Exception {
        Thread thread = new Thread(runMultipleTimes(BlockHoundBenchmark::blockingCall));
        thread.start();
        thread.join(5_000);
    }
//
//    @Benchmark
//    public void baselineAllowedBlockingCall() throws Exception {
//        Thread thread = new Thread(runMultipleTimes(BlockHoundBenchmark::allowsBlockingCalls));
//        thread.start();
//        thread.join(5_000);
//    }

    @Benchmark
    public void measureAllowedBlockingCall(BlockHoundInstalledState state) throws Exception {
        Thread thread = new NonBlockingThread(runMultipleTimes(BlockHoundBenchmark::allowsBlockingCalls));
        thread.start();
        thread.join(5_000);
    }

    static Runnable runMultipleTimes(Runnable runnable) {
        return () -> {
            for (int i = 0; i < OPERATIONS_PER_INVOCATION; i++) {
                runnable.run();
            }
        };
    }

    static final class NonBlockingThread extends Thread {
        public NonBlockingThread(Runnable target) {
            super(target);
        }
    }
}
