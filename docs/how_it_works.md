# How it works

BlockHound is a Java Agent with a JNI helper.

It instruments the pre-defined set of blocking methods (see [customization](customization.md))
in the JVM and adds a special check (via JNI method) whether current thread is blocking or not before calling the callback.

## Blocking Java method detection
To detect blocking Java methods, BlockHound alters the bytecode of a method and adds the following line at the beginning of the method's body:
```java
// java.net.Socket
public void connect(SocketAddress endpoint, int timeout) {
    reactor.BlockHoundRuntime.checkBlocking(
        "java.net.Socket",
        "connect",
        /*method modifiers*/
    );
```

`checkBlocking` will delegate to the JNI helper and maybe call the "blocking method detected" callback.

The arguments are passed to the callback, but not used in the "blocking call" decision making.

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

Now, we add the blocking call detection, the same way as we do it with Java methods:
```java
public static void sleep(long millis) {
    reactor.BlockHoundRuntime.checkBlocking(
        "java.lang.Thread",
        "sleep",
        /*method modifiers*/
    );
    $$BlockHound$$_sleep(millis);
}
```

## Blocking call decision
For performance reasons, part of it is implemented in C++ and executed with JNI:
1. First, it checks if a current thread is "tagged" already. If not, it creates a tag and calls user-provided predicate
   (see [customization](customization.md)) to mark it as either blocking or non-blocking.
2. Then, it iterates the stack trace until it finds a pre-marked method (see [customization](customization.md)), and returns a boolean where:  
    - `true` means "this method is not supposed to call blocking methods"
    - `false` means "this method may have a blocking call down the callstack" (think SLF4J logger writing something
      to the console with a blocking `OutputSteam#write` method).
