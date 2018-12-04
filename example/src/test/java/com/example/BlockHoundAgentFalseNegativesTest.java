package com.example;

import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class BlockHoundAgentFalseNegativesTest {

    @Rule
    public Timeout timeout = new Timeout(20, TimeUnit.SECONDS);

    @Test
    public void shouldNotReportClassLoader() throws Exception {
        Thread.sleep(5000);
        ClassLoader classLoader = new ClassLoader() {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                try {
                    Thread.sleep(10);
                    return null;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Throwable e = Assertions.catchThrowable(() -> {
            Mono.fromCallable(() -> {
                return classLoader.loadClass(UUID.randomUUID().toString());
            }).hide().subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(10));
        });

        if (e != null) {
            e.printStackTrace(System.out);
        }

        assertThat(e).isNull();
    }

}