/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.Collections.singleton;
import static net.bytebuddy.jar.asm.Opcodes.*;

public class BlockHound {

    private static final String PREFIX = "$$BlockHound$$_";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private static void markMethod(String className, String methodName, boolean allowed) {
        try {
            Class<?> aClass = ClassLoader.getSystemClassLoader().getParent().loadClass(BlockHoundRuntime.class.getCanonicalName());
            Method method = aClass.getMethod("markMethod", Class.class, String.class, boolean.class);
            method.invoke(null, Class.forName(className), methodName, allowed);
        }
        catch (ClassNotFoundException __) {
            return;
        }
        catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void install() {
        try {
            if (!INITIALIZED.compareAndSet(false, true)) {
                return;
            }

            Instrumentation instrumentation = ByteBuddyAgent.install();

            injectBootstrapClasses(instrumentation);

            markMethod("reactor.core.publisher.Flux", "subscribe", false);
            markMethod("reactor.core.publisher.Flux", "onNext", false);
            markMethod("reactor.core.publisher.Flux", "onError", false);
            markMethod("reactor.core.publisher.Flux", "onComplete", false);

            markMethod("reactor.core.publisher.Mono", "subscribe", false);
            markMethod("reactor.core.publisher.Mono", "onNext", false);
            markMethod("reactor.core.publisher.Mono", "onError", false);
            markMethod("reactor.core.publisher.Mono", "onComplete", false);

            markMethod("reactor.core.scheduler.SchedulerTask", "call", false);
            markMethod("reactor.core.scheduler.WorkerTask", "call", false);
            markMethod("reactor.core.scheduler.PeriodicWorkerTask", "call", false);
            markMethod("reactor.core.scheduler.InstantPeriodicWorkerTask", "call", false);

            markMethod("reactor.core.scheduler.Schedulers", "workerSchedule", true);
            markMethod("reactor.core.scheduler.Schedulers", "workerSchedulePeriodically", true);

            markMethod("java.lang.ClassLoader", "loadClass", true);
            markMethod("java.security.SecureRandom", "nextBytes", true);
            markMethod("org.gradle.internal.io.LineBufferingOutputStream", "write", true);
            markMethod("ch.qos.logback.classic.Logger", "callAppenders", true);

            Map<String, Map<String, Set<String>>> blockingMethods = new HashMap<String, Map<String, Set<String>>>() {{
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
            }};

            try {
                // Check if Java 9+
                Class.forName("java.lang.StackWalker");

                blockingMethods.put("jdk/internal/misc/Unsafe", new HashMap<String, Set<String>>() {{
                    put("park", singleton("(ZJ)V"));
                }});
                blockingMethods.put("java/lang/ProcessImpl", new HashMap<String, Set<String>>() {{
                    put("forkAndExec", singleton("(I[B[B[BI[BI[B[IZ)I"));
                }});
            }
            catch (ClassNotFoundException __) {
                blockingMethods.put("sun/misc/Unsafe", new HashMap<String, Set<String>>() {{
                    put("park", singleton("(ZJ)V"));
                }});
                blockingMethods.put("java/lang/UNIXProcess", new HashMap<String, Set<String>>() {{
                    put("forkAndExec", singleton("(I[B[B[BI[BI[B[IZ)I"));
                }});
            }

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
        }
        catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void injectBootstrapClasses(Instrumentation instrumentation) throws IOException {
        File tempJarFile = File.createTempFile("BlockHound", ".jar");

        ClassLoader classLoader = BlockHound.class.getClassLoader();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(tempJarFile))) {
            for (Class clazz : new Class[] { BlockHound.class, BlockHoundRuntime.class }) {
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
                        CtMethod oldMethod = ct.getMethod(methodName, signature);

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
                                ((newMethod.getModifiers() & ACC_STATIC) != 0) +
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
