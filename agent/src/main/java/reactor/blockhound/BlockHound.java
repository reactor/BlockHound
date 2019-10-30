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
        Builder builder = builder();
        ServiceLoader<BlockHoundIntegration> serviceLoader = ServiceLoader.load(BlockHoundIntegration.class);
        Stream
                .concat(StreamSupport.stream(serviceLoader.spliterator(), false), Stream.of(integrations))
                .sorted()
                .forEach(builder::with);
        builder.install();
    }

    private BlockHound() {

    }

    public static class Builder {

        private final Map<String, Map<String, Set<String>>> blockingMethods = new HashMap<String, Map<String, Set<String>>>() {{
            put("java/lang/Thread", new HashMap<String, Set<String>>() {{
                put("sleep", singleton("(J)V"));
                put("yield", singleton("()V"));
                put("onSpinWait", singleton("()V"));
            }});

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
            }});

            put("java/net/PlainSocketImpl", new HashMap<String, Set<String>>() {{
                put("socketAccept", singleton("(Ljava/net/SocketImpl;)V"));
            }});

            put("java/net/SocketInputStream", new HashMap<String, Set<String>>() {{
                put("socketRead0", singleton("(Ljava/io/FileDescriptor;[BIII)I"));
            }});

            put("java/net/SocketOutputStream", new HashMap<String, Set<String>>() {{
                put("socketWrite0", singleton("(Ljava/io/FileDescriptor;[BII)V"));
            }});

            put("java/io/FileInputStream", new HashMap<String, Set<String>>() {{
                put("read0", singleton("()I"));
                put("readBytes", singleton("([BII)I"));
            }});

            put("java/io/FileOutputStream", new HashMap<String, Set<String>>() {{
                put("write", singleton("(IZ)V"));
                put("writeBytes", singleton("([BIIZ)V"));
            }});

            try {
                // Check if Java 9+
                Class.forName("java.lang.StackWalker");

                put("jdk/internal/misc/Unsafe", new HashMap<String, Set<String>>() {{
                    put("park", singleton("(ZJ)V"));
                }});
                put("java/lang/ProcessImpl", new HashMap<String, Set<String>>() {{
                    put("forkAndExec", singleton("(I[B[B[BI[BI[B[IZ)I"));
                }});
            }
            catch (ClassNotFoundException __) {
                put("sun/misc/Unsafe", new HashMap<String, Set<String>>() {{
                    put("park", singleton("(ZJ)V"));
                }});
                put("java/lang/UNIXProcess", new HashMap<String, Set<String>>() {{
                    put("forkAndExec", singleton("(I[B[B[BI[BI[B[IZ)I"));
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
         *
         * @param className class' name (e.g. "java.lang.Thread")
         * @param methodName a method name
         * @return this
         */
        public Builder allowBlockingCallsInside(String className, String methodName) {
            allowances.computeIfAbsent(className, __ -> new HashMap<>()).put(methodName, true);
            return this;
        }

        /**
         * Disallows blocking calls inside any method of a class with name identified by the provided className
         * and which name matches the provided methodName.
         *
         * @param className class' name (e.g. "java.lang.Thread")
         * @param methodName a method name
         * @return this
         */
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
         * Applies the provided {@link BlockHoundIntegration} to the current builder
         * @param integration an integration to apply
         * @return this
         */
        public Builder with(BlockHoundIntegration integration) {
            integration.applyTo(this);
            return this;
        }

        Builder() {
        }

        /**
         * Installs the agent and runs the instrumentation, but only if BlockHound wasn't installed yet (it is global).
         */
        public void install() {
            try {
                if (!INITIALIZED.compareAndSet(false, true)) {
                    return;
                }

                Instrumentation instrumentation = ByteBuddyAgent.install();
                InstrumentationUtils.injectBootstrapClasses(instrumentation, BLOCK_HOUND_RUNTIME_TYPE.getInternalName());

                // Since BlockHoundRuntime is injected into the bootstrap classloader,
                // we use raw Object[] here instead of `BlockingMethod` to avoid classloading issues
                BlockHoundRuntime.blockingMethodConsumer = args -> {
                    String className = (String) args[0];
                    String methodName = (String) args[1];
                    int modifiers = (Integer) args[2];
                    onBlockingMethod.accept(new BlockingMethod(className, methodName, modifiers));
                };

                // Eagerly trigger the classloading of `threadPredicate` (since classloading is blocking)
                threadPredicate.test(Thread.currentThread());
                BlockHoundRuntime.threadPredicate = threadPredicate;

                instrument(instrumentation);
            }
            catch (Throwable e) {
                throw new RuntimeException(e);
            }
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
                    .with((PoolStrategy) (classFileLocator, classLoader) -> new TypePool.Default(
                            new CacheProvider.Simple(),
                            classFileLocator,
                            TypePool.Default.ReaderMode.FAST
                    ))
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
