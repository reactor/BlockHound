package com.example;

import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.exceptions.CompositeException;
import io.reactivex.internal.schedulers.NonBlockingThread;
import io.reactivex.internal.schedulers.ScheduledDirectTask;
import org.junit.Test;
import reactor.BlockHound;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

public class RxJavaTest {

    static {
        BlockHound
                .builder()
                .blockingThreadPredicate(current -> current.or(NonBlockingThread.class::isInstance))
                // TODO more places?
                .disallowBlockingCallsInside(ScheduledDirectTask.class.getName(), "call")
                .install();
    }

    @Test(expected = Error.class)
    public void testBlockingCallInsideRxJavaSingle() {
        Single.timer(10, TimeUnit.MILLISECONDS)
                .doOnSuccess(it -> Thread.sleep(10))
                .blockingGet();
    }

    @Test(expected = Error.class)
    public void testBlockingCallInsideRxJavaFlowable() throws Throwable {
        try {
            Flowable.timer(10, TimeUnit.MILLISECONDS)
                    .doOnEach(it -> Thread.sleep(10))
                    .blockingFirst();
        }
        catch (CompositeException e) {
            throw e.getExceptions().get(0);
        }
    }

    @Test(expected = Error.class)
    public void testBlockingCallInsideRxJavaInterop() throws Throwable {
        try {
            Flux.from(Flowable.timer(10, TimeUnit.MILLISECONDS))
                    .doOnEach(it -> {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    })
                    .blockFirst();
        }
        catch (Exception e) {
            if (e.getClass().getName().contains("$ReactiveException")) {
                throw e.getCause();
            }
        }
    }
}
