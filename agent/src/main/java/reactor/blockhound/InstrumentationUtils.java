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

        ClassLoader classLoader = BlockHound.class.getClassLoader();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(tempJarFile))) {
            for (String className : classNames) {
                String classFile = className.replace(".", "/") + ".class";
                InputStream inputStream = classLoader.getResourceAsStream(classFile);
                ZipEntry e = new ZipEntry(classFile);
                zipOutputStream.putNextEntry(e);

                byte[] buf = new byte[4096];
                int n;
                while ((n = inputStream.read(buf)) > 0) {
                    zipOutputStream.write(buf, 0, n);
                }

                zipOutputStream.closeEntry();
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJarFile));
    }
}
