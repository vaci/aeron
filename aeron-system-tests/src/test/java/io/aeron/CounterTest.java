/*
 * Copyright 2014-2023 Real Logic Limited.
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

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.status.ReadableCounter;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.Tests;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(InterruptingTestCallback.class)
class CounterTest
{
    private static final int COUNTER_TYPE_ID = 1101;
    private static final String COUNTER_LABEL = "counter label";

    private final UnsafeBuffer keyBuffer = new UnsafeBuffer(new byte[8]);
    private final UnsafeBuffer labelBuffer = new UnsafeBuffer(new byte[COUNTER_LABEL.length()]);

    private Aeron clientA;
    private Aeron clientB;
    private TestMediaDriver driver;

    @RegisterExtension
    final SystemTestWatcher testWatcher = new SystemTestWatcher();

    private volatile ReadableCounter readableCounter;

    @BeforeEach
    void before()
    {
        labelBuffer.putStringWithoutLengthAscii(0, COUNTER_LABEL);

        driver = TestMediaDriver.launch(
            new MediaDriver.Context()
                .errorHandler(Tests::onError)
                .threadingMode(ThreadingMode.SHARED),
            testWatcher);
        testWatcher.dataCollector().add(driver.context().aeronDirectory());

        clientA = Aeron.connect();
        clientB = Aeron.connect();
    }

    @AfterEach
    void after()
    {
        CloseHelper.closeAll(clientA, clientB, driver);
        driver.context().deleteDirectory();
    }

    @Test
    @InterruptAfter(10)
    void shouldBeAbleToAddCounter()
    {
        final AvailableCounterHandler availableCounterHandlerClientA = mock(AvailableCounterHandler.class);
        clientA.addAvailableCounterHandler(availableCounterHandlerClientA);

        final AvailableCounterHandler availableCounterHandlerClientB = mock(AvailableCounterHandler.class);
        clientB.addAvailableCounterHandler(availableCounterHandlerClientB);

        final Counter counter = clientA.addCounter(
            COUNTER_TYPE_ID,
            keyBuffer,
            0,
            keyBuffer.capacity(),
            labelBuffer,
            0,
            COUNTER_LABEL.length());

        assertFalse(counter.isClosed());
        assertEquals(counter.registrationId(), clientA.countersReader().getCounterRegistrationId(counter.id()));
        assertEquals(clientA.clientId(), clientA.countersReader().getCounterOwnerId(counter.id()));

        verify(availableCounterHandlerClientA, timeout(5000L))
            .onAvailableCounter(any(CountersReader.class), eq(counter.registrationId()), eq(counter.id()));
        verify(availableCounterHandlerClientB, timeout(5000L))
            .onAvailableCounter(any(CountersReader.class), eq(counter.registrationId()), eq(counter.id()));
    }

    @Test
    @InterruptAfter(10)
    void shouldBeAbleToAddReadableCounterWithinHandler()
    {
        clientB.addAvailableCounterHandler(this::createReadableCounter);

        final Counter counter = clientA.addCounter(
            COUNTER_TYPE_ID,
            keyBuffer,
            0,
            keyBuffer.capacity(),
            labelBuffer,
            0,
            COUNTER_LABEL.length());

        while (null == readableCounter)
        {
            Tests.sleep(1);
        }

        assertEquals(CountersReader.RECORD_ALLOCATED, readableCounter.state());
        assertEquals(counter.id(), readableCounter.counterId());
        assertEquals(counter.registrationId(), readableCounter.registrationId());
    }

    @Test
    @InterruptAfter(10)
    void shouldCloseReadableCounterOnUnavailableCounter()
    {
        clientB.addAvailableCounterHandler(this::createReadableCounter);
        clientB.addUnavailableCounterHandler(this::unavailableCounterHandler);

        final Counter counter = clientA.addCounter(
            COUNTER_TYPE_ID,
            keyBuffer,
            0,
            keyBuffer.capacity(),
            labelBuffer,
            0,
            COUNTER_LABEL.length());

        while (null == readableCounter)
        {
            Tests.sleep(1);
        }

        assertFalse(readableCounter.isClosed());
        assertEquals(CountersReader.RECORD_ALLOCATED, readableCounter.state());

        counter.close();

        while (!readableCounter.isClosed())
        {
            Tests.sleep(1);
        }

        while (clientA.hasActiveCommands())
        {
            Tests.sleep(1);
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldGetUnavailableCounterWhenOwningClientIsClosed()
    {
        clientB.addAvailableCounterHandler(this::createReadableCounter);
        clientB.addUnavailableCounterHandler(this::unavailableCounterHandler);

        clientA.addCounter(
            COUNTER_TYPE_ID,
            keyBuffer,
            0,
            keyBuffer.capacity(),
            labelBuffer,
            0,
            COUNTER_LABEL.length());

        while (null == readableCounter)
        {
            Tests.sleep(1);
        }

        assertFalse(readableCounter.isClosed());
        assertEquals(CountersReader.RECORD_ALLOCATED, readableCounter.state());

        clientA.close();

        while (!readableCounter.isClosed())
        {
            Tests.sleep(1, "Counter not closed");
        }
    }

    private void createReadableCounter(final CountersReader counters, final long registrationId, final int counterId)
    {
        if (COUNTER_TYPE_ID == counters.getCounterTypeId(counterId))
        {
            readableCounter = new ReadableCounter(counters, registrationId, counterId);
        }
    }

    private void unavailableCounterHandler(
        final CountersReader counters, final long registrationId, final int counterId)
    {
        if (null != readableCounter && readableCounter.registrationId() == registrationId)
        {
            readableCounter.close();
        }
    }
}
