# Tips & Tricks

## Adding BlockHound to your tests

When you add BlockHound, **make sure to add a test that will assert the integration.**
Otherwise, you may get false positives due to the incorrectly installed agent (or not installed at all!).

The simpliest test using Project Reactor would look like this:
```java
@Test
public void blockHoundWorks() {
    try {
        FutureTask<?> task = new FutureTask<>(() -> {
            Thread.sleep(0);
            return "";
        });
        Schedulers.parallel().schedule(task);

        task.get(10, TimeUnit.SECONDS);
        Assert.fail("should fail");
    } catch (ExecutionException e) {
        Assert.assertTrue("detected", e.getCause() instanceof BlockingOperationError);
    }
}
```

## Debugging

If your tests hang after adding BlockHound, it may be that some blocking call is detected
and the event loop didn't handle the error properly.  
Even worse if there is `try {} catch {}` that ignores (swallows) the error. 

If you see such behaviour, **consider [overriding the callback](customization.md) and printing instead of throwing:**
```java
BlockHound.install(builder -> {
    builder.blockingMethodCallback(it -> {
        new Exception(it.toString()).printStackTrace();
    });
});
```

This way you will run BlockHound in a "soft mode" where blocking operations
are detected but won't cause the code to fail and help you to pinpoint the issue.

But don't forget to change it back after debugging!

## How to select what to whitelist

Sometimes some calls have to be whitelisted and cannot be avoided.

BlockHound provides an API to whitelist them, but you have to be careful!  
**Whitelisting a common method (think `Thread#run`) may cause false positives!**

Instead, whitelist the least common denominator you can find by iterating
the stacktrace of the reported call.

Consider the following code:
```java
class OperationRunnable implements Runnable {

    TaskRunner runner;

    public void run() {
        while (true) {
            runner.run();
        }
    }
}

class TaskRunner {

    TaskExecutor executor;

    public void run() {
        var task = executor.takeTask();

        task.run();
    }
} 
```

And the following stacktrace:
```java
java.lang.Error: sun.misc.Unsafe#park
    at sun.misc.Unsafe.park(Unsafe.java)
    at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:215)
    at java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos(AbstractQueuedSynchronizer.java:2078)
    at java.util.concurrent.LinkedBlockingQueue.poll(LinkedBlockingQueue.java:467)
    at com.example.TaskExecutor.takeTask(GlobalEventExecutor.java:95)
    at com.example.TaskExecutor$TaskRunner.run(GlobalEventExecutor.java:239)
    at com.example.OperationRunnable.run(OperationRunnable.java:30)
    at com.example.NonBlockingThread.run(NonBlockingThread.java:18)
```

Whitelisting `NonBlockingThread#run`, `OperationRunnable#run` or even `TaskRunner#run` would
prevent BlockHound from detecting blocking code in the tasks.

Whitelisting `LinkedBlockingQueue#poll` or `LockSupport#parkNanos` would affect
other places that may call this API and actually block what shouldn't be blocked.

This is why the best candidate to be whitelisted is `TaskExecutor#takeTask` (unless this is public API!).  
It is blocking, but we need it to run our task polling logic.  
Since `TaskExecutor#takeTask` does not call any user provided code, we know how it will behave
and can safely whitelist it.
