/*
 * Copyright (c) 2019-Present Pivotal Software Inc, All Rights Reserved.
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

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * This ASM-based transformer finds all methods defined in {@link NativeWrappingClassFileTransformer#blockingMethods}
 * and creates a delegating method by prefixing the original native method.
 *
 */
class NativeWrappingClassFileTransformer implements ClassFileTransformer {

    static final Type BLOCK_HOUND_RUNTIME_TYPE = Type.getType("Lreactor/blockhound/BlockHoundRuntime;");

    private final Map<String, Map<String, Set<String>>> blockingMethods;

    NativeWrappingClassFileTransformer(final Map<String, Map<String, Set<String>>> blockingMethods) {
        this.blockingMethods = blockingMethods;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        Map<String, Set<String>> blockingMethodsOfClass = blockingMethods.get(className);
        if (blockingMethodsOfClass == null) {
            return null;
        }

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

        try {
            cr.accept(new NativeWrappingClassVisitor(cw, blockingMethods.get(className), className), 0);

            classfileBuffer = cw.toByteArray();

            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            CheckClassAdapter.verify(new ClassReader(classfileBuffer), false, printWriter);
            String output = stringWriter.toString();
            if (!output.isEmpty()) {
                System.err.println("Verification failed for " + className + ":\n" + output);
            }
        }
        catch (Throwable e) {
            e.printStackTrace();
            throw e;
        }

        return classfileBuffer;
    }

    static class NativeWrappingClassVisitor extends ClassVisitor {

        private final String className;

        private final Map<String, Set<String>> methods;

        NativeWrappingClassVisitor(ClassVisitor cw, Map<String, Set<String>> methods, String className) {
            super(Opcodes.ASM7, cw);
            this.className = className;
            this.methods = methods;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & ACC_NATIVE) == 0) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            if (methods == null) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            Set<String> descriptors = methods.get(name);

            if (descriptors == null || !descriptors.contains(descriptor)) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            super.visitMethod(
                    ACC_NATIVE | ACC_PRIVATE | ACC_FINAL | (access & ACC_STATIC),
                    BlockHound.PREFIX + name,
                    descriptor,
                    signature,
                    exceptions
            );

            MethodVisitor delegatingMethodVisitor = super.visitMethod(access & ~ACC_NATIVE, name, descriptor, signature, exceptions);
            delegatingMethodVisitor.visitCode();

            return new GeneratorAdapter(ASM7, delegatingMethodVisitor, access & ~ACC_NATIVE, name, descriptor) {

                @Override
                public void visitEnd() {
                    boolean isStatic = (access & ACC_STATIC) != 0;
                    if (!isStatic) {
                        loadThis();
                    }
                    loadArgs();
                    visitMethodInsn(
                            isStatic ? INVOKESTATIC : INVOKESPECIAL,
                            className,
                            BlockHound.PREFIX + name,
                            descriptor,
                            false
                    );
                    returnValue();
                    endMethod();
                }
            };
        }

    }
}
