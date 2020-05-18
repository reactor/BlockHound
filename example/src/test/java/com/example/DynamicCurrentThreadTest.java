package com.example;

import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;

public class DynamicCurrentThreadTest {

    private static final ThreadLocal<Boolean> CAN_BLOCK = ThreadLocal.withInitial(() -> true);

    static {
        var testThread = Thread.currentThread();
        BlockHound.install(b -> {
            b.addDynamicThreadPredicate(testThread::equals);

            b.nonBlockingThreadPredicate(p -> p.or(thread -> {
                return !CAN_BLOCK.get();
            }));
        });
    }

    @After
    public void tearDown() {
        CAN_BLOCK.remove();
    }

    @Test
    public void testChangingCurrentThreadsStatus() throws Exception {
        Thread.sleep(0);

        CAN_BLOCK.set(false);
        Assertions.assertThatThrownBy(() -> Thread.sleep(0))
                  .isInstanceOf(BlockingOperationError.class);

        // Reset to default
        CAN_BLOCK.remove();
        Thread.sleep(0);
    }
}
