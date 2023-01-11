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

/**
 * This transformer applies {@link AllowAdvice} to every method
 * registered with {@link BlockHound.Builder#allowBlockingCallsInside(String, String)}.
 */
class AllowancesByteBuddyTransformer implements AgentBuilder.Transformer {

    private Map<String, Map<String, Boolean>> allowances;

    AllowancesByteBuddyTransformer(Map<String, Map<String, Boolean>> allowances) {
        this.allowances = allowances;
    }

    @Override
    public DynamicType.Builder<?> transform(
            DynamicType.Builder<?> builder,
            TypeDescription typeDescription,
            ClassLoader classLoader,
            JavaModule module,
            ProtectionDomain protectionDomain
    ) {
        Map<String, Boolean> methods = allowances.get(typeDescription.getName());

        if (methods == null) {
            return builder;
        }

        AsmVisitorWrapper advice = Advice
                .withCustomMapping()
                .bind(new AllowedArgument.Factory(methods))
                .to(AllowAdvice.class)
                .on(method -> methods.containsKey(method.getInternalName()));

        return builder.visit(advice);
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    @interface AllowedArgument {

        /**
         * Binds advice method's argument annotated with {@link AllowedArgument}
         * to boolean where `true` means "allowed" and `false" means "disallowed"
         */
        class Factory implements Advice.OffsetMapping.Factory<AllowedArgument> {

            final Map<String, Boolean> methods;

            Factory(Map<String, Boolean> methods) {
                this.methods = methods;
            }

            @Override
            public Class<AllowedArgument> getAnnotationType() {
                return AllowedArgument.class;
            }

            @Override
            public Advice.OffsetMapping make(
                    ParameterDescription.InDefinedShape target,
                    AnnotationDescription.Loadable<AllowedArgument> annotation,
                    AdviceType adviceType
            ) {
                return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
                    boolean allowed = methods.get(instrumentedMethod.getInternalName());
                    return Advice.OffsetMapping.Target.ForStackManipulation.of(allowed);
                };
            }
        }
    }

    static class AllowAdvice {

        @Advice.OnMethodEnter
        static BlockHoundRuntime.State onEnter(
                @AllowancesByteBuddyTransformer.AllowedArgument boolean allowed
        ) {
            BlockHoundRuntime.State previous = BlockHoundRuntime.STATE.get();
            if (previous == null) {
                return null;
            }

            if (previous.isAllowed() == allowed) {
                // if we won't change the flag, return `null` and skip the `onExit` part
                return null;
            }

            // Otherwise, set to `allowed` and reset to `!allowed` in `onExit`
            previous.setAllowed(allowed);
            return previous;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void onExit(
                @Advice.Enter BlockHoundRuntime.State previousState,
                @AllowancesByteBuddyTransformer.AllowedArgument boolean allowed
        ) {
            if (previousState != null) {
                previousState.setAllowed(!allowed);
            }
        }
    }
}
