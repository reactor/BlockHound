# BlockHound (experimental)

[![Travis CI](https://travis-ci.org/reactor/BlockHound.svg?branch=master)](https://travis-ci.org/reactor/BlockHound)

Java agent to detect blocking calls from Reactor's non-blocking threads.

## How it works
BlockHound will transparently instrument the JVM classes and intercept blocking calls (e.g. IO) if they are performed from threads marked as "non-blocking operations only" (ie. threads implementing Reactor's `NonBlocking` marker interface, like those started by `Schedulers.parallel()`). If and when this happens (but remember, this should never happen!:stuck_out_tongue_winking_eye:), an error will be thrown. Here is an example:
```java
// Example.java
Mono.delay(Duration.ofSeconds(1))
    .doOnNext(it -> {
        try {
            Thread.sleep(10);
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    })
    .block();
```

Will result in:
```
java.lang.Error: Blocking call! java.lang.Thread.sleep
	at java.base/java.lang.Thread.sleep(Native Method)
	at com.example.Example.lambda$exampleTest$0(Example.java:16)
```
Note that it points to the exact place where the blocking call got triggered. In this example it was `Example.java:16`.


## Getting the binaries
Currently there are no pre-built binaries and you have to build them for your platform with the following command:

    ./gradlew :native-agent:linkRelease

It will compile and put the artifact to `native-agent/build/lib/main/release/` folder.

## Adding to the project

BlockHound works as a native JVM agent. Please refer to your build system's documentation to find out how to configure it. Here are some examples:

### Gradle tests
```groovy
test {
    jvmArgs(
        "-agentpath:/path/to/libBlockHound.{dylib,so,dll}"
    )
}
```

### Maven surefire tests
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
        -agentpath:/path/to/libBlockHound.{dylib,so,dll}
        </argLine>
    </configuration>
</plugin>
```

### Maven failsafe tests
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <argLine>-agentpath:/path/to/libBlockHound.{dylib,so,dll}</argLine>
    </configuration>
</plugin>
```

### Gradle Spring Boot application
```groovy
bootRun {
   jvmArgs = ["-agentpath:/path/to/libBlockHound.{dylib,so,dll}"]
}
```

### Maven Spring Boot application
```xml
    <plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <jvmArguments>
        -agentpath:/path/to/libBlockHound.{dylib,so,dll}
        </jvmArguments>
    </configuration>
    </plugin>
```

-------------------------------------
_Licensed under [Apache Software License 2.0](www.apache.org/licenses/LICENSE-2.0)_

_Sponsored by [Pivotal](http://pivotal.io)_
