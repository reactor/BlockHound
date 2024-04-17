/*
 * Copyright (c) 2018-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.blockhound;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.DescriptionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.PoolStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.DiscoveryStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.pool.TypePool.CacheProvider;
import reactor.blockhound.integration.BlockHoundIntegration;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Collections.singleton;
import static reactor.blockhound.NativeWrappingClassFileTransformer.BLOCK_HOUND_RUNTIME_TYPE;

/**
 * BlockHound is a tool to detect blocking calls from non-blocking threads.
 *
 * To use it, you need to "install" it first with either {@link BlockHound#install(BlockHoundIntegration...)}
 * or {@link BlockHound#builder()}.
 *
 * On installation, it will run the instrumentation and add the check to the blocking methods.
 *
 * Note that the installation can (and should) only be done once, subsequent install calls will be ignored.
 * Hence, the best place to put the install call is before all tests
 * or in the beginning of your "public static void main" method.
 *
 * If you have it automatically installed (e.g. via a testing framework integration), you can apply the customizations
 * by using the SPI mechanism (see {@link BlockHoundIntegration}).
 */
public class BlockHound {

    static final String PREFIX = "$$BlockHound$$_";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    /**
     * Creates a completely new {@link BlockHound.Builder} that *does not* have any integration applied.
     * Use it only if you want to ignore the built-in SPI mechanism (see {@link #install(BlockHoundIntegration...)}).
     *
     * @see BlockHound#install(BlockHoundIntegration...)
     * @return a fresh {@link BlockHound.Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads integrations with {@link ServiceLoader}, adds provided integrations,
     * and installs the BlockHound instrumentation.
     * If you don't want to load the integrations, use {@link #builder()} method.
     *
     * @param integrations an array of integrations to automatically apply on the intermediate builder
     * @see BlockHound#builder()
     */
    public static void install(BlockHoundIntegration... integrations) {
        builder()
                .loadIntegrations(integrations)
                .install();
    }

