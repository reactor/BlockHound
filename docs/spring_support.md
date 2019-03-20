# Spring support

BlockHound Spring module will automatically install the agent at a very early stage of Spring Boot application's startup.

## Getting it
Download it from repo.spring.io or Maven Central repositories (stable releases only):

```groovy
repositories {
  maven { url 'https://repo.spring.io/milestone' }
  // maven { url 'https://repo.spring.io/snapshot' }
}

dependencies {
  testCompile 'io.projectreactor.tools:blockhound-spring:$LATEST_RELEASE'
  // testCompile 'io.projectreactor.tools:blockhound-spring:$LATEST_SNAPSHOT'
}
```
Where:  
`$LATEST_RELEASE` is: ![](https://img.shields.io/maven-metadata/v/https/repo.spring.io/milestone/io/projectreactor/tools/blockhound-spring/maven-metadata.xml.svg?label=)  
`$LATEST_SNAPSHOT` is: ![](https://img.shields.io/maven-metadata/v/https/repo.spring.io/snapshot/io/projectreactor/tools/blockhound-spring/maven-metadata.xml.svg?label=)

## Custom config
The listener will call `install()` without the arguments (see [customization](./customization.md) for details).

If you want to apply your custom integration, register it as any other integration as documented [here](./custom_integrations.md).
