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
import reactor.blockhound.integration.BlockHoundIntegration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.blockhound.BlockHound.builder;
import static reactor.core.scheduler.Schedulers.single;

@SuppressWarnings("WeakerAccess")
//quick settings for IDE - overridden in Gradle configuration
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BlockHoundBlockingBenchmark {

    String fuzzier = "foo";
    Mono<String> blockingMinimallyMono;

    int range = 100_000;
    Flux<Integer> blockingNumbers;

    @Setup(Level.Iteration)
    public void createMonos() {
        blockingMinimallyMono = Mono.fromCallable(() -> {
            Thread.yield();
            return fuzzier;
        });
        blockingNumbers = Flux.generate(
                () -> 0,
                (state, sink) -> {
                    Thread.yield();
                    sink.next(state);
                    if (state == range - 1) {   //== is enough as it should guaranteed to be executed sequentially
                        sink.complete();
                    }
                    return state + 1;
                });
    }

    @State(Scope.Benchmark)
    public static class BlockHoundInstalledState {
        //It's just a protection against badly written case which does nothing. We ignore issues with access from multiple threads, as it's enough
        //to have it increased by one by any of the threads to assume the benchmark logic works.
        int detectedCallsCounter;

        @Setup
        public void prepare() {
            System.out.println("Debug: Installing Block Hound");
            BlockHound.Builder builder = builder();
            ServiceLoader<BlockHoundIntegration> serviceLoader = ServiceLoader.load(BlockHoundIntegration.class);
            serviceLoader.stream().map(ServiceLoader.Provider::get).sorted().forEach(builder::with);
            builder.blockingMethodCallback(bm -> detectedCallsCounter++);
            builder.install();
        }

        @Setup(Level.Iteration)
        public void resetDetections() {
            detectedCallsCounter = 0;
        }

        @TearDown(Level.Iteration)
        public void assertDetections() {
            assertThat(detectedCallsCounter).describedAs("No blocking calls detected").isGreaterThan(0);
        }
    }

    @Benchmark
    public String baselineBlockingMonoSubscribedOnSingle() {
        return blockingMinimallyMono.hide().subscribeOn(single()).block(Duration.ofSeconds(1));
    }

    @Benchmark
    public String measureBlockingMonoSubscribedOnSingle(BlockHoundInstalledState state) {
        return blockingMinimallyMono.hide().subscribeOn(single()).block(Duration.ofSeconds(1));
    }

    //Test case where Block Hound is only used to emit warning (not to break execution - e.g. in production code)
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer baselineBlockingFluxWithWarningOnlySubscribedOnSingle() {
        return blockingNumbers.hide().subscribeOn(single()).blockLast(Duration.ofSeconds(20));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer measureBlockingFluxWithWarningOnlySubscribedOnSingle(BlockHoundInstalledState state) {
        return blockingNumbers.hide().subscribeOn(single()).blockLast(Duration.ofSeconds(20));
    }
}
