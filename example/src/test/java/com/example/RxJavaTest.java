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

package com.example;

import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.exceptions.CompositeException;
import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

public class RxJavaTest {

    static {
        BlockHound.install();
    }

    @Test(expected = Error.class)
    public void testBlockingCallInsideRxJavaSingle() {
        Single.timer(10, TimeUnit.MILLISECONDS)
                .doOnSuccess(it -> Thread.sleep(10))
                .blockingGet();
    }

    @Test(expected = Error.class)
    public void testBlockingCallInsideRxJavaFlowable() throws Throwable {
        try {
            Flowable.timer(10, TimeUnit.MILLISECONDS)
                    .doOnEach(it -> Thread.sleep(10))
                    .blockingFirst();
        } catch (CompositeException e) {
            throw e.getExceptions().get(0);
        }
    }

    @Test(expected = Error.class)
    public void testBlockingCallInsideRxJavaInterop() throws Throwable {
        try {
            Flux.from(Flowable.timer(10, TimeUnit.MILLISECONDS))
                    .doOnEach(it -> {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    })
                    .blockFirst();
        } catch (Exception e) {
            if (e.getClass().getName().contains("$ReactiveException")) {
                throw e.getCause();
            }
        }
    }
}