    /**
     * Entrypoint for installation via the {@code -javaagent=} command-line option.
     *
     * @param agentArgs Options for the agent.
     * @param inst Instrumentation API.
     *
     * @see java.lang.instrument
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        builder()
                .loadIntegrations()
                .with(inst)
                .install();
    }

    private BlockHound() {

    }

    private static final class BlockHoundPoolStrategy implements PoolStrategy {

        public static final PoolStrategy INSTANCE = new BlockHoundPoolStrategy();

        private BlockHoundPoolStrategy() { }

        @Override
        public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader, String name) {
            return typePool(classFileLocator, classLoader);
        }

        @Override
        public TypePool typePool(ClassFileLocator classFileLocator, ClassLoader classLoader) {
            return new TypePool.Default(
                    new CacheProvider.Simple(),
                    classFileLocator,
                    TypePool.Default.ReaderMode.FAST
            );
        }
    }

    public static class Builder {

        private final Map<String, Map<String, Set<String>>> blockingMethods = new HashMap<String, Map<String, Set<String>>>() {{
            put("java/lang/Object", new HashMap<String, Set<String>>() {{
                put("wait", singleton("(J)V"));
            }});

            put("java/io/RandomAccessFile", new HashMap<String, Set<String>>() {{
                put("read0", singleton("()I"));
                put("readBytes", singleton("([BII)I"));
                put("write0", singleton("(I)V"));
                put("writeBytes", singleton("([BII)V"));
            }});

            put("java/net/Socket", new HashMap<String, Set<String>>() {{
                put("connect", singleton("(Ljava/net/SocketAddress;)V"));
            }});

            put("java/net/DatagramSocket", new HashMap<String, Set<String>>() {{
                put("connect", singleton("(Ljava/net/InetAddress;I)V"));
            }});

            put("java/net/PlainDatagramSocketImpl", new HashMap<String, Set<String>>() {{
                put("connect0", singleton("(Ljava/net/InetAddress;I)V"));
                put("peekData", singleton("(Ljava/net/DatagramPacket;)I"));
                put("send", singleton("(Ljava/net/DatagramPacket;)V"));
                put("send0", singleton("(Ljava/net/DatagramPacket;)V"));
            }});

            put("java/net/PlainSocketImpl", new HashMap<String, Set<String>>() {{
                put("socketAccept", singleton("(Ljava/net/SocketImpl;)V"));
            }});

            put("java/net/ServerSocket", new HashMap<String, Set<String>>() {{
                put("implAccept", singleton("(Ljava/net/Socket;)V"));
            }});

            put("java/net/SocketInputStream", new HashMap<String, Set<String>>() {{
                put("socketRead0", singleton("(Ljava/io/FileDescriptor;[BIII)I"));
            }});

            put("java/net/Socket$SocketInputStream", new HashMap<String, Set<String>>() {{
                put("read", singleton("([BII)I"));
            }});

            put("java/net/SocketOutputStream", new HashMap<String, Set<String>>() {{
                put("socketWrite0", singleton("(Ljava/io/FileDescriptor;[BII)V"));
            }});

            put("java/net/Socket$SocketOutputStream", new HashMap<String, Set<String>>() {{
                put("write", singleton("([BII)V"));
            }});

            put("java/io/FileInputStream", new HashMap<String, Set<String>>() {{
                put("read0", singleton("()I"));
                put("readBytes", singleton("([BII)I"));
            }});

            put("java/io/FileOutputStream", new HashMap<String, Set<String>>() {{
                put("write", singleton("(IZ)V"));
                put("writeBytes", singleton("([BIIZ)V"));
            }});

            if (InstrumentationUtils.jdkMajorVersion >= 9) {
                put("jdk/internal/misc/Unsafe", new HashMap<String, Set<String>>() {{
                    put("park", singleton("(ZJ)V"));
                }});
                put("java/lang/ProcessImpl", new HashMap<String, Set<String>>() {{
                    put("forkAndExec", singleton("(I[B[B[BI[BI[B[IZ)I"));
                }});
            }
            else {
                put("sun/misc/Unsafe", new HashMap<String, Set<String>>() {{
                    put("park", singleton("(ZJ)V"));
                }});
                put("java/lang/UNIXProcess", new HashMap<String, Set<String>>() {{
                    put("forkAndExec", singleton("(I[B[B[BI[BI[B[IZ)I"));
                }});
            }

            if (InstrumentationUtils.jdkMajorVersion < 19) {
                // for jdk version < 19, the native method for Thread.sleep is "sleep"
                put("java/lang/Thread", new HashMap<String, Set<String>>() {{
                    put("sleep", singleton("(J)V"));
                    put("yield", singleton("()V"));
                    put("onSpinWait", singleton("()V"));
                }});
            }
            else if (InstrumentationUtils.jdkMajorVersion >= 19 && InstrumentationUtils.jdkMajorVersion <= 21) {
                // for jdk version in the range [19, 21], the native method for Thread.sleep is "sleep0"
                put("java/lang/Thread", new HashMap<String, Set<String>>() {{
                    put("sleep0", singleton("(J)V"));
                    put("yield0", singleton("()V"));
                    put("onSpinWait", singleton("()V"));
                }});
            }
            else {
                // for jdk version >= 22, the native method for Thread.sleep is "sleepNanos0"
                put("java/lang/Thread", new HashMap<String, Set<String>>() {{
                    put("sleepNanos0", singleton("(J)V"));
                    put("yield0", singleton("()V"));
                    put("onSpinWait", singleton("()V"));
                }});
            }
        }};

        private final Map<String, Map<String, Boolean>> allowances = new HashMap<String, Map<String, Boolean>>() {{
            put(ClassLoader.class.getName(), new HashMap<String, Boolean>() {{
                put("loadClass", true);
            }});
            put(Throwable.class.getName(), new HashMap<String, Boolean>() {{
                put("printStackTrace", true);
            }});

            put(ConcurrentHashMap.class.getName(), new HashMap<String, Boolean>() {{
                put("initTable", true);
            }});

            put(Advice.class.getName(), new HashMap<String, Boolean>() {{
                put("to", true);
            }});
        }};

        private Consumer<BlockingMethod> onBlockingMethod = method -> {
            Error error = new BlockingOperationError(method);

            // Strip BlockHound's internal noisy frames from the stacktrace to not mislead the users
            StackTraceElement[] stackTrace = error.getStackTrace();
            int length = stackTrace.length;
            for (int i = 0; i < length; i++) {
                StackTraceElement stackTraceElement = stackTrace[i];
                if (!BlockHoundRuntime.class.getName().equals(stackTraceElement.getClassName())) {
                    continue;
                }

                if ("checkBlocking".equals(stackTraceElement.getMethodName())) {
                    if (i + 1 < length) {
                        error.setStackTrace(Arrays.copyOfRange(stackTrace, i + 1, length));
                    }
                    break;
                }
            }

            throw error;
        };

        private Predicate<Thread> threadPredicate = t -> false;

        private Predicate<Thread> dynamicThreadPredicate = t -> false;

        private Instrumentation configuredInstrumentation;

        /**
         * Marks provided method of the provided class as "blocking".
         *
         * The descriptor should be in JVM's format:
         * https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html#wp276
         *
         * @param clazz a class reference
         * @param methodName a method name
         * @param signature a method descriptor in JVM's format
         * @return this
         */
        public Builder markAsBlocking(Class clazz, String methodName, String signature) {
            return markAsBlocking(clazz.getName(), methodName, signature);
        }

