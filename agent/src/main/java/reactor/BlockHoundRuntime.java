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

package reactor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class BlockHoundRuntime {

    static {
        try {
            Path tempFile = Files.createTempFile("BlockHound", ".dylib");
            try (InputStream inputStream = BlockHoundRuntime.class.getResource("/" + System.mapLibraryName("BlockHound")).openStream()) {
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

    private static native boolean isBlocking();

    private static volatile boolean initialized = false;

    @SuppressWarnings("unused")
    private static volatile Consumer<Object[]> blockingMethodConsumer;

    @SuppressWarnings("unused")
    private static volatile Predicate<Thread> threadPredicate;

    @SuppressWarnings("unused")
    public static void checkBlocking(String className, String methodName, int modifiers) {
        if (initialized && isBlocking()) {
            blockingMethodConsumer.accept(new Object[] { className, methodName, modifiers });
        }
    }

    @SuppressWarnings("unused")
    private static boolean isBlockingThread(Thread thread) {
        try {
            return threadPredicate.test(thread);
        }
        catch (Error e) {
            e.printStackTrace();
            throw e;
        }
    }
}
