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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class BlockHoundRuntime {

    static {
        try {
            Path tempFile = Files.createTempFile("BlockHound", ".dylib");
            try (InputStream inputStream = BlockHound.class.getResource("/" + System.mapLibraryName("BlockHound")).openStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            System.load(tempFile.toAbsolutePath().toString());
        }
        catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    public static native void markMethod(Class clazz, String methodName, boolean allowed);

    private static native boolean hook();

    private static volatile boolean initialized = false;

    @SuppressWarnings("unused")
    public static void checkBlocking(String className, String methodName, boolean isStatic) {
        if (initialized && hook()) {
            throw new Error(String.format("Blocking call! %s%s%s", className, isStatic ? "." : "#", methodName));
        }
    }
}