        /**
         * Marks provided method of the class identified by the provided name as "blocking".
         *
         * The descriptor should be in JVM's format:
         * https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html#wp276
         *
         * @param className class' name (e.g. "java.lang.Thread")
         * @param methodName a method name
         * @param signature a method signature (in JVM's format)
         * @return this
         */
        public Builder markAsBlocking(String className, String methodName, String signature) {
            blockingMethods.computeIfAbsent(className.replace(".", "/"), __ -> new HashMap<>())
                           .computeIfAbsent(methodName, __ -> new HashSet<>())
                           .add(signature);
            return this;
        }

        /**
         * Allows blocking calls inside any method of a class with name identified by the provided className
         * and which name matches the provided methodName.
         * <p>
         * There are two special cases for {@code methodName}:
         * <ul>
         *     <li>
         *     static initializers are currently supported by their JVM reserved name of {@code "<clinit>"}
         *     </li>
         *     <li>
         *     constructors are currently not supported (ByteBuddy cannot weave the necessary instrumentation around a constructor that throws an exception, see gh174)
         *     </li>
         * </ul>
         *
         * @param className class' name (e.g. "java.lang.Thread")
         * @param methodName a method name
         * @return this
         */
        // see https://github.com/reactor/BlockHound/issues/174
        public Builder allowBlockingCallsInside(String className, String methodName) {
            allowances.computeIfAbsent(className, __ -> new HashMap<>()).put(methodName, true);
            return this;
        }

        /**
         * Disallows blocking calls inside any method of a class with name identified by the provided className
         * and which name matches the provided methodName.
         * <p>
         * There are two special cases for {@code methodName}:
         * <ul>
         *     <li>
         *     static initializers are currently supported by their JVM reserved name of {@code "<clinit>"}
         *     </li>
         *     <li>
         *     constructors are currently not supported (ByteBuddy cannot weave the necessary instrumentation around a constructor that throws an exception, see gh174)
         *     </li>
         * </ul>
         *
         * @param className class' name (e.g. "java.lang.Thread")
         * @param methodName a method name
         * @return this
         */
        // see https://github.com/reactor/BlockHound/issues/174
        public Builder disallowBlockingCallsInside(String className, String methodName) {
            allowances.computeIfAbsent(className, __ -> new HashMap<>()).put(methodName, false);
            return this;
        }

        /**
         * Overrides the callback that is being triggered when a blocking method is detected
         * @param consumer a consumer of the detected blocking method call's description ({@link BlockingMethod}).
         *
         * @return this
         */
        public Builder blockingMethodCallback(Consumer<BlockingMethod> consumer) {
            this.onBlockingMethod = consumer;
            return this;
        }

