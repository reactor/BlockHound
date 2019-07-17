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
import org.objectweb.asm.commons.Method;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * This ASM-based transformer finds all methods defined in {@link ASMClassFileTransformer#blockingMethods}
 * and adds {@link BlockHoundRuntime#checkBlocking(String, String, int)} call to them.
 *
 * In case of native methods, it will create a delegating method and prefix the original native method.
 *
 */
class ASMClassFileTransformer implements ClassFileTransformer {

    private static final Type BLOCK_HOUND_RUNTIME_TYPE = Type.getType("Lreactor/blockhound/BlockHoundRuntime;");

    private static final Method CHECK_BLOCKING_METHOD = new Method(
            "checkBlocking",
            Type.VOID_TYPE,
            new Type[] { Type.getType(String.class), Type.getType(String.class), Type.INT_TYPE }
    );

    private final Map<String, Map<String, Set<String>>> blockingMethods;

    ASMClassFileTransformer(final Map<String, Map<String, Set<String>>> blockingMethods) {
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
        if (!blockingMethods.containsKey(className)) {
            return null;
        }

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

        try {
            cr.accept(new InstrumentingClassVisitor(cw, className), 0);
        }
        catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        classfileBuffer = cw.toByteArray();
        return classfileBuffer;
    }

    private class InstrumentingClassVisitor extends ClassVisitor {

        private final String className;

        InstrumentingClassVisitor(ClassWriter cw, String className) {
            super(Opcodes.ASM7, cw);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            Map<String, Set<String>> methods = blockingMethods.get(className);

            if (methods == null) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            Set<String> descriptors = methods.get(name);

            if (descriptors == null || !descriptors.contains(descriptor)) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            if ((access & ACC_NATIVE) != 0) {
                super.visitMethod(
                        ACC_NATIVE | ACC_PRIVATE | ACC_FINAL | (access & ACC_STATIC),
                        BlockHound.PREFIX + name,
                        descriptor,
                        signature,
                        exceptions
                );

                MethodVisitor delegatingMethodVisitor = super.visitMethod(access & ~ACC_NATIVE, name, descriptor, signature, exceptions);

                return new GeneratorAdapter(ASM7, delegatingMethodVisitor, access & ~ACC_NATIVE, name, descriptor) {

                    @Override
                    public void visitEnd() {
                        callCheckBlocking(this, name, access);
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
            else {
                MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM7, methodVisitor) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        callCheckBlocking(this, name, access);
                    }
                };
            }
        }

        private void callCheckBlocking(MethodVisitor generatorAdapter, String name, int access) {
            generatorAdapter.visitLdcInsn(className.replace("/", "."));
            generatorAdapter.visitLdcInsn(name);
            generatorAdapter.visitLdcInsn(access);
            generatorAdapter.visitMethodInsn(
                    INVOKESTATIC,
                    BLOCK_HOUND_RUNTIME_TYPE.getInternalName(),
                    CHECK_BLOCKING_METHOD.getName(),
                    CHECK_BLOCKING_METHOD.getDescriptor(),
                    false
            );
        }

    }
}
