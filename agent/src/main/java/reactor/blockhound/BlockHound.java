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
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.pool.TypePool.CacheProvider;
import net.bytebuddy.utility.JavaModule;
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

public class BlockHound {

    static final String PREFIX = "$$BlockHound$$_";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads integrations with {@link ServiceLoader}, adds provided integrations,
     * and installs the BlockHound instrumentation.
     * If you don't want to load the integrations, use {@link #builder()} method.
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
            throw new Error(String.format("Blocking call! %s", method));
        };

        private Predicate<Thread> threadPredicate = t -> false;

        public Builder markAsBlocking(Class clazz, String methodName, String signature) {
            return markAsBlocking(clazz.getName(), methodName, signature);
        }

        public Builder markAsBlocking(String className, String methodName, String signature) {
            blockingMethods.computeIfAbsent(className.replace(".", "/"), __ -> new HashMap<>())
                           .computeIfAbsent(methodName, __ -> new HashSet<>())
                           .add(signature);
            return this;
        }

        public Builder allowBlockingCallsInside(String className, String methodName) {
            allowances.computeIfAbsent(className, __ -> new HashMap<>()).put(methodName, true);
            return this;
        }

        public Builder disallowBlockingCallsInside(String className, String methodName) {
            allowances.computeIfAbsent(className, __ -> new HashMap<>()).put(methodName, false);
            return this;
        }

        public Builder blockingMethodCallback(Consumer<BlockingMethod> consumer) {
            this.onBlockingMethod = consumer;
            return this;
        }

        public Builder nonBlockingThreadPredicate(Function<Predicate<Thread>, Predicate<Thread>> predicate) {
            this.threadPredicate = predicate.apply(this.threadPredicate);
            return this;
        }

        public Builder with(BlockHoundIntegration integration) {
            integration.applyTo(this);
            return this;
        }

        Builder() {
        }

        public void install() {
            try {
                if (!INITIALIZED.compareAndSet(false, true)) {
                    return;
                }

                Instrumentation instrumentation = ByteBuddyAgent.install();
                InstrumentationUtils.injectBootstrapClasses(instrumentation, BLOCK_HOUND_RUNTIME_TYPE.getInternalName());

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

        private void instrument(Instrumentation instrumentation) throws Exception {
            ClassFileTransformer transformer = new NativeWrappingClassFileTransformer(blockingMethods);
            instrumentation.addTransformer(transformer, true);
            instrumentation.setNativeMethodPrefix(transformer, PREFIX);

            new AgentBuilder.Default()
                    .with(RedefinitionStrategy.RETRANSFORMATION)
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
                    .with(DescriptionStrategy.Default.POOL_FIRST)
                    .with((PoolStrategy) (classFileLocator, classLoader) -> new TypePool.Default(
                            new CacheProvider.Simple(),
                            classFileLocator,
                            TypePool.Default.ReaderMode.FAST
                    ))
                    .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                    // Do not ignore JDK classes
                    .ignore(ElementMatchers.none())
                    .type((typeDescription, classLoader, module, classBeingRedefined, protectionDomain) -> {
                        if (blockingMethods.containsKey(typeDescription.getInternalName())) {
                            return true;
                        }
                        if (allowances.containsKey(typeDescription.getName())) {
                            return true;
                        }
                        return false;
                    })
                    .transform(new BlockingCallsTransformer())
                    .transform(new AllowancesTransformer())
                    .installOn(instrumentation);
        }

        private class BlockingCallsTransformer implements AgentBuilder.Transformer {

            @Override
            public DynamicType.Builder<?> transform(
                    DynamicType.Builder<?> builder,
                    TypeDescription typeDescription,
                    ClassLoader classLoader,
                    JavaModule module
            ) {
                Map<String, Set<String>> methods = blockingMethods.get(typeDescription.getInternalName());

                if (methods == null) {
                    return builder;
                }

                AsmVisitorWrapper advice = Advice.withCustomMapping()
                        .bind(BlockingCallAdvice.ModifiersArgument.class, (type, method, assigner, argumentHandler, sort) -> {
                            return Advice.OffsetMapping.Target.ForStackManipulation.of(method.getModifiers());
                        })
                        .to(BlockingCallAdvice.class)
                        .on(method -> {
                            Set<String> descriptors = methods.get(method.getName());
                            return descriptors != null && descriptors.contains(method.getDescriptor());
                        });

                return builder.visit(advice);
            }
        }

        private class AllowancesTransformer implements AgentBuilder.Transformer {

            @Override
            public DynamicType.Builder<?> transform(
                    DynamicType.Builder<?> builder,
                    TypeDescription typeDescription,
                    ClassLoader classLoader,
                    JavaModule module
            ) {
                Map<String, Boolean> methods = allowances.get(typeDescription.getName());

                if (methods == null) {
                    return builder;
                }

                AsmVisitorWrapper advice = Advice
                        .withCustomMapping()
                        .bind(AllowAdvice.AllowedArgument.class, (type, method, assigner, argumentHandler, sort) -> {
                            return Advice.OffsetMapping.Target.ForStackManipulation.of(methods.get(method.getName()));
                        })
                        .to(AllowAdvice.class)
                        .on(method -> methods.containsKey(method.getName()));

                return builder.visit(advice);
            }
        }
    }
}
