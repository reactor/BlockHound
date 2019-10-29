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

public final class BlockingOperationError extends Error {

    private static final long serialVersionUID = 4980196508457280342L;

    private final BlockingMethod blockingMethod;

    public BlockingOperationError(BlockingMethod blockingMethod) {
        super();
        this.blockingMethod = blockingMethod;
    }

    public BlockingMethod getBlockingMethod() {
        return blockingMethod;
    }

    @Override
    public String toString() {
        return super.toString() + ": " + String.format("Blocking call! %s", blockingMethod);
    }
}

