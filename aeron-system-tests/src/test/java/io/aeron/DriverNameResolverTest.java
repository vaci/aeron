/*
 * Copyright 2014-2020 Real Logic Limited.
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
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;

import static io.aeron.Aeron.NULL_VALUE;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class DriverNameResolverTest
{
    private final String baseDir = CommonContext.getAeronDirectoryName();
    private final ArrayList<MediaDriver> drivers = new ArrayList<>();

    @AfterEach
    public void after()
    {
        CloseHelper.closeAll(drivers);

        for (final MediaDriver driver : drivers)
        {
            driver.context().deleteDirectory();
        }
    }

    @Test
    public void shouldInitializeWithDefaultsAndHaveResolverCounters()
    {
        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context()
            .resolverInterface("0.0.0.0:0"))));

        final int neighborsCounterId = neighborsCounterId(drivers.get(0));
        assertNotEquals(neighborsCounterId, NULL_VALUE);
    }

    @Test
    @Timeout(10)
    public void shouldSeeNeighbor()
    {
        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-A")
            .resolverName("A")
            .resolverInterface("0.0.0.0:8050")));

        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-B")
            .resolverName("B")
            .resolverInterface("0.0.0.0:8051")
            .resolverBootstrapNeighbor("localhost:8050")));

        final int aNeighborsCounterId = neighborsCounterId(drivers.get(0));
        final int bNeighborsCounterId = neighborsCounterId(drivers.get(1));

        awaitCounterValue(drivers.get(0).context().countersManager(), aNeighborsCounterId, 1);
        awaitCounterValue(drivers.get(1).context().countersManager(), bNeighborsCounterId, 1);
    }

    @Test
    @Timeout(10)
    public void shouldSeeNeighborsViaGossip()
    {
        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-A")
            .resolverName("A")
            .resolverInterface("0.0.0.0:8050")));

        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-B")
            .resolverName("B")
            .resolverInterface("0.0.0.0:8051")
            .resolverBootstrapNeighbor("localhost:8050")));

        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-C")
            .resolverName("C")
            .resolverInterface("0.0.0.0:8052")
            .resolverBootstrapNeighbor("localhost:8051")));

        final int aNeighborsCounterId = neighborsCounterId(drivers.get(0));
        final int bNeighborsCounterId = neighborsCounterId(drivers.get(1));
        final int cNeighborsCounterId = neighborsCounterId(drivers.get(2));

        awaitCounterValue(drivers.get(0).context().countersManager(), aNeighborsCounterId, 2);
        awaitCounterValue(drivers.get(1).context().countersManager(), bNeighborsCounterId, 2);
        awaitCounterValue(drivers.get(2).context().countersManager(), cNeighborsCounterId, 2);
    }

    @Test
    @Timeout(10)
    public void shouldSeeNeighborsViaGossipAsLateJoiningDriver()
    {
        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-A")
            .resolverName("A")
            .resolverInterface("0.0.0.0:8050")));

        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-B")
            .resolverName("B")
            .resolverInterface("0.0.0.0:8051")
            .resolverBootstrapNeighbor("localhost:8050")));

        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-C")
            .resolverName("C")
            .resolverInterface("0.0.0.0:8052")
            .resolverBootstrapNeighbor("localhost:8050")));

        final int aNeighborsCounterId = neighborsCounterId(drivers.get(0));
        final int bNeighborsCounterId = neighborsCounterId(drivers.get(1));
        final int cNeighborsCounterId = neighborsCounterId(drivers.get(2));

        awaitCounterValue(drivers.get(0).context().countersManager(), aNeighborsCounterId, 2);
        awaitCounterValue(drivers.get(1).context().countersManager(), bNeighborsCounterId, 2);
        awaitCounterValue(drivers.get(2).context().countersManager(), cNeighborsCounterId, 2);

        drivers.add(MediaDriver.launch(setDefaults(new MediaDriver.Context())
            .aeronDirectoryName(baseDir + "-D")
            .resolverName("C")
            .resolverInterface("0.0.0.0:8053")
            .resolverBootstrapNeighbor("localhost:8050")));

        final int dNeighborsCounterId = neighborsCounterId(drivers.get(3));

        awaitCounterValue(drivers.get(3).context().countersManager(), aNeighborsCounterId, 3);
        awaitCounterValue(drivers.get(0).context().countersManager(), aNeighborsCounterId, 3);
        awaitCounterValue(drivers.get(1).context().countersManager(), bNeighborsCounterId, 3);
        awaitCounterValue(drivers.get(2).context().countersManager(), cNeighborsCounterId, 3);
    }

    private static MediaDriver.Context setDefaults(final MediaDriver.Context context)
    {
        context
            .errorHandler(Throwable::printStackTrace)
            .publicationTermBufferLength(LogBufferDescriptor.TERM_MIN_LENGTH)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true);

        return context;
    }

    private static int neighborsCounterId(final MediaDriver driver)
    {
        final CountersReader countersReader = driver.context().countersManager();
        final MutableInteger id = new MutableInteger(NULL_VALUE);

        countersReader.forEach(
            (counterId, typeId, keyBuffer, label) ->
            {
                if (label.startsWith("Resolver neighbors"))
                {
                    id.value = counterId;
                }
            });

        return id.value;
    }

    private static int cacheEntriesCounterId(final MediaDriver driver)
    {
        final CountersReader countersReader = driver.context().countersManager();
        final MutableInteger id = new MutableInteger(NULL_VALUE);

        countersReader.forEach(
            (counterId, typeId, keyBuffer, label) ->
            {
                if (label.startsWith("Resolver cache entries"))
                {
                    id.value = counterId;
                }
            });

        return id.value;
    }

    private static void awaitCounterValue(
        final CountersReader countersReader, final int counterId, final long expectedValue)
    {
        while (countersReader.getCounterValue(counterId) != expectedValue)
        {
            Tests.sleep(50);
        }
    }
}