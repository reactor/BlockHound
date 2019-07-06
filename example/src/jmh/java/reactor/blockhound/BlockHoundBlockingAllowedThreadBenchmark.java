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
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import reactor.blockhound.util.JmhIntegration;

import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.blockhound.BlockHound.builder;
import static reactor.blockhound.util.JmhThreadFactory.blockingAllowedThreadFactory;

@SuppressWarnings("WeakerAccess")
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(3)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BlockHoundBlockingAllowedThreadBenchmark {

    int fuzzier = 42;
    ExecutorService blockingAllowedSingleThreadExecutor;

    Callable<Integer> dummyBlockingCallable = () -> OptionalInt.of(fuzzier).getAsInt();
    Callable<Integer> nonBlockingCallable = () -> fuzzier;

    @Setup(Level.Iteration)
    public void createObjectWithNonBlockingExecution() {
        blockingAllowedSingleThreadExecutor = Executors.newSingleThreadExecutor(blockingAllowedThreadFactory());
    }

    @TearDown(Level.Iteration)
    public void cleanupExecutors() {
        blockingAllowedSingleThreadExecutor.shutdown();
    }

    @State(Scope.Benchmark)
    public static class BlockHoundInstalledState {
        int detectedCallsCounter;

        @Setup
        public void prepare() {
            System.out.println("Debug: Installing Block Hound");
            BlockHound.Builder builder = builder();
            builder.with(new JmhIntegration());
            builder.allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask"); //Due to: https://github.com/reactor/BlockHound/issues/38
            builder.markAsBlocking(OptionalInt.class, "of", "(I)Ljava/util/OptionalInt;");
            builder.blockingMethodCallback(bm -> {
                if (detectedCallsCounter == 0) {
                    System.out.println(Thread.currentThread().toString() + ": Blocking method: " + bm.toString());
                }
                detectedCallsCounter++;
            });
            builder.install();
        }

        @TearDown(Level.Iteration)
        public void assertDetections() {
            assertThat(detectedCallsCounter).describedAs("Unexpected blocking calls detected").isZero();
        }
    }

    @Benchmark  //not fully reliable as executor call is internally blocking: https://github.com/reactor/BlockHound/issues/38
    public int baselineNonBlockingCall() throws ExecutionException, InterruptedException {
        return blockingAllowedSingleThreadExecutor.submit(nonBlockingCallable).get();
    }

    @Benchmark
    public int measureNonBlockingCall(BlockHoundInstalledState state) throws ExecutionException, InterruptedException {
        return blockingAllowedSingleThreadExecutor.submit(nonBlockingCallable).get();
    }

    @Benchmark
    public int baselineBlockingCall() throws ExecutionException, InterruptedException {
        return blockingAllowedSingleThreadExecutor.submit(dummyBlockingCallable).get();
    }

    @Benchmark
    public int measureBlockingCall(BlockHoundInstalledState state) throws ExecutionException, InterruptedException {
        return blockingAllowedSingleThreadExecutor.submit(dummyBlockingCallable).get();
    }
}
