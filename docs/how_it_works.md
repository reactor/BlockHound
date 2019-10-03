# How it works

BlockHound is a Java Agent.

It instruments the pre-defined set of blocking methods (see [customization](customization.md))
in the JVM and adds a special check before calling the callback
(see [Blocking call decision](#Blocking-call-decision)).

## Blocking Java method detection
To detect blocking Java methods, BlockHound alters the bytecode of a method and adds the following line at the beginning of the method's body:
```java
// java.net.Socket
public void connect(SocketAddress endpoint, int timeout) {
    reactor.blockhound.BlockHoundRuntime.checkBlocking(
        "java.net.Socket",
        "connect",
        /*method modifiers*/
    );
```

See [Blocking call decision](#Blocking-call-decision) for the details of how `checkBlocking` works.

## Blocking JVM native method detection
Since native methods in JVM can't be instrumented (they have no body), we use JVM's native method instrumentation technique.

Consider the following blocking method:
```java
// java.lang.Thread
public static native void sleep(long millis);
```

The method is public and we can't instrument the wrapping Java method. Instead, we relocate the old native method:
```java
private static native void $$BlockHound$$_sleep(long millis);
```

Then we create a new Java method, with exactly same signature as the old one, delegating to the old implementation:
```java
public static void sleep(long millis) {
    $$BlockHound$$_sleep(millis);
}
```

As you can see, the cost of such instrumentation is minimal and only adds 1 hop to the original method.

Now, we add the blocking call detection, [the same way as we do it with Java methods](#Blocking-Java-method-detection):
```java
public static void sleep(long millis) {
    reactor.blockhound.BlockHoundRuntime.checkBlocking(
        "java.lang.Thread",
        "sleep",
        /*method modifiers*/
    );
    $$BlockHound$$_sleep(millis);
}
```

## Blocking call decision
We could throw an error (or call the user-provided callback) on every blocking call,
but sometimes there are blocking calls that must be called (class-loading is a good example).

For this reason, BlockHound supports white- and blacklisting of different methods
by checking the current state:
```java
static void checkBlocking(String className, String methodName, int modifiers) {
    if (Boolean.FALSE == IS_ALLOWED.get()) {
        // Report
    }
}
```

Where `IS_ALLOWED` ("is blocking call allowed in this thread or not") is a `ThreadLocal` variable that is defined as:
```java
public static final ThreadLocal<Boolean> IS_ALLOWED = ThreadLocal.withInitial(() -> {
    if (threadPredicate.test(Thread.currentThread())) {
        return false;
    }
    else {
        // Optimization: use Three-state (true, false, null) where `null` is `not non-blocking`
        return null;
    }
});
```

it defaults to `false` ("not allowed") if the current thread is non-blocking,
and to `null` otherwise.

Then, we instrument every "allowed" (or "disallowed") method and set `IS_ALLOWED`:
```java
class ClassLoader {
    // ...

    public Class<?> loadClass(String name) {
        Boolean previous = BlockHoundRuntime.IS_ALLOWED.get();
        BlockHoundRuntime.IS_ALLOWED.set(previous != null ? true : null);

        try {
            // Original call
            return loadClass(name, false);
        } finally {
            BlockHoundRuntime.IS_ALLOWED.set(previous);
        }
    }
}
```

This way, unless there is a "disallowed" method inside `loadClass(String, boolean)`,
`checkBlocking` will not report the blocking call.

Note that the check is O(1) and equals to a single `ThreadLocal` read that is supposed
to be fast enough for this use case.