        /**
         * Replaces the current non-blocking thread predicate with the result of applying the provided function.
         *
         * Warning! Consider always using {@link Predicate#or(Predicate)} and not override the previous one:
         * <code>
         * nonBlockingThreadPredicate(current -&gt; current.or(MyMarker.class::isInstance))
         * </code>
         *
         * @param function a function to immediately apply on the current instance of the predicate
         * @return this
         */
        public Builder nonBlockingThreadPredicate(Function<Predicate<Thread>, Predicate<Thread>> function) {
            this.threadPredicate = function.apply(this.threadPredicate);
            return this;
        }

        /**
         * Replaces the current dynamic thread predicate with the result of applying the provided function.
         *
         * Warning! Consider always using {@link Predicate#or(Predicate)} and not override the previous one:
         * <code>
         * dynamicThreadPredicate(current -&gt; current.or(MyMarker.class::isInstance))
         * </code>
         *
         * @param function a function to immediately apply on the current instance of the predicate
         * @return this
         */
        public Builder dynamicThreadPredicate(Function<Predicate<Thread>, Predicate<Thread>> function) {
            this.dynamicThreadPredicate = function.apply(this.dynamicThreadPredicate);
            return this;
        }

        /**
         * Appends the provided predicate to the current one.
         *
         * @param predicate a predicate to append to the current instance of the predicate
         * @return this
         */
        public Builder addDynamicThreadPredicate(Predicate<Thread> predicate) {
            return dynamicThreadPredicate(p -> p.or(predicate));
        }

        /**
         * Loads integrations with {@link ServiceLoader} and adds provided integrations
         * using {{@link #with(BlockHoundIntegration)}}.
         * If you don't want to load the integrations using service loader, only use
         * {@link #with(BlockHoundIntegration)} method.
         *
         * @param integrations an array of integrations to automatically apply on the builder using
         * {@link #with(BlockHoundIntegration)}
         * @return this
         * @see BlockHound#builder()
         */
        public Builder loadIntegrations(BlockHoundIntegration... integrations) {
            ServiceLoader<BlockHoundIntegration> serviceLoader = ServiceLoader.load(BlockHoundIntegration.class);
            Stream
                    .concat(StreamSupport.stream(serviceLoader.spliterator(), false), Stream.of(integrations))
                    .sorted()
                    .forEach(this::with);
            return this;
        }

        /**
         * Applies the provided {@link BlockHoundIntegration} to the current builder
         * @param integration an integration to apply
         * @return this
         */
        public Builder with(BlockHoundIntegration integration) {
            integration.applyTo(this);
            return this;
        }

        /**
         * Configure the {@link Instrumentation} to use. If not provided, {@link ByteBuddyAgent#install()} is used.
         *
         * @param instrumentation The instrumentation instance to use.
         * @return this
         */
        public Builder with(Instrumentation instrumentation) {
            this.configuredInstrumentation = instrumentation;
            return this;
        }

        Builder() {
        }

        /**
         * Installs the agent and runs the instrumentation, but only if BlockHound wasn't installed yet (it is global).
         */
        public void install() {
            if (!INITIALIZED.compareAndSet(false, true)) {
                return;
            }

            Consumer<BlockingMethod> originalOnBlockingMethod = onBlockingMethod;
            try {
                Instrumentation instrumentation = configuredInstrumentation == null ?
                        ByteBuddyAgent.install() : configuredInstrumentation;
                InstrumentationUtils.injectBootstrapClasses(
                        instrumentation,
                        BLOCK_HOUND_RUNTIME_TYPE.getInternalName(),
                        "reactor/blockhound/BlockHoundRuntime$State"
                );

                // Since BlockHoundRuntime is injected into the bootstrap classloader,
                // we use raw Object[] here instead of `BlockingMethod` to avoid classloading issues
                BlockHoundRuntime.blockingMethodConsumer = args -> {
                    String className = (String) args[0];
                    String methodName = (String) args[1];
                    int modifiers = (Integer) args[2];
                    onBlockingMethod.accept(new BlockingMethod(className, methodName, modifiers));
                };

                onBlockingMethod = m -> {
                    Thread currentThread = Thread.currentThread();
                    if (currentThread instanceof TestThread) {
                        ((TestThread) currentThread).blockingCallDetected = true;
                    }
                };
                BlockHoundRuntime.dynamicThreadPredicate = t -> false;
                BlockHoundRuntime.threadPredicate = TestThread.class::isInstance;

                instrument(instrumentation);
            }
            catch (Throwable e) {
                throw new RuntimeException(e);
            }

            testInstrumentation();

            // Eagerly trigger the classloading of `dynamicThreadPredicate` (since classloading is blocking)
            dynamicThreadPredicate.test(Thread.currentThread());
            BlockHoundRuntime.dynamicThreadPredicate = dynamicThreadPredicate;

            // Eagerly trigger the classloading of `threadPredicate` (since classloading is blocking)
            threadPredicate.test(Thread.currentThread());
            BlockHoundRuntime.threadPredicate = threadPredicate;

            onBlockingMethod = originalOnBlockingMethod;

            // Re-evaluate the current thread's state after assigning user-provided predicates
            BlockHoundRuntime.STATE.remove();
        }

