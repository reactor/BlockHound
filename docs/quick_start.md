# Quick start

## Getting it
Download it from repo.spring.io or Maven Central repositories (stable releases only):

```groovy
repositories {
  mavenCentral()
  // maven { url 'https://repo.spring.io/milestone' }
  // maven { url 'https://repo.spring.io/snapshot' }
}

dependencies {
  testImplementation 'io.projectreactor.tools:blockhound:$LATEST_RELEASE'
  // testImplementation 'io.projectreactor.tools:blockhound:$LATEST_MILESTONE'
  // testImplementation 'io.projectreactor.tools:blockhound:$LATEST_SNAPSHOT'
}
```
Where:

|||
|-|-|
|`$LATEST_RELEASE`|[![](https://img.shields.io/badge/dynamic/xml.svg?label=&color=green&query=%2F%2Fmetadata%2Fversioning%2Flatest&url=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fprojectreactor%2Ftools%2Fblockhound%2Fmaven-metadata.xml)](https://repo1.maven.org/maven2/io/projectreactor/tools/blockhound/)|
|`$LATEST_MILESTONE`|[![](https://img.shields.io/badge/dynamic/xml.svg?label=&color=blue&query=%2F%2Fmetadata%2Fversioning%2Flatest&url=https%3A%2F%2Frepo.spring.io%2Fmilestone%2Fio%2Fprojectreactor%2Ftools%2Fblockhound%2Fmaven-metadata.xml)](https://repo.spring.io/milestone/io/projectreactor/tools/blockhound/)|
|`$LATEST_SNAPSHOT`|[![](https://img.shields.io/badge/dynamic/xml.svg?label=&color=orange&query=%2F%2Fmetadata%2Fversioning%2Flatest&url=https%3A%2F%2Frepo.spring.io%2Fsnapshot%2Fio%2Fprojectreactor%2Ftools%2Fblockhound%2Fmaven-metadata.xml)](https://repo.spring.io/snapshot/io/projectreactor/tools/blockhound/)|

## JDK13+ support

for JDK 13+, it is no longer allowed redefining native methods. So for the moment, as a temporary work around, please use the
`-XX:+AllowRedefinitionToAddDeleteMethods` jvm argument:

_Maven_

```xml
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
                <version>2.22.2</version>
                <configuration>
                    <argLine>-XX:+AllowRedefinitionToAddDeleteMethods</argLine>
                </configuration>
    </plugin>
```

_Gradle_

```groovy
    tasks.withType(Test).all {
        if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_13)) {
            jvmArgs += [
                "-XX:+AllowRedefinitionToAddDeleteMethods"
            ]
        }
    }
```

## Using Tomcat

When using BlockHound from a Tomcat webapp, do not embedd blockhound dependency within your webapp. Instead of that, just drop
the blockhound jar in the Tomcat shared "lib" directory.
If you are using `Cargo` maven plugin, this can be done using a [shared classpath](https://codehaus-cargo.github.io/cargo/Application+Classpath.html) 

Here is an example using Cargo maven plugin:

````xml
    <dependencies>
        <dependency>
            <groupId>io.projectreactor.tools</groupId>
            <artifactId>blockhound</artifactId>
            <version>(latest blockhound version)</version>
            <scope>provided</scope>
        </dependency>
        ...
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.cargo</groupId>
                <artifactId>cargo-maven3-plugin</artifactId>
                <version>1.10.4</version>
                <configuration>
                    <container>
                        <containerId>tomcat9x</containerId>
                        <type>embedded</type>
                        <dependencies>
                            <dependency>
                                <groupId>io.projectreactor.tools</groupId>
                                <artifactId>blockhound</artifactId>
                                <classpath>shared</classpath>
                            </dependency>
                        </dependencies>
                    </container>
                    <deployables>
                        <deployable>
                            <type>war</type>
                            <location>${project.build.directory}/${project.build.finalName}.war</location>
                            <properties>
                                <context>/</context>
                            </properties>
                        </deployable>
                    </deployables>
                </configuration>
            </plugin>
        </plugins>
    </build>
    ...
````


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

If you can't change the application code, you can also activate BlockHound using the -javaagent:<path to BlockHound agent jar> JVM option, something like this
(you need at least version 1.0.7):
```shell
java -javaagent:BlockHound/agent/build/libs/agent.jar -jar my-application.jar
```
Notice that when using JPMS, for the moment BlockHound needs to be installed using `-javaavant` JVM option.

## What's Next?
You can further customize Blockhound's behavior, see [customization](customization.md).
