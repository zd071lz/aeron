/*
 * Copyright 2014-2022 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

/**
 * Interface for delivery of inactive image notification to a {@link Subscription}.
 */
@FunctionalInterface
public interface UnavailableImageHandler
{
    /**
     * Method called by Aeron to deliver notification that an {@link Image} is no longer available for polling.
     * <p>
     * Within this callback reentrant calls to the {@link Aeron} client are not permitted and
     * will result in undefined behaviour.
     *
     * @param image that is no longer available for polling.
     */
    void onUnavailableImage(Image image);
}
