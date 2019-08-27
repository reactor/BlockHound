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

import net.bytebuddy.asm.Advice;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

class AllowAdvice {

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    @interface AllowedArgument {
    }

    @Advice.OnMethodEnter
    static boolean onEnter(@AllowedArgument boolean allowed) {
        Boolean previous = BlockHoundRuntime.IS_ALLOWED.get();
        if (previous == null || previous == allowed) {
            return allowed;
        }
        BlockHoundRuntime.IS_ALLOWED.set(allowed);
        return previous;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void onExit(@Advice.Enter boolean previous, @AllowedArgument boolean allowed) {
        if (previous != allowed) {
            BlockHoundRuntime.IS_ALLOWED.set(previous);
        }
    }
}
