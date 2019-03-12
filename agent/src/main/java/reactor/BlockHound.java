/*
 * Copyright (c) 2018-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor;

import javassist.*;
import javassist.bytecode.AttributeInfo;
import net.bytebuddy.agent.ByteBuddyAgent;
import reactor.blockhound.integration.BlockHoundIntegration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Collections.singleton;
import static net.bytebuddy.jar.asm.Opcodes.*;

public class BlockHound {

    private static final String PREFIX = "$$BlockHound$$_";

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

        private final Map<Class<?>, Map<String, Boolean>> allowances = new HashMap<Class<?>, Map<String, Boolean>>() {{
            put(ClassLoader.class, new HashMap<String, Boolean>() {{
                put("loadClass", true);
            }});
            put(Throwable.class, new HashMap<String, Boolean>() {{
                put("printStackTrace", true);
            }});
        }};

        private Consumer<BlockingMethod> onBlockingMethod = method -> {
            throw new Error(String.format("Blocking call! %s", method));
        };

        private Predicate<Thread> threadPredicate = t -> false;

        public Builder markAsBlocking(Class clazz, String methodName, String signature) {
            return markAsBlocking(clazz.getCanonicalName(), methodName, signature);
        }

        public Builder markAsBlocking(String className, String methodName, String signature) {
            blockingMethods.computeIfAbsent(className.replace(".", "/"), __ -> new HashMap<>())
                           .computeIfAbsent(methodName, __ -> new HashSet<>())
                           .add(signature);
            return this;
        }

        public Builder allowBlockingCallsInside(String className, String methodName) {
            try {
                allowances.computeIfAbsent(Class.forName(className), __ -> new HashMap<>()).put(methodName, true);
            }
            catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public Builder disallowBlockingCallsInside(String className, String methodName) {
            try {
                allowances.computeIfAbsent(Class.forName(className), __ -> new HashMap<>()).put(methodName, false);
            }
            catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
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

                InstrumentationUtils.injectBootstrapClasses(instrumentation, BlockHoundRuntime.class);

                final Class<?> runtimeClass;
                final Method initMethod;
                try {
                    runtimeClass = ClassLoader.getSystemClassLoader().getParent().loadClass(BlockHoundRuntime.class.getCanonicalName());
                    initMethod = runtimeClass.getMethod("init", String.class);
                }
                catch (Throwable e) {
                    throw new RuntimeException(e);
                }
                initMethod.invoke(null, extractNativeLibFile().toString());

                final Method markMethod;
                try {
                    markMethod = runtimeClass.getMethod("markMethod", Class.class, String.class, boolean.class);
                }
                catch (Throwable e) {
                    throw new RuntimeException(e);
                }

                markMethod.invoke(null, runtimeClass, "checkBlocking", true);

                allowances.forEach((clazz, methods) -> methods.forEach((methodName, allowed) -> {
                    try {
                        markMethod.invoke(null, clazz, methodName, allowed);
                    }
                    catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

                ClassFileTransformer transformer = new BlockingClassFileTransformer(blockingMethods);

                instrumentation.addTransformer(transformer, true);
                instrumentation.setNativeMethodPrefix(transformer, PREFIX);

                for (Class clazz : instrumentation.getAllLoadedClasses()) {
                    try {
                        String canonicalName = clazz.getCanonicalName();
                        if (canonicalName == null) {
                            continue;
                        }
                        if (blockingMethods.containsKey(canonicalName.replace(".", "/"))) {
                            instrumentation.retransformClasses(clazz);
                        }
                    }
                    catch (NoClassDefFoundError e) {
                        continue;
                    }
                }

                Field initializedField = runtimeClass.getDeclaredField("initialized");
                initializedField.setAccessible(true);
                initializedField.setBoolean(null, true);

                Field blockingMethodConsumerField = runtimeClass.getDeclaredField("blockingMethodConsumer");
                blockingMethodConsumerField.setAccessible(true);
                blockingMethodConsumerField.set(null, (Consumer<Object[]>) args -> {
                    String className = (String) args[0];
                    String methodName = (String) args[1];
                    int modifiers = (Integer) args[2];
                    onBlockingMethod.accept(new BlockingMethod(className, methodName, modifiers));
                });

                Field threadPredicateField = runtimeClass.getDeclaredField("threadPredicate");
                threadPredicateField.setAccessible(true);
                threadPredicateField.set(null, threadPredicate);
            }
            catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * TODO use ByteBuddy instead of Javassist
     */
    private static class BlockingClassFileTransformer implements ClassFileTransformer {

        private final Map<String, Map<String, Set<String>>> blockingMethods;

        private final ClassPool cp;

        BlockingClassFileTransformer(Map<String, Map<String, Set<String>>> blockingMethods) {
            this.blockingMethods = blockingMethods;
            cp = ClassPool.getDefault();
            // Make it see bootstrap's BlockHoundRuntime class
            cp.appendClassPath(new LoaderClassPath(BlockHound.class.getClassLoader()));
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            Map<String, Set<String>> methods = blockingMethods.get(className);
            if (methods == null) {
                return classfileBuffer;
            }
            ClassPath classPath = new LoaderClassPath(loader);
            try {
                cp.appendClassPath(classPath);

                CtClass ct = cp.makeClass(new ByteArrayInputStream(classfileBuffer));

                for (Map.Entry<String, Set<String>> methodEntry : methods.entrySet()) {
                    String methodName = methodEntry.getKey();

                    for (String signature : methodEntry.getValue()) {
                        final CtMethod oldMethod;
                        try {
                            oldMethod = ct.getMethod(methodName, signature);
                        }
                        catch (NotFoundException e) {
                            continue;
                        }

                        CtMethod newMethod = oldMethod;
                        if ((oldMethod.getModifiers() & ACC_NATIVE) != 0) {
                            ct.removeMethod(oldMethod);
                            oldMethod.setName(PREFIX + oldMethod.getName());

                            newMethod = CtNewMethod.delegator(oldMethod, ct);
                            for (AttributeInfo attribute : oldMethod.getMethodInfo2().getAttributes()) {
                                if (attribute.getName().equals("RuntimeVisibleAnnotations")) {
                                    newMethod.getMethodInfo2().getAttributes().add(attribute);
                                }
                            }

                            newMethod.setName(methodName);
                            newMethod.setModifiers(oldMethod.getModifiers() & ~ACC_NATIVE);
                            ct.addMethod(newMethod);

                            oldMethod.setModifiers(ACC_NATIVE | ACC_PRIVATE | ACC_FINAL | (oldMethod.getModifiers() & ACC_STATIC));
                            // HotSpotIntrinsicCandidate...
                            oldMethod.getMethodInfo2().removeAttribute("RuntimeVisibleAnnotations");

                            ct.addMethod(oldMethod);
                        }

                        newMethod.insertBefore("{" +
                                "reactor.BlockHoundRuntime.checkBlocking(" +
                                "\"" + className.replace("/", ".") + "\"," +
                                "\"" + methodName + "\"," +
                                newMethod.getModifiers() +
                                ");" +
                                "}"
                        );
                    }
                }

                byte[] bytes = ct.toBytecode();
                ct.detach();
                return bytes;
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
            finally {
                cp.removeClassPath(classPath);
            }

            return classfileBuffer;
        }
    }

    private static Path extractNativeLibFile() throws IOException {
        String nativeLibraryFileName = System.mapLibraryName("BlockHound");
        URL nativeLibraryURL = BlockHound.class.getResource("/" + nativeLibraryFileName);

        if (nativeLibraryURL == null) {
            throw new IllegalStateException("Failed to load the following lib from a classpath: " + nativeLibraryFileName);
        }

        Path tempFile = Files.createTempFile("BlockHound", ".dylib");
        try (InputStream inputStream = nativeLibraryURL.openStream()) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile.toAbsolutePath();
    }
}
