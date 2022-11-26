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
package io.aeron.cluster;

import io.aeron.test.EventLogExtension;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SlowTest;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.cluster.TestCluster;
import io.aeron.test.cluster.TestNode;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static io.aeron.test.cluster.TestCluster.aCluster;
import static io.aeron.test.cluster.TestCluster.awaitElectionClosed;

@SlowTest
@ExtendWith({ EventLogExtension.class, InterruptingTestCallback.class })
class StalledLeaderLogReplicationClusterTest
{
    private static ClusterInstrumentor clusterInstrumentor;

    @BeforeAll
    static void beforeAll()
    {
        clusterInstrumentor = new ClusterInstrumentor(
            StallLeaderLogReplicationIntercept.class, "Election", "leaderLogReplication");
    }

    @AfterAll
    static void afterAll()
    {
        clusterInstrumentor.reset();
    }

    @RegisterExtension
    final SystemTestWatcher systemTestWatcher = new SystemTestWatcher();

    public static class StallLeaderLogReplicationIntercept
    {
        static boolean shouldStall = true;

        @Advice.OnMethodEnter
        static void leaderLogReplication(final long nowNs, @Advice.This final Object election)
        {
            if (shouldStall && 0 == ((Election)election).leadershipTermId())
            {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2_500));
                shouldStall = false;
            }
        }
    }

    @Test
    @InterruptAfter(120)
    void shouldHandleMultipleElections()
    {
        final TestCluster cluster = aCluster().withStaticNodes(3).start();
        systemTestWatcher.cluster(cluster);

        final TestNode leader0 = cluster.awaitLeader();

        final int numMessages = 3;
        cluster.connectClient();
        cluster.sendMessages(numMessages);
        cluster.awaitResponseMessageCount(numMessages);
        cluster.awaitServicesMessageCount(numMessages);

        cluster.stopNode(leader0);
        final TestNode leader1 = cluster.awaitLeader(leader0.index());
        cluster.connectClient();
        cluster.startStaticNode(leader0.index(), false);
        awaitElectionClosed(cluster.node(leader0.index()));

        cluster.connectClient();
        cluster.sendMessages(numMessages);
        cluster.awaitResponseMessageCount(numMessages * 2);
        cluster.awaitServicesMessageCount(numMessages * 2);

        cluster.stopNode(leader1);
        cluster.awaitLeader(leader1.index());
        cluster.startStaticNode(leader1.index(), false);
        awaitElectionClosed(cluster.node(leader1.index()));

        cluster.connectClient();
        cluster.sendMessages(numMessages);
        cluster.awaitResponseMessageCount(numMessages * 3);
        cluster.awaitServicesMessageCount(numMessages * 3);
    }
}
