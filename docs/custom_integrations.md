# Custom integrations

BlockHound can be extended without changing its code by using
[the JVM's SPI mechanism](https://docs.oracle.com/javase/7/docs/api/java/util/ServiceLoader.html).

You will need to implement `reactor.blockhound.integration.BlockHoundIntegration` interface
and add the implementor to `META-INF/services/reactor.blockhound.integration.BlockHoundIntegration` file.

> ℹ️ **Hint:** consider using [Google's AutoService](https://github.com/google/auto/tree/master/service) for it:
> ```java
> @AutoService(BlockHoundIntegration.class)
> public class MyIntegration implements BlockHoundIntegration {
>     // ...
> }
> ```

## Writing integrations
An integration is just a consumer of BlockHound's `Builder` and uses the same API as described in [customization](customization.md).

Here is an example:
```java
public class MyIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.nonBlockingThreadPredicate(current -> {
            return current.or(t -> {
                if (i.getName() == null) {
                    return false;
                }
                return t.getName().contains("my-pool-");
            });
        });
    }
}
```


BlockHound's built-in integrations use the same mechanism and can be used as more advanced examples:  
https://github.com/reactor/BlockHound/tree/master/agent/src/main/java/reactor/blockhound/integration
