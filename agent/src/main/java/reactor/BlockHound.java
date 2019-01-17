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

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.jar.asm.Opcodes.*;

public class BlockHound {

    private static final String PREFIX = "$$BlockHound$$_";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static Builder builder() {
        return new Builder();
    }

    public static void install() {
        builder().install();
    }

    private static void injectBootstrapClasses(Instrumentation instrumentation) throws IOException {
        File tempJarFile = File.createTempFile("BlockHound", ".jar");

        ClassLoader classLoader = BlockHound.class.getClassLoader();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(tempJarFile))) {
            for (Class clazz : new Class[] { BlockHoundRuntime.class }) {
                String classFile = clazz.getName().replace(".", "/") + ".class";
                InputStream inputStream = classLoader.getResourceAsStream(classFile);
                ZipEntry e = new ZipEntry(classFile);
                zipOutputStream.putNextEntry(e);

                byte[] buf = new byte[4096];
                int n;
                while ((n = inputStream.read(buf)) > 0) {
                    zipOutputStream.write(buf, 0, n);
                }

                zipOutputStream.closeEntry();
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJarFile));
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
            try {
                HashMap<String, Boolean> publisherMethods = new HashMap<String, Boolean>() {{
                    put("subscribe", false);
                    put("onNext", false);
                    put("onError", false);
                    put("onComplete", false);
                }};
                put(Class.forName("reactor.core.publisher.Flux"), publisherMethods);
                put(Class.forName("reactor.core.publisher.Mono"), publisherMethods);
                put(Class.forName("reactor.core.publisher.ParallelFlux"), publisherMethods);

                put(Class.forName("reactor.core.scheduler.SchedulerTask"), singletonMap("call", false));
                put(Class.forName("reactor.core.scheduler.WorkerTask"), singletonMap("call", false));
                put(Class.forName("reactor.core.scheduler.PeriodicWorkerTask"), singletonMap("call", false));
                put(Class.forName("reactor.core.scheduler.InstantPeriodicWorkerTask"), singletonMap("call", false));

                put(Class.forName("reactor.core.scheduler.Schedulers"), new HashMap<String, Boolean>() {{
                    put("workerSchedule", true);
                    put("workerSchedulePeriodically", true);
                }});

                put(ClassLoader.class, singletonMap("loadClass", true));
            }
            catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }

            try {
                put(Class.forName("org.gradle.internal.io.LineBufferingOutputStream"), singletonMap("write", true));
            } catch (ClassNotFoundException __) {
            }

            try {
                put(Class.forName("ch.qos.logback.classic.Logger"), singletonMap("callAppenders", true));
            } catch (ClassNotFoundException e) {
            }
        }};

        private Consumer<BlockingMethod> onBlockingMethod = method -> {
            throw new Error(String.format("Blocking call! %s%s%s", method.getClassName(), method.isStatic() ? "." : "#", method.getName()));
        };

        public Builder markAsBlocking(Class clazz, String methodName, String signature) {
            blockingMethods.computeIfAbsent(clazz.getCanonicalName().replace(".", "/"), __ -> new HashMap<>())
                           .computeIfAbsent(methodName, __ -> new HashSet<>())
                           .add(signature);
            return this;
        }

        public Builder allowBlockingCallsInside(Class clazz, String methodName) {
            allowances.computeIfAbsent(clazz, __ -> new HashMap<>()).put(methodName, true);
            return this;
        }

        public Builder disallowBlockingCallsInside(Class clazz, String methodName) {
            allowances.computeIfAbsent(clazz, __ -> new HashMap<>()).put(methodName, false);
            return this;
        }

        public Builder blockingMethodCallback(Consumer<BlockingMethod> consumer) {
            this.onBlockingMethod = consumer;
            return this;
        }

        public Builder() {
        }

        public void install() {
            try {
                if (!INITIALIZED.compareAndSet(false, true)) {
                    return;
                }

                Instrumentation instrumentation = ByteBuddyAgent.install();

                injectBootstrapClasses(instrumentation);

                Method markMethod;
                try {
                    Class<?> aClass = ClassLoader.getSystemClassLoader().getParent().loadClass(BlockHoundRuntime.class.getCanonicalName());
                    markMethod = aClass.getMethod("markMethod", Class.class, String.class, boolean.class);
                }
                catch (Throwable e) {
                    throw new RuntimeException(e);
                }

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

                Class<?> aClass = ClassLoader.getSystemClassLoader().getParent().loadClass(BlockHoundRuntime.class.getCanonicalName());
                Field initializedField = aClass.getDeclaredField("initialized");
                initializedField.setAccessible(true);
                initializedField.setBoolean(null, true);

                Field blockingMethodConsumerField = aClass.getDeclaredField("blockingMethodConsumer");
                blockingMethodConsumerField.setAccessible(true);
                blockingMethodConsumerField.set(null, (Consumer<Object[]>) args -> {
                    String className = (String) args[0];
                    String methodName = (String) args[1];
                    int modifiers = (Integer) args[2];
                    onBlockingMethod.accept(new BlockingMethod(className, methodName, modifiers));
                });
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

        BlockingClassFileTransformer(Map<String, Map<String, Set<String>>> blockingMethods) {
            this.blockingMethods = blockingMethods;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            Map<String, Set<String>> methods = blockingMethods.get(className);
            if (methods == null) {
                return classfileBuffer;
            }
            try {
                ClassPool cp = ClassPool.getDefault();
                cp.appendClassPath(new LoaderClassPath(loader));

                CtClass ct = cp.makeClass(new ByteArrayInputStream(classfileBuffer));

                for (Map.Entry<String, Set<String>> methodEntry : methods.entrySet()) {
                    String methodName = methodEntry.getKey();

                    for (String signature : methodEntry.getValue()) {
                        final CtMethod oldMethod;
                        try {
                            oldMethod = ct.getMethod(methodName, signature);
                        } catch (NotFoundException e) {
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

                return ct.toBytecode();
            }
            catch (Throwable e) {
                e.printStackTrace();
            }

            return classfileBuffer;
        }
    }
}
