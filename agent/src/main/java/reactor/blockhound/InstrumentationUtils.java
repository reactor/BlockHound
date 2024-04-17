/*
 * Copyright (c) 2018-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.blockhound;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class InstrumentationUtils {

    /**
     * Constant used to indicate the current JDK major version (8,9,..22,...)
     */
    static final int jdkMajorVersion;

    static {
        try {
            jdkMajorVersion = getJdkMajorVersion();
        }
        catch (InvocationTargetException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void injectBootstrapClasses(Instrumentation instrumentation, String... classNames) throws IOException {
        File tempJarFile = File.createTempFile("BlockHound", ".jar");
        tempJarFile.deleteOnExit();

        ClassLoader classLoader = BlockHound.class.getClassLoader();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(tempJarFile))) {
            for (String className : classNames) {
                String classFile = className.replace(".", "/") + ".class";
                try (InputStream inputStream = classLoader.getResourceAsStream(classFile)) {
                    ZipEntry entry = new ZipEntry(classFile);
                    zipOutputStream.putNextEntry(entry);

                    ClassReader cr = new ClassReader(inputStream);
                    ClassWriter cw = new ClassWriter(cr, 0);

                    cr.accept(new MakePublicClassVisitor(cw), 0);

                    zipOutputStream.write(cw.toByteArray());
                }

                zipOutputStream.closeEntry();
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJarFile));
    }

    /**
     * Makes the class, fields and methods public
     */
    static class MakePublicClassVisitor extends ClassVisitor {

        MakePublicClassVisitor(ClassWriter cw) {
            super(Opcodes.ASM7, cw);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access | Opcodes.ACC_PUBLIC, name, signature, superName, interfaces);
        }
    }

    /**
     * Helper method to detect the current JDK major version.
     * For security reasons, we don't rely on "java.version" system property, but on Runtime.version() method, which is
     * available from JDK9 +
     * And starting from JDK10+, we rely on Runtime.version().feature() method.
     *
     * @return the current jdk major version (8, 9, 10, ... 22)
     */
    private static int getJdkMajorVersion() throws InvocationTargetException, IllegalAccessException {
        Object version = getRuntimeVersion();

        if (version == null) {
            return 8; // Runtime.version() not available, JDK 8
        }

        return getRuntimeVersionFeature(version);
    }

    /**
     * Detects the Runtime.version() object, or null if JDK version is < JDK 9
     *
     * @return the detected JDK version object or null if not available
     */
    private static Object getRuntimeVersion() throws InvocationTargetException, IllegalAccessException {
        Runtime runtime = Runtime.getRuntime();
        try {
            Method versionMethod = runtime.getClass().getMethod("version");
            return versionMethod.invoke(null);
        }

        catch (NoSuchMethodException e) {
            // Method Runtime.version() not found -> return null, meaning JDK 8
            return null; // JDK 8
        }
    }

    /**
     * Extracts the major version from the JDK version object.
     *
     * @param version the JDK version object
     * @return the major version (9, 10, ...)
     */
    private static int getRuntimeVersionFeature(Object version) throws InvocationTargetException, IllegalAccessException {
        try {
            Method featureMethod = version.getClass().getMethod("feature");
            Object feature = featureMethod.invoke(version);
            return (int) feature;
        }

        catch (NoSuchMethodException e) {
            // Version.feature() method not found -> JDK 9 (because feature method is only available starting from JDK10 +)
            return 9;
        }
    }

}
