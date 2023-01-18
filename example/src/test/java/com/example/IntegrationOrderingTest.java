/*
 * Copyright (c) 2023-Present Pivotal Software Inc, All Rights Reserved.
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

import org.junit.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BlockHoundIntegration plugins ordering tests.
 */
public class IntegrationOrderingTest {

    final static List<Integer> applied = new ArrayList<>();

    /**
     * Plugin with priority=-1, loaded from
     * META-INF/services/reactor.blockhound.integration.BlockHoundIntegration (2nd position in file)
     */
    public static final class First implements BlockHoundIntegration {

        public First() {
            System.out.println("Creating First");
        }
        @Override
        public int getPriority() {
            return -1;
        }

        @Override
        public void applyTo(BlockHound.Builder builder) {
            applied.add(1);
        }
    }

    /**
     * Plugin with default priority=0, loaded from
     * META-INF/services/reactor.blockhound.integration.BlockHoundIntegration (1st position in file)
     */
    public static final class Second implements BlockHoundIntegration {
        @Override
        public void applyTo(BlockHound.Builder builder) {
            applied.add(2);
        }
    }

    /**
     * Plugin with priority=1, installed using {@link BlockHound#install(BlockHoundIntegration...)}}
     */
    public static final class Third implements BlockHoundIntegration {
        @Override
        public int getPriority() {
            return 1;
        }

        @Override
        public void applyTo(BlockHound.Builder builder) {
            applied.add(3);
        }
    }

    /**
     * Plugin with priority=2, installed using {@link BlockHound#install(BlockHoundIntegration...)}}
     */
    public static final class Fourth implements BlockHoundIntegration {
        @Override
        public int getPriority() {
            return 2;
        }

        @Override
        public void applyTo(BlockHound.Builder builder) {
            applied.add(4);
        }
    }

    /**
     * Plugin with default priority=0, installed using {@link BlockHound#install(BlockHoundIntegration...)}}
     */
    public static final class Fifth implements BlockHoundIntegration {
        @Override
        public void applyTo(BlockHound.Builder builder) {
            applied.add(5);
        }
    }

    /**
     * Plugin with default priority=0, installed using {@link BlockHound#install(BlockHoundIntegration...)}}
     */
    public static final class Sixth implements BlockHoundIntegration {
        @Override
        public void applyTo(BlockHound.Builder builder) {
            applied.add(6);
        }
    }

    /**
     * In this test, we install 6 blockhound integrations plugins.
     * <ul>
     *     <li> First: priority=-1, defined in META-INF/services/reactor.blockhound.integration.BlockHoundIntegration at 2nd position</li>
     *     <li> Second: no priority (default=0), defined in META-INF/services/reactor.blockhound.integration.BlockHoundIntegration at 1st position</li>
     *     <li> Third: priority=1, added using {link {@link BlockHound#install(BlockHoundIntegration...)}}, passed in 2nd parameter</li>
     *     <li> Fourth: priority=2, added using {link {@link BlockHound#install(BlockHoundIntegration...)}}, passed in 1st parameter</li>
     *     <li> Fifth: no priority (default=0), added using {link {@link BlockHound#install(BlockHoundIntegration...)}}, passed in 3rd parameter</li>
     *     <li> Sixth: no priority, by default: 0, added using {link {@link BlockHound#install(BlockHoundIntegration...)}}, passed in 4th parameter</li>
     * </ul>
     *
     * We expect to see the 6 plugins applied in this order: First, Second, Fifth, Sixth, Third, Four.
     * And plugins without any priority should be loaded in natural order, as before.
     */
    @Test
    public void checkIntegrationsOrdering() {
        // Do not install BlockHound in our static initialized, because other tests
        // will load our inner integrations classes ...
        BlockHound.install(new Fourth(), new Third(), new Fifth(), new Sixth());
        Integer[] expectedApplies = new Integer[] { 1, 2, 5, 6, 3, 4};
        assertThat(applied).containsExactly(expectedApplies);
    }
}
