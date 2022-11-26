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

import io.aeron.cluster.service.Cluster;
import io.aeron.test.EventLogExtension;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SlowTest;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.cluster.ClusterTests;
import io.aeron.test.cluster.TestCluster;
import io.aeron.test.cluster.TestNode;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.IntArrayList;
import org.agrona.collections.LongArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.aeron.test.cluster.TestCluster.aCluster;
import static io.aeron.test.cluster.TestCluster.awaitElectionClosed;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith({ EventLogExtension.class, InterruptingTestCallback.class })
class ServiceIpcIngressMessageTest
{
    @RegisterExtension
    final SystemTestWatcher systemTestWatcher = new SystemTestWatcher();

    @AfterEach
    void tearDown()
    {
        TestNode.MessageTrackingService.delaySessionMessageProcessing(false);
    }

    @Test
    @InterruptAfter(10)
    void shouldEchoServiceIpcMessages()
    {
        final TestCluster cluster = aCluster().withStaticNodes(3).start();
        systemTestWatcher.cluster(cluster);

        cluster.awaitLeader();
        cluster.connectClient();
        final int messageLength = cluster.msgBuffer().putStringWithoutLengthAscii(
            0, ClusterTests.ECHO_SERVICE_IPC_INGRESS_MSG);

        final int messageCount = 10;
        for (int i = 0; i < messageCount; i++)
        {
            cluster.pollUntilMessageSent(messageLength);
        }

        cluster.awaitResponseMessageCount(messageCount);
        cluster.awaitServicesMessageCount(messageCount);
    }

    @Test
    @SlowTest
    @InterruptAfter(30)
    void shouldProcessServiceMessagesWithoutDuplicates()
    {
        final TestCluster cluster = aCluster()
            .withStaticNodes(3)
            .withTimerServiceSupplier(new PriorityHeapTimerServiceSupplier())
            .withServiceSupplier((i) -> new TestNode.TestService[]{
                new TestNode.MessageTrackingService(1, i),
                new TestNode.MessageTrackingService(2, i),
                new TestNode.MessageTrackingService(3, i) })
            .start();
        systemTestWatcher.cluster(cluster);
        final int serviceCount = cluster.node(0).services().length;

        TestNode oldLeader = cluster.awaitLeader();
        cluster.connectClient();
        final ExpandableArrayBuffer msgBuffer = cluster.msgBuffer();

        int messageCount = 0;
        for (int i = 0; i < 50; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, messageCount);

        cluster.stopNode(oldLeader);

        final TestNode newLeader = cluster.awaitLeader(oldLeader.index());
        final TestNode follower = cluster.node(3 - oldLeader.index() - newLeader.index());
        assertEquals(Cluster.Role.FOLLOWER, follower.role());
        cluster.reconnectClient();
        for (int i = 0; i < 30; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, messageCount);

        oldLeader = cluster.startStaticNode(oldLeader.index(), false);
        awaitElectionClosed(oldLeader);
        assertEquals(Cluster.Role.FOLLOWER, oldLeader.role());
        awaitMessageCounts(cluster, oldLeader, messageCount);

        assertTrackedMessages(cluster, -1, messageCount);
    }

    @Test
    @SlowTest
    @InterruptAfter(30)
    void shouldProcessServiceMessagesAndTimersWithoutDuplicatesWhenLeaderServicesAreStopped()
    {
        final TestCluster cluster = aCluster()
            .withStaticNodes(3)
            .withTimerServiceSupplier(new PriorityHeapTimerServiceSupplier())
            .withServiceSupplier((i) -> new TestNode.TestService[]{
                new TestNode.MessageTrackingService(1, i),
                new TestNode.MessageTrackingService(2, i) })
            .start();
        systemTestWatcher.cluster(cluster);
        final int serviceCount = cluster.node(0).services().length;

        TestNode oldLeader = cluster.awaitLeader();
        cluster.connectClient();
        final ExpandableArrayBuffer msgBuffer = cluster.msgBuffer();

        int messageCount = 0;
        for (int i = 0; i < 50; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, messageCount);

        oldLeader.stopServiceContainers(); // stop services to cause a new election

        final TestNode newLeader = cluster.awaitLeader(oldLeader.index());
        final TestNode follower = cluster.node(3 - oldLeader.index() - newLeader.index());
        assertEquals(Cluster.Role.FOLLOWER, follower.role());
        cluster.awaitNodeState(oldLeader, node -> Cluster.Role.FOLLOWER == node.role());
        cluster.reconnectClient();
        for (int i = 0; i < 30; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, newLeader, messageCount);
        awaitMessageCounts(cluster, follower, messageCount);
        assertTrackedMessages(cluster, oldLeader.index(), messageCount);

        cluster.stopNode(oldLeader);
        oldLeader = cluster.startStaticNode(oldLeader.index(), false);
        awaitMessageCounts(cluster, oldLeader, messageCount);

        assertTrackedMessages(cluster, -1, messageCount);
    }

