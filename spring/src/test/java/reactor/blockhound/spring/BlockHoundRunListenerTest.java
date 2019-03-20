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
package reactor.blockhound.spring;

import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.Closeable;

public class BlockHoundRunListenerTest {

    @Test
    public void shouldInstallOnRun() throws Exception {
        assertThatBlockingCall().doesNotThrowAnyException();

        try (Closeable __ = new SpringApplication(DummyAppConfiguration.class).run()) {
            assertThatBlockingCall().hasMessageContaining("Blocking call!");
        }
    }

    private AbstractThrowableAssert<?, ? extends Throwable> assertThatBlockingCall() {
        return Assertions.assertThatCode(() -> {
            Mono
                    .fromCallable(() -> {
                        Thread.sleep(1);
                        return "";
                    })
                    .subscribeOn(Schedulers.parallel())
                    .block();
        });
    }

    @Configuration
    static class DummyAppConfiguration {
    }

}
