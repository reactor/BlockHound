package reactor.blockhound.junit.platform;

import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class JUnitPlatformIntegrationTest {

    @Test
    void shouldApplyAutomatically() {
        assertThatBlockingCall().hasMessageContaining("Blocking call!");
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
