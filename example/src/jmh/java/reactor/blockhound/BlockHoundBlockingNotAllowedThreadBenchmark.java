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
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static reactor.blockhound.BlockHound.builder;
import static reactor.blockhound.util.JmhThreadFactory.blockingNotAllowedThreadFactory;

@SuppressWarnings({"WeakerAccess"})
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(3)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BlockHoundBlockingNotAllowedThreadBenchmark {

    int fuzzier = 42;
    ExecutorService blockingNotAllowedSingleThreadExecutor;

    @SuppressWarnings({"OptionalGetWithoutIsPresent"})
    Callable<Integer> dummyBlockingCallable = () -> DummyBlocking.dummyBlocking(fuzzier).getAsInt();

    //Separate class to be allow to whitelist in some tests
    public static class DummyBlocking {
        public static OptionalInt dummyBlocking(int value) {
            return OptionalInt.of(value);    //our artificial blocking method call
        }
    }

    @Setup(Level.Iteration)
    public void createObjectWithNonBlockingExecution() {
        blockingNotAllowedSingleThreadExecutor = newSingleThreadExecutor(blockingNotAllowedThreadFactory());
    }

    @TearDown(Level.Iteration)
    public void cleanupExecutors() {
        blockingNotAllowedSingleThreadExecutor.shutdown();
    }

    public abstract static class BlockHoundInstalledState {
        //It's just a protection against badly written case which does nothing. We ignore issues with access from multiple threads, as it's enough
        //to have it increased by one by any of the threads to assume the benchmark logic works.
        int detectedCallsCounter;

        @Setup
        public void prepare() {
            System.out.println("Debug: Installing Block Hound");
            BlockHound.Builder builder = builder();
            configureBuilder(builder);
            builder.install();
        }

        protected void configureBuilder(BlockHound.Builder builder) {
            builder.with(new JmhIntegration());

//            builder.markAsBlocking("reactor.blockhound.BlockHoundBlockingNotAllowedThreadBenchmark$DummyBlocking", "dummyBlocking", "()Ljava/util/OptionalInt;");  //TODO: Broken: https://github.com/reactor/BlockHound/issues/39
            builder.markAsBlocking(OptionalInt.class, "of", "(I)Ljava/util/OptionalInt;");
            builder.markAsBlocking(OptionalInt.class, "empty", "()Ljava/util/OptionalInt;");

            //Due to: https://github.com/reactor/BlockHound/issues/38
            builder.allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask");

            builder.blockingMethodCallback(bm -> {
                if (detectedCallsCounter == 0) {
                    System.out.println(Thread.currentThread().toString() + ": Blocking method: " + bm.toString());
                }
                detectedCallsCounter++;
            });
        }

        @Setup(Level.Iteration)
        public void resetDetections() {
            detectedCallsCounter = 0;
        }
    }

    @State(Scope.Benchmark)
    public static class BlockHoundWithAllowedYieldInstalled extends BlockHoundInstalledState {
        @Override
        protected void configureBuilder(BlockHound.Builder builder) {
            super.configureBuilder(builder);
            builder.allowBlockingCallsInside("reactor.blockhound.BlockHoundBlockingNotAllowedThreadBenchmark$DummyBlocking", "dummyBlocking");
        }

        @TearDown(Level.Iteration)
        public void assertDetections() {
            assertThat(detectedCallsCounter).describedAs("Unexpected blocking calls detected").isZero();
        }
    }

    @State(Scope.Benchmark)
    public static class BlockHoundWithNotAllowedYieldInstalled extends BlockHoundInstalledState {
        @Override
        protected void configureBuilder(BlockHound.Builder builder) {
            super.configureBuilder(builder);
            //Note: class.getCanonicalName() doesn't work due to $
            builder.disallowBlockingCallsInside("reactor.blockhound.BlockHoundBlockingNotAllowedThreadBenchmark$DummyBlocking", "dummyBlocking");
        }

        @TearDown(Level.Iteration)
        public void assertDetections() {
            assertThat(detectedCallsCounter).describedAs("No blocking calls detected").isGreaterThan(0);
        }
    }

    @Benchmark
    public int baselineBlockingCall() throws ExecutionException, InterruptedException {
        return blockingNotAllowedSingleThreadExecutor.submit(dummyBlockingCallable).get();
    }

//    @Benchmark    //TODO: Problematic in implementation as ThreadPoolExecutor itself contains blocking code - https://github.com/reactor/BlockHound/issues/38
//    public int measureNoBlockingCall(BlockHoundWithAllowedYieldInstalled state) throws ExecutionException, InterruptedException {
//        return blockingNotAllowedSingleThreadExecutor.submit(nonBlockingCallable).get();
//    }

    @Benchmark
    public int measureAllowedBlockingCall(BlockHoundWithAllowedYieldInstalled state) throws ExecutionException, InterruptedException {
        return blockingNotAllowedSingleThreadExecutor.submit(dummyBlockingCallable).get();
    }

    @Benchmark
    public int measureDisallowedBlockingCall(BlockHoundWithNotAllowedYieldInstalled state) throws ExecutionException, InterruptedException {
        return blockingNotAllowedSingleThreadExecutor.submit(dummyBlockingCallable).get();
    }
}
