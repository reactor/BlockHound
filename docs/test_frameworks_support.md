# Testing Framework Support

## JUnit Platform (JUnit Jupiter or JUnit Vintage)

BlockHound ships with an optional module providing a [JUnit Platform TestExecutionListener](https://junit.org/junit5/docs/current/api/org/junit/platform/launcher/TestExecutionListener.html):

```groovy
'io.projectreactor.tools:blockhound-junit-platform:$VERSION'
```

> ⚠️ Due to a [bug in Gradle](http://github.com/gradle/gradle/issues/8806), Gradle users must add the following dependency:
> `testRuntime 'org.junit.platform:junit-platform-launcher'` (version 1.0.0 or higher)

Once you add the `blockhound-junit-platform` artifact as a dependency, the BlockHound `TestExecutionListener` will be automatically registered and executed by the JUnit Platform.

The implementation invokes `BlockHound.install()` (see [customization](./customization.md)).  

Should you need any customizations, you can implement them as [custom integrations](./custom_integrations.md). Simply implement the `reactor.blockhound.integration.BlockHoundIntegration` interface and register it in `META-INF/services/reactor.blockhound.integration.BlockHoundIntegration`.

## JUnit 3/4

Unfortunatelly, there is no simple way to add a global lifecycle listener in JUnit 3 or JUnit 4. However, you can use the JUnit Platform integration by running your JUnit 3/4 tests as described in the [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/#migrating-from-junit4-running).
