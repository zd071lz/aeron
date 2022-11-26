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
package io.aeron.driver;

import io.aeron.CommonContext;
import io.aeron.test.Tests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@Timeout(10)
class TerminateDriverTest
{
    @Test
    void shouldCallTerminationHookUponValidRequest()
    {
        final TerminationValidator mockTerminationValidator = mock(TerminationValidator.class);
        final AtomicBoolean hasTerminated = new AtomicBoolean(false);
        final MediaDriver.Context ctx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .terminationHook(() -> hasTerminated.set(true))
            .terminationValidator(mockTerminationValidator);

        when(mockTerminationValidator.allowTermination(any(), any(), anyInt(), anyInt())).thenReturn(true);

        try (MediaDriver mediaDriver = MediaDriver.launch(ctx))
        {
            assertTrue(CommonContext.requestDriverTermination(mediaDriver.context().aeronDirectory(), null, 0, 0));

            do
            {
                Tests.yield();
            }
            while (!hasTerminated.get());
        }

        verify(mockTerminationValidator).allowTermination(any(), any(), anyInt(), anyInt());
    }

    @Test
    void shouldNotCallTerminationHookUponInvalidRequest()
    {
        final AtomicBoolean hasTerminatedByHook = new AtomicBoolean(false);
        final AtomicBoolean hasCalledTerminationValidator = new AtomicBoolean(false);
        final MediaDriver.Context ctx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .terminationHook(() -> hasTerminatedByHook.set(true))
            .terminationValidator((dir, buffer, offset, length) ->
            {
                hasCalledTerminationValidator.set(true);
                return false;
            });

        try (MediaDriver mediaDriver = MediaDriver.launch(ctx))
        {
            assertFalse(hasCalledTerminationValidator.get());
            assertTrue(CommonContext.requestDriverTermination(mediaDriver.context().aeronDirectory(), null, 0, 0));

            do
            {
                Tests.yield();
            }
            while (!hasCalledTerminationValidator.get());
        }

        assertFalse(hasTerminatedByHook.get());
    }
}