    @Test
    @SlowTest
    @InterruptAfter(30)
    void shouldProcessServiceMessagesWithoutDuplicatesAfterAFullClusterRestart()
    {
        final TestCluster cluster = aCluster()
            .withStaticNodes(3)
            .withTimerServiceSupplier(new PriorityHeapTimerServiceSupplier())
            .withServiceSupplier((i) -> new TestNode.TestService[]{
                new TestNode.MessageTrackingService(1, i) })
            .start();
        systemTestWatcher.cluster(cluster);
        final int serviceCount = cluster.node(0).services().length;

        cluster.awaitLeader();
        cluster.connectClient();
        final ExpandableArrayBuffer msgBuffer = cluster.msgBuffer();

        int messageCount = 0;
        for (int i = 0; i < 10; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, messageCount);

        cluster.stopAllNodes();
        cluster.restartAllNodes(false);
        cluster.awaitLeader();

        cluster.reconnectClient();
        for (int i = 0; i < 20; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, messageCount);

        assertTrackedMessages(cluster, -1, messageCount);
    }

    @Test
    @SlowTest
    @InterruptAfter(30)
    void shouldProcessServiceMessagesWithoutDuplicatesWhenClusterIsRestartedAfterTakingASnapshot()
    {
        final TestCluster cluster = aCluster()
            .withStaticNodes(3)
            .withTimerServiceSupplier(new PriorityHeapTimerServiceSupplier())
            .withServiceSupplier((i) -> new TestNode.TestService[]{
                new TestNode.MessageTrackingService(1, i),
                new TestNode.MessageTrackingService(2, i) })
            .start();
        systemTestWatcher.cluster(cluster);
        final int serviceCount = cluster.node(0).services().length;

        final TestNode leader = cluster.awaitLeader();
        cluster.connectClient();
        final ExpandableArrayBuffer msgBuffer = cluster.msgBuffer();

        int messageCount = 0;
        TestNode.MessageTrackingService.delaySessionMessageProcessing(true);
        for (int i = 0; i < 1999; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.takeSnapshot(leader);
        cluster.awaitSnapshotCount(1);
        TestNode.MessageTrackingService.delaySessionMessageProcessing(false);

        for (int i = 0; i < 567; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, messageCount);
        assertTrackedMessages(cluster, -1, messageCount);

        final TestNode.MessageTrackingService leaderTrackingService =
            (TestNode.MessageTrackingService)leader.services()[0];
        final IntArrayList clientMessagesBeforeRestart = leaderTrackingService.clientMessages();
        final IntArrayList serviceMessagesBeforeRestart = leaderTrackingService.serviceMessages();
        final LongArrayList timersBeforeRestart = leaderTrackingService.timers();

        cluster.stopAllNodes();
        cluster.restartAllNodes(false);
        final TestNode newLeader = cluster.awaitLeader();
        final TestNode.MessageTrackingService newLeaderTrackingService =
            (TestNode.MessageTrackingService)newLeader.services()[0];
        assertEquals(clientMessagesBeforeRestart, newLeaderTrackingService.clientMessages());
        assertEquals(serviceMessagesBeforeRestart, newLeaderTrackingService.serviceMessages());
        assertEquals(timersBeforeRestart, newLeaderTrackingService.timers());
        assertTrackedMessages(cluster, -1, messageCount);

        cluster.reconnectClient();
        for (int i = 0; i < 20; i++)
        {
            msgBuffer.putInt(0, ++messageCount, LITTLE_ENDIAN);
            cluster.pollUntilMessageSent(SIZE_OF_INT);
        }
        cluster.awaitResponseMessageCount(messageCount * serviceCount);
        awaitMessageCounts(cluster, messageCount);
        assertTrackedMessages(cluster, -1, messageCount);
    }

    private static void awaitMessageCounts(final TestCluster cluster, final int messageCount)
    {
        for (int i = 0; i < 3; i++)
        {
            final TestNode node = cluster.node(i);
            if (null != node && !node.isClosed())
            {
                awaitMessageCounts(cluster, node, messageCount);
            }
        }
    }

    private static void awaitMessageCounts(final TestCluster cluster, final TestNode node, final int messageCount)
    {
        final TestNode.TestService[] services = node.services();
        for (final TestNode.TestService service : services)
        {
            // 1 client message + 3 service messages x number of services
            cluster.awaitServiceMessageCount(node, service, messageCount + (messageCount * 3 * services.length));
            // 2 timers x number of services
            cluster.awaitTimerEventCount(node, service, messageCount * 2 * services.length);
        }
    }

    private static void assertTrackedMessages(
        final TestCluster cluster, final int excludeIndex, final int messageCount)
    {
        final TestNode leader = cluster.findLeader();
        assertNotEquals(leader.index(), excludeIndex);

        final TestNode.TestService[] leaderServices = leader.services();
        final int numberOfTrackingServices = leaderServices.length;
        final TestNode.MessageTrackingService trackingService = (TestNode.MessageTrackingService)leaderServices[0];
        final IntArrayList clientMessages = trackingService.clientMessages();
        final IntArrayList serviceMessages = trackingService.serviceMessages();
        final LongArrayList timers = trackingService.timers();

        assertEquals(
            messageCount,
            clientMessages.size(),
            () -> "Invalid client message count on leader: " + leader);
        assertEquals(
            messageCount * 3 * numberOfTrackingServices,
            serviceMessages.size(),
            () -> "Invalid service message count on leader: " + leader);
        assertEquals(messageCount * 2 * numberOfTrackingServices,
            timers.size(),
            () -> "Invalid timer event count on leader: " + leader);

        for (int i = 0; i < 3; i++)
        {
            if (excludeIndex != i)
            {
                final TestNode node = cluster.node(i);
                assertTrackedServiceState(node, clientMessages, serviceMessages, timers);
            }
        }
    }

    private static void assertTrackedServiceState(
        final TestNode node,
        final IntArrayList expectedClientMessages,
        final IntArrayList expectedServiceMessages,
        final LongArrayList expectedTimers)
    {
        final TestNode.TestService[] services = node.services();
        for (final TestNode.TestService service : services)
        {
            final TestNode.MessageTrackingService trackingService = (TestNode.MessageTrackingService)service;
            final IntArrayList actualClientMessages = trackingService.clientMessages();
            if (!expectedClientMessages.equals(actualClientMessages))
            {
                fail("memberId=" + node.index() + ", role=" + node.role() + ": Client messages diverged: expected=" +
                    expectedClientMessages.size() + ", actual=" + actualClientMessages.size());
            }

            final IntArrayList actualServiceMessages = trackingService.serviceMessages();
            if (!expectedServiceMessages.equals(actualServiceMessages))
            {
                fail("memberId=" + node.index() + ", role=" + node.role() + ": Service messages diverged: expected=" +
                    expectedServiceMessages.size() + ", actual=" + actualServiceMessages.size());
            }

            final LongArrayList actualTimers = trackingService.timers();
            if (!expectedTimers.equals(actualTimers))
            {
                fail("memberId=" + node.index() + ", role=" + node.role() + ": Timers diverged: expected=" +
                    expectedTimers.size() + ", actual=" + actualTimers.size());
            }
        }
    }
}
