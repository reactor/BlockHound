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

package reactor.blockhound.integration;

import reactor.blockhound.BlockHound;

/**
 * An interface that defines the contract for the BlockHound integrations.
 *
 * {@link BlockHoundIntegration#applyTo(BlockHound.Builder)} will receive an instance
 * of the builder that is being installed.
 *
 * One can override {@link Comparable#compareTo(Object)} to ensure the order in case
 * one needs to run an integration before or after another.
 */
public interface BlockHoundIntegration extends Comparable<BlockHoundIntegration> {

    /**
     * Lets an integration apply the customizations (see {@link BlockHound.Builder})
     * before BlockHound is installed.
     *
     * @param builder an instance of {@link BlockHound.Builder} that is being installed
     */
    void applyTo(BlockHound.Builder builder);

    /**
     * Returns the default priority level for this integration. The priority level
     * controls the ordering of the {@link BlockHoundIntegration} plugins.
     * Plugins which do not provide a priority are sorted using natural ordering, and
     * their {@link #applyTo(BlockHound.Builder)} method will be called using the order
     * in which the plugins are loaded.
     *
     * @return The {@link BlockHoundIntegration} plugin priority, 0 by default.
     * @see #compareTo(BlockHoundIntegration) 
     */
    default int getPriority() {
        return 0;
    }

    @Override
    default int compareTo(BlockHoundIntegration o) {
        return Integer.compare(getPriority(), o.getPriority());
    }
}
