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

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

/**
 * This transformer applies {@link BlockingCallAdvice} to every method
 * registered with {@link BlockHound.Builder#markAsBlocking(Class, String, String)}.
 */
class BlockingCallsByteBuddyTransformer implements AgentBuilder.Transformer {

    private Map<String, Map<String, Set<String>>> blockingMethods;

    BlockingCallsByteBuddyTransformer(Map<String, Map<String, Set<String>>> blockingMethods) {
        this.blockingMethods = blockingMethods;
    }

    @Override
    public DynamicType.Builder<?> transform(
            DynamicType.Builder<?> builder,
            TypeDescription typeDescription,
            ClassLoader classLoader,
            JavaModule module,
            ProtectionDomain protectionDomain
    ) {
        Map<String, Set<String>> methods = blockingMethods.get(typeDescription.getInternalName());

        if (methods == null) {
            return builder;
        }

        AsmVisitorWrapper advice = Advice.withCustomMapping()
                .bind(ModifiersArgument.Factory.INSTANCE)
                .to(BlockingCallAdvice.class)
                .on(method -> {
                    Set<String> descriptors = methods.get(method.getInternalName());
                    return descriptors != null && descriptors.contains(method.getDescriptor());
                });

        return builder.visit(advice);
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    @interface ModifiersArgument {

        /**
         * Binds advice method's argument annotated with {@link ModifiersArgument}
         * to method's modifiers (static, final, private, etc)
         */
        enum Factory implements Advice.OffsetMapping.Factory<ModifiersArgument> {
            INSTANCE;

            @Override
            public Class<ModifiersArgument> getAnnotationType() {
                return ModifiersArgument.class;
            }

            @Override
            public Advice.OffsetMapping make(
                    ParameterDescription.InDefinedShape target,
                    AnnotationDescription.Loadable<ModifiersArgument> annotation,
                    AdviceType adviceType
            ) {
                return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
                    int modifiers = instrumentedMethod.getModifiers();
                    return Advice.OffsetMapping.Target.ForStackManipulation.of(modifiers);
                };
            }
        }
    }

    static class BlockingCallAdvice {

        @Advice.OnMethodEnter
        static void onEnter(
                @Advice.Origin("#t") String declaringType,
                @Advice.Origin("#m") String methodName,
                @BlockingCallsByteBuddyTransformer.ModifiersArgument int modifiers
        ) {
            BlockHoundRuntime.checkBlocking(declaringType, methodName, modifiers);
        }
    }
}
