# Customization

BlockHound provides three means of usage:
1. `BlockHound.install()` - will use `ServiceLoader` to load all known `reactor.blockhound.integration.BlockHoundIntegration`s
1. `BlockHound.install(BlockHoundIntegration... integrations)` - same as `BlockHound.install()`, but adds user-provided integrations to the list.
1. `BlockHound.builder().install()` - will create a **new** builder, **without** discovering any integrations.  
You may install them manually by using `BlockHound.builder().with(new MyIntegration()).install()`.

## Marking more methods as blocking
* `Builder#markAsBlocking(Class clazz, String methodName, String signature)`
* `Builder#markAsBlocking(String className, String methodName, String signature)`

Example:
```java
builder.markAsBlocking("com.example.NativeHelper", "doSomethingBlocking", "(I)V");
```

Note that the `signature` argument is
[JVM's notation for the method signature](https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html#wp276).

## (Dis-)allowing blocking calls inside methods
* `Builder#allowBlockingCallsInside(String className, String methodName)`
* `Builder#disallowBlockingCallsInside(String className, String methodName)`

Example:

This will allow blocking method calls inside `Logger#callAppenders` down the callstack:
```java
builder.allowBlockingCallsInside(
    "ch.qos.logback.classic.Logger",
    "callAppenders"
);
```

While this disallows blocking calls unless there is an allowed method down the callstack:
```java
builder.disallowBlockingCallsInside(
    "reactor.core.publisher.Flux",
    "subscribe"
);
```

## Custom blocking method callback
* `Builder#blockingMethodCallback(Consumer<BlockingMethod> consumer)`

By default, BlockHound will throw an error when it detects a blocking call.  
But you can implement your own logic by setting a callback.

Example:
```java
builder.blockingMethodCallback(it -> {
    new Error(it.toString()).printStackTrace();
});
```
Here we dump the stacktrace instead of throwing the error, so that we do not alter an execution of the code.

## Custom blocking thread predicate
* `Builder#blockingThreadPredicate(Function<Predicate<Thread>, Predicate<Thread>> predicate)`

If you integrate with exotic technologies, or implement your own thread pooling,
you might want to mark those threads as non-blocking. Example:
```java
builder.blockingThreadPredicate(current -> {
    return current.or(it -> it.getName().contains("my-thread-"))
});
```

 ⚠️ **Warning:** do not ignore the `current` predicate unless you're absolutely sure you know what you're doing.
Other integrations will not work if you override it instead of using `Predicate#or`.
