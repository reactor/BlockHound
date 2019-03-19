package reactor.blockhound.junit.platform;

import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class JUnitPlatformDynamicIntegrationTest {

    @TestFactory
    List<DynamicTest> tests() {
        return Arrays.asList(
                dynamicTest("simple dynamic test", () -> {
                    assertThatBlockingCall().hasMessageContaining("Blocking call!");
                })
        );
    }

    private AbstractThrowableAssert<?, ? extends Throwable> assertThatBlockingCall() {
        return Assertions.assertThatCode(() -> {
            Mono
                    .fromCallable(() -> {
                        Thread.sleep(1);
                        return "";
                    })
                    .subscribeOn(Schedulers.parallel())
                    .block();
        });
    }
}
