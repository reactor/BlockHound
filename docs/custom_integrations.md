# Custom integrations

BlockHound can be extended without changing its code by using
[the JVM's SPI mechanism](https://docs.oracle.com/javase/7/docs/api/java/util/ServiceLoader.html).

You will need to implement `reactor.blockhound.integration.BlockHoundIntegration` interface
and add the implementor to `META-INF/services/reactor.blockhound.integration.BlockHoundIntegration` file.

**Hint:** consider using [Google's AutoFactory](https://github.com/google/auto/tree/master/factory) for it:
```java
@AutoFactory(BlockHoundIntegration.class)
public class MyIntegration implements BlockHoundIntegration {
    // ...
}
```

BlockHound's own integrations are using the same mechanism:  
https://github.com/reactor/BlockHound/tree/master/agent/src/main/java/reactor/blockhound/integration
