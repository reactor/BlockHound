package com.example;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class CustomBlockingMethodTest {

    static {
        // Load the class, so that we test the retransform too
        Blocking.block();
        BlockHound.install(b -> {
            b.markAsBlocking(Blocking.class, "block", "()V");
        });
    }

    @Test
    public void shouldReportCustomBlockingMethods() {
        Throwable e = Assertions.catchThrowable(() -> {
            Mono.fromRunnable(Blocking::block).hide().subscribeOn(Schedulers.parallel()).block(Duration.ofMillis(100));
        });

        assertThat(e)
                .as("exception")
                .isNotNull()
                .hasMessageEndingWith("Blocking call! " + Blocking.class.getName() + ".block");
    }

    static class Blocking {

        static void block() {
        }
    }
}