        private void testInstrumentation() {
            TestThread thread = new TestThread();
            thread.startAndWait();

            // Set in the artificial blockingMethodConsumer, see install()
            if (thread.blockingCallDetected) {
                return;
            }

            String message = "The instrumentation have failed.";
            try {
                // Test some public API class added in Java 13
                Class.forName("sun.nio.ch.NioSocketImpl");
                message += "\n";
                message += "It looks like you're running on JDK 13+.\n";
                message += "You need to add '-XX:+AllowRedefinitionToAddDeleteMethods' JVM flag.\n";
                message += "See https://github.com/reactor/BlockHound/issues/33 for more info.";
            }
            catch (ClassNotFoundException ignored) {
            }

            throw new IllegalStateException(message);
        }

        private void instrument(Instrumentation instrumentation) {
            ClassFileTransformer transformer = new NativeWrappingClassFileTransformer(blockingMethods);
            instrumentation.addTransformer(transformer, true);
            instrumentation.setNativeMethodPrefix(transformer, PREFIX);

            new AgentBuilder.Default()
                    .with(RedefinitionStrategy.RETRANSFORMATION)
                    // Explicit strategy is almost 2 times faster than SinglePass
                    // TODO https://github.com/raphw/byte-buddy/issues/715
                    .with(new DiscoveryStrategy.Explicit(
                            Stream
                                    .of(instrumentation.getAllLoadedClasses())
                                    .filter(it -> it.getName() != null)
                                    .filter(it -> {
                                        if (allowances.containsKey(it.getName())) {
                                            return true;
                                        }

                                        String internalClassName = it.getName().replace(".", "/");
                                        if (blockingMethods.containsKey(internalClassName)) {
                                            return true;
                                        }

                                        return false;
                                    })
                                    .toArray(Class[]::new)
                    ))
                    .with(TypeStrategy.Default.DECORATE)
                    .with(InitializationStrategy.NoOp.INSTANCE)
                    // this DescriptionStrategy is required to force ByteBuddy to parse the bytes
                    // and not cache them, since we run another transformer (see NativeWrappingClassFileTransformer)
                    // before ByteBuddy
                    .with(DescriptionStrategy.Default.POOL_FIRST)
                    // Override PoolStrategy because the default one will cache java.lang.Object,
                    // and we need to instrument it.
                    .with(BlockHoundPoolStrategy.INSTANCE)
                    .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())

                    // Do not ignore JDK classes
                    .ignore(ElementMatchers.none())

                    // Instrument blocking calls
                    .type(it -> blockingMethods.containsKey(it.getInternalName()))
                    .transform(new BlockingCallsByteBuddyTransformer(blockingMethods))
                    .asTerminalTransformation()

                    // Instrument allowed/disallowed methods
                    .type(it -> allowances.containsKey(it.getName()))
                    .transform(new AllowancesByteBuddyTransformer(allowances))
                    .asTerminalTransformation()

                    .installOn(instrumentation);
        }
    }
}
