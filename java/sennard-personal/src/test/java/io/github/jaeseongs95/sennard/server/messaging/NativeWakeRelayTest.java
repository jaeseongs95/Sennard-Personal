// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;
import io.github.jaeseongs95.sennard.server.hook.HostId;

class NativeWakeRelayTest {
    @Test
    void aRelayExpiresWithTheActorEvidenceUsedForItsRegistration() {
        NativeWakeRelay relay = new NativeWakeRelay();
        WakeTransport transport = relay.transport(new HostId("fixture"));
        AtomicBoolean current = new AtomicBoolean(true);
        String key = UUID.randomUUID().toString();
        var target = new WakeTransport.Target("target", "/work", current::get);
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class, transport.connect(key, target)).connection();
        var channel = relay.find(key);
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.ROOT));
        channel.endPoll();
        assertTrue(connection.live());

        current.set(false);
        assertFalse(connection.live());
        assertNull(relay.find(key));
        assertEquals(NativeWakeRelay.Poll.CLOSED, channel.beginPoll(NativeWakeProtocol.Capability.ROOT));

        String nextKey = UUID.randomUUID().toString();
        assertInstanceOf(WakeTransport.Availability.Ready.class, transport.connect(nextKey, new WakeTransport.Target("target", "/work")));
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, relay.find(nextKey).beginPoll(NativeWakeProtocol.Capability.ROOT));
    }

    @Test
    void managedOnlyRegistrationDoesNotAdvertiseRootWakeAndAcceptsOneExactResponse() throws Exception {
        NativeWakeRelay relay = new NativeWakeRelay();
        String key = UUID.randomUUID().toString();
        String otherKey = UUID.randomUUID().toString();
        WakeTransport transport = relay.transport(new HostId("fixture"));
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class,
                transport.connect(key, new WakeTransport.Target("parent", "/work"))).connection();
        assertInstanceOf(WakeTransport.Availability.Ready.class,
                transport.connect(otherKey, new WakeTransport.Target("other-parent", "/work")));
        var channel = relay.find(key);
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.MANAGED));
        assertTrue(connection.registered());
        assertFalse(connection.live());
        assertInstanceOf(WakeTransport.Outcome.NotSent.class, connection.wake("parent", IdleWakeService.marker(UUID.randomUUID().toString())));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var creating = executor.submit(() -> connection.createChild("model-current", "effort-current", () -> true, "child"::equals));
            var launch = channel.awaitOffer().launch();
            assertNotNull(launch);
            assertEquals("model-current", launch.model());
            assertEquals("effort-current", launch.effort());
            channel.endPoll();
            var created = new NativeWakeProtocol.LaunchReply(NativeWakeProtocol.LaunchResult.CREATED, "child");
            assertFalse(relay.find(otherKey).completeLaunch(launch.id(), created));
            assertFalse(channel.completeLaunch(UUID.randomUUID().toString(), created));
            assertFalse(channel.completeLaunch(launch.id(), new NativeWakeProtocol.LaunchReply(NativeWakeProtocol.LaunchResult.CREATED, "parent")));
            assertTrue(channel.completeLaunch(launch.id(), created));
            assertFalse(channel.completeLaunch(launch.id(), created));
            assertEquals(created, creating.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void staleRootCannotAcceptChildOrReplayTheOffer() throws Exception {
        NativeWakeRelay relay = new NativeWakeRelay();
        String key = UUID.randomUUID().toString();
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class,
                relay.transport(new HostId("fixture")).connect(key, new WakeTransport.Target("parent", "/work"))).connection();
        var channel = relay.find(key);
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.BOTH));
        AtomicBoolean current = new AtomicBoolean(true);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var creating = executor.submit(() -> connection.createChild("model", "effort", current::get, child -> true));
            var launch = channel.awaitOffer().launch();
            assertNotNull(launch);
            channel.endPoll();
            current.set(false);
            assertFalse(channel.completeLaunch(launch.id(), new NativeWakeProtocol.LaunchReply(NativeWakeProtocol.LaunchResult.CREATED, "child")));
            assertEquals(NativeWakeProtocol.LaunchResult.UNKNOWN, creating.get(2, TimeUnit.SECONDS).result());
            assertEquals(NativeWakeProtocol.LaunchResult.NOT_SENT, connection.createChild("model", "effort", current::get, child -> true).result());
        }
    }

    @Test
    void sendsTheReservedMarkerOnlyToTheNamedManagedConversation() throws Exception {
        var relay = new NativeWakeRelay();
        String key = UUID.randomUUID().toString();
        String marker = IdleWakeService.marker(UUID.randomUUID().toString());
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class,
                relay.transport(new HostId("fixture")).connect(key, new WakeTransport.Target("parent", "/work"))).connection();
        var channel = relay.find(key);
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.MANAGED));
        assertTrue(connection.managedReady());
        assertFalse(connection.live());
        assertInstanceOf(WakeTransport.Outcome.NotSent.class, connection.wakeManaged("parent", marker, () -> true));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var woke = executor.submit(() -> connection.wakeManaged("child", marker, () -> true));
            var offer = channel.awaitOffer();
            assertEquals("child", offer.childId());
            assertEquals(marker, offer.marker());
            channel.endPoll();
            assertFalse(channel.complete(marker, null, NativeWakeProtocol.Result.SUBMITTED));
            assertFalse(channel.complete(marker, "sibling", NativeWakeProtocol.Result.SUBMITTED));
            assertTrue(channel.complete(marker, "child", NativeWakeProtocol.Result.SUBMITTED));
            assertFalse(channel.complete(marker, "child", NativeWakeProtocol.Result.SUBMITTED));
            assertInstanceOf(WakeTransport.Outcome.Submitted.class, woke.get(2, TimeUnit.SECONDS));
        } finally {
            channel.close();
        }
    }

    @Test
    void closingTheRegistrationWhileBindingRejectsTheWorkerReceipt() throws Exception {
        var relay = new NativeWakeRelay();
        String key = UUID.randomUUID().toString();
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class,
                relay.transport(new HostId("fixture")).connect(key, new WakeTransport.Target("parent", "/work"))).connection();
        var channel = relay.find(key);
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.MANAGED));
        var binding = new CountDownLatch(1);
        var commit = new CompletableFuture<Boolean>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var creating = executor.submit(() -> connection.createChild("model", "effort", () -> true, child -> {
                    binding.countDown();
                    return commit.join();
                }));
                var launch = channel.awaitOffer().launch();
                assertNotNull(launch);
                channel.endPoll();
                var created = new NativeWakeProtocol.LaunchReply(NativeWakeProtocol.LaunchResult.CREATED, "child");
                var completion = executor.submit(() -> channel.completeLaunch(launch.id(), created));
                assertTrue(binding.await(2, TimeUnit.SECONDS));
                assertFalse(executor.submit(() -> channel.completeLaunch(launch.id(), created)).get(2, TimeUnit.SECONDS));
                executor.submit(channel::close).get(2, TimeUnit.SECONDS);
                assertEquals(NativeWakeProtocol.LaunchResult.UNKNOWN, creating.get(2, TimeUnit.SECONDS).result());
                commit.complete(true);
                assertFalse(completion.get(2, TimeUnit.SECONDS));
            } finally {
                commit.complete(false);
            }
        }
    }

    @Test
    void closingDuringPreassignmentDoesNotPublishTheLaunch() throws Exception {
        var relay = new NativeWakeRelay();
        String key = UUID.randomUUID().toString();
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class,
                relay.transport(new HostId("fixture"), true).connect(key, new WakeTransport.Target("parent", "/work"))).connection();
        var channel = relay.find(key);
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.MANAGED));
        var assigning = new CountDownLatch(1);
        var commit = new CompletableFuture<Boolean>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var creating = executor.submit(() -> connection.createChild("model", "effort", () -> true, child -> {
                    assigning.countDown();
                    return commit.join();
                }));
                assertTrue(assigning.await(2, TimeUnit.SECONDS));
                executor.submit(channel::close).get(2, TimeUnit.SECONDS);
                var reply = channel.awaitOffer();
                assertTrue(reply.closed());
                assertNull(reply.launch());
                commit.complete(true);
                assertEquals(NativeWakeProtocol.LaunchResult.NOT_SENT, creating.get(2, TimeUnit.SECONDS).result());
            } finally {
                channel.endPoll();
                channel.close();
                commit.complete(false);
            }
        }
    }

    @Test
    void handedOffMarkerStaysUncertainWhenChannelCloses() throws Exception {
        NativeWakeRelay relay = new NativeWakeRelay();
        String key = UUID.randomUUID().toString();
        String marker = IdleWakeService.marker(UUID.randomUUID().toString());
        WakeTransport transport = relay.transport(new HostId("fixture"));
        var target = new WakeTransport.Target("target", "/work");
        var connection = assertInstanceOf(WakeTransport.Availability.Ready.class, transport.connect(key, target)).connection();
        var channel = relay.find(key);
        assertFalse(connection.live());
        assertInstanceOf(WakeTransport.Outcome.NotSent.class, connection.wake("target", marker));
        assertInstanceOf(WakeTransport.Availability.Missing.class, transport.connect(key, new WakeTransport.Target("other", "/work")));
        assertEquals(NativeWakeRelay.Poll.FIRST_CONTACT, channel.beginPoll(NativeWakeProtocol.Capability.ROOT));
        assertEquals(NativeWakeRelay.Poll.BUSY, channel.beginPoll(NativeWakeProtocol.Capability.ROOT));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sending = executor.submit(() -> connection.wake("target", marker));
            try {
                assertEquals(marker, channel.awaitOffer().marker());
                channel.endPoll();
                assertEquals(NativeWakeRelay.Poll.BUSY, channel.beginPoll(NativeWakeProtocol.Capability.ROOT));
                assertFalse(channel.complete(IdleWakeService.marker(UUID.randomUUID().toString()), null, NativeWakeProtocol.Result.SUBMITTED));
            } finally {
                connection.close();
            }
            assertInstanceOf(WakeTransport.Outcome.Unknown.class, sending.get(2, TimeUnit.SECONDS));
        }
        assertNull(relay.find(key));
        assertEquals(NativeWakeRelay.Poll.CLOSED, channel.beginPoll(NativeWakeProtocol.Capability.ROOT));
        assertFalse(channel.complete(marker, null, NativeWakeProtocol.Result.SUBMITTED));
    }
}
