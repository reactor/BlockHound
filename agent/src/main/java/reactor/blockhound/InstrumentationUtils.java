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
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class InstrumentationUtils {

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

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            switch (name) {
                case "blockingMethodConsumer":
                case "threadPredicate":
                case "IS_ALLOWED":
                    access = access | Opcodes.ACC_PUBLIC;
                    break;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            switch (name) {
                case "checkBlocking":
                    access = access | Opcodes.ACC_PUBLIC;
                    break;
            }

            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }
}
