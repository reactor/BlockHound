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
import org.openjdk.jmh.annotations.Warmup;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static reactor.core.scheduler.Schedulers.single;

@SuppressWarnings("WeakerAccess")
//quick settings for IDE - overridden in Gradle configuration
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BlockHoundNonBlockingBenchmark {

    String fuzzier = "foo";
    Mono<String> justMono;

    int range = 100_000;
    Flux<Integer> nonBlockingFluxWithNumbers;
    Flux<Integer> nonBlockingGeneratedFluxWithNumbers;

    @Setup(Level.Iteration)
    public void createMonos() {
        justMono = Mono.just(fuzzier);
        nonBlockingFluxWithNumbers = Flux.range(0, range);

        nonBlockingGeneratedFluxWithNumbers = Flux.generate(
                () -> 0,
                (state, sink) -> {
                    sink.next(state);
                    if (state == range - 1) {   //== is enough as it should guaranteed to be executed sequentially
                        sink.complete();
                    }
                    return state + 1;
                });
    }

    @State(Scope.Benchmark)
    public static class BlockHoundInstalledState {
        @Setup
        public void prepare() {
            System.out.println("Debug: Installing Block Hound");
            BlockHound.install();
        }
    }

    @Benchmark
    public String baselineNonBlockingMonoSubscribedOnSingle() {
        return justMono.hide().subscribeOn(single()).block(Duration.ofSeconds(1));
    }

    @Benchmark
    public String measureNonBlockingMonoSubscribedOnSingle(BlockHoundInstalledState state) {
        return justMono.hide().subscribeOn(single()).block(Duration.ofSeconds(1));
    }

    @Benchmark
    public Integer baselineNonBlockingFluxSubscribedOnSingle() {
        return nonBlockingFluxWithNumbers.hide().subscribeOn(single()).blockLast(Duration.ofSeconds(10));
    }

    @Benchmark
    public Integer measureNonBlockingFluxSubscribedOnSingle(BlockHoundInstalledState state) {
        return nonBlockingFluxWithNumbers.hide().subscribeOn(single()).blockLast(Duration.ofSeconds(10));
    }

    @Benchmark
    public Integer baselineNonBlockingGeneratedFluxSubscribedOnSingle() {
        return nonBlockingGeneratedFluxWithNumbers.hide().subscribeOn(single()).blockLast(Duration.ofSeconds(10));
    }

    @Benchmark
    public Integer measureNonBlockingGeneratedFluxSubscribedOnSingle(BlockHoundInstalledState state) {
        return nonBlockingGeneratedFluxWithNumbers.hide().subscribeOn(single()).blockLast(Duration.ofSeconds(10));
    }
}
