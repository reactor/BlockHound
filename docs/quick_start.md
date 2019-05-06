# Quick start

## Getting it
Download it from repo.spring.io or Maven Central repositories (stable releases only):

```groovy
repositories {
  maven { url 'https://repo.spring.io/milestone' }
  // maven { url 'https://repo.spring.io/snapshot' }
}

dependencies {
  testCompile 'io.projectreactor.tools:blockhound:$LATEST_RELEASE'
  // testCompile 'io.projectreactor.tools:blockhound:$LATEST_SNAPSHOT'
}
```
Where:

|||
|-|-|
|`$LATEST_RELEASE`|[![](https://img.shields.io/badge/dynamic/xml.svg?label=&color=blue&query=%2F%2Fmetadata%2Fversioning%2Flatest&url=https%3A%2F%2Frepo.spring.io%2Fmilestone%2Fio%2Fprojectreactor%2Ftools%2Fblockhound%2Fmaven-metadata.xml)](https://repo.spring.io/milestone/io/projectreactor/tools/blockhound/)|
|`$LATEST_SNAPSHOT`|[![](https://img.shields.io/badge/dynamic/xml.svg?label=&color=orange&query=%2F%2Fmetadata%2Fversioning%2Flatest&url=https%3A%2F%2Frepo.spring.io%2Fsnapshot%2Fio%2Fprojectreactor%2Ftools%2Fblockhound%2Fmaven-metadata.xml)](https://repo.spring.io/snapshot/io/projectreactor/tools/blockhound/)|

## Installation
BlockHound is a JVM agent. You need to "install" it before it starts detecting the issues:
```java
BlockHound.install();
```

On install, it will discover all known integrations (see [writing custom integrations](custom_integrations.md) for details)
and perform a one time instrumentation (see [how it works](how_it_works.md)).
The method is idempotent, you can call it multiple times.

The best place to put this line is before *any* code gets executed, e.g. `@BeforeClass`, or `static {}` block, or test listener.
BlockHound also [supports some testing frameworks](supported_testing_frameworks.md).

**NB:** it is highly recommended to add a dummy test with a well-known blocking call to ensure that it installed correctly.  
Something like this will work:
```java
Mono.delay(Duration.ofMillis(1))
    .doOnNext(it -> {
        try {
            Thread.sleep(10);
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    })
    .block(); // should throw an exception about Thread.sleep
```

## What's Next?
You can further customize Blockhound's behavior, see [customization](customization.md).
