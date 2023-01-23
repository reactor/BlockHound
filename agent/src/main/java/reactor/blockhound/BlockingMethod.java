/*
 * Copyright (c) 2018-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.io.Serializable;

import static net.bytebuddy.jar.asm.Opcodes.ACC_STATIC;

public class BlockingMethod implements Serializable {

    private final String className;

    private final String name;

    private final int modifiers;

    public BlockingMethod(String className, String name, int modifiers) {
        this.className = className;
        this.name = name;
        this.modifiers = modifiers;
    }

    /**
     * @return a class' name of the detected blocking call (e.g. "java.lang.Thread")
     */
    public String getClassName() {
        return className;
    }

    /**
     * @return a blocking method's name (e.g. "sleep").
     */
    public String getName() {
        return name;
    }

    /**
     * @return a blocking methods' modifiers. see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html
     */
    public int getModifiers() {
        return modifiers;
    }

    public boolean isStatic() {
        return (getModifiers() & ACC_STATIC) != 0;
    }

    @Override
    public String toString() {
        return String.format("%s%s%s", className, isStatic() ? "." : "#", name);
    }
}
