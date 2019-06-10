package com.example;

import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ConcurrentHashMap;

public class JDKTest {

    static {
        BlockHound.install();
    }

    @Test
    public void shouldAllowConcurrentHashMapInit() {
        for (int i = 0; i < 100; i++) {
            var map = new ConcurrentHashMap<Integer, Boolean>();
            Flux.range(0, Runtime.getRuntime().availableProcessors())
                    .parallel()
                    .runOn(Schedulers.parallel())
                    .doOnNext(it -> map.put(it, true))
                    .sequential()
                    .blockLast();
        }
    }
}
