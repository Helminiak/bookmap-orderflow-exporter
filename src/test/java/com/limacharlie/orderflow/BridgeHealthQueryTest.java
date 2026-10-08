package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.zeromq.*;

class BridgeHealthQueryTest {
    @Test
    void terminalAckWithinRetentionBudgetCompletesSession() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        while (health == market) health = ExporterTest.freePort();
        var bridge = new LiveBridge("127.0.0.1", market, health, 1, "SYNTH", .25, () -> "{}");
        try (var context = new ZContext()) {
            // Binding happens on the worker. Wait for observable readiness, not a CI scheduling
            // guess.
            String ready = null;
            long readyDeadline = System.nanoTime() + 10_000_000_000L;
            while (ready == null && !bridge.invalid() && System.nanoTime() < readyDeadline)
                ready = BridgeHealthQuery.query("127.0.0.1", health, 200);
            assertNotNull(ready, bridge.status());
            assertTrue(bridge.offer(new CanonicalEvent(1, 1, () -> "{\"seq\":1}")));
            assertTrue(bridge.offerTerminal(new CanonicalEvent(2, 2, () -> "{\"seq\":2}")));
            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(0);
            receiver.setReceiveTimeOut(2000);
            receiver.connect("tcp://127.0.0.1:" + market);
            receiver.send("HELLO owner 0");
            String welcome = receiver.recvStr(),
                    first = receiver.recvStr(),
                    terminal = receiver.recvStr();
            assertNotNull(welcome, bridge.status());
            assertNotNull(first, bridge.status());
            assertNotNull(terminal, bridge.status());
            assertTrue(welcome.contains("WELCOME"));
            assertTrue(first.contains("\"seq\":1"));
            assertTrue(terminal.contains("\"seq\":2"));
            receiver.send("ACK owner 2");
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (bridge.depth() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(0, bridge.depth());
            bridge.close();
            assertTrue(bridge.status().contains("\"delivery_complete\":true"));
            assertTrue(bridge.status().contains("\"state\":\"STOPPED\""));
            assertTrue(
                    bridge.status()
                            .contains(
                                    "\"ack_guarantee\":\"receiver_validated_in_memory_not_durable\""));
            assertFalse(bridge.invalid());
        } finally {
            bridge.close();
        }
    }

    @Test
    void probeReadsHealthWithoutRegisteringAReceiver() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        var bridge =
                new LiveBridge(
                        "127.0.0.1",
                        market,
                        health,
                        100,
                        "SYNTH",
                        .25,
                        () -> "{\"writer_ok\":true,\"overflows\":0}");
        try {
            String result = null;
            for (int i = 0; i < 10 && result == null; i++)
                result = BridgeHealthQuery.query("127.0.0.1", health, 200);
            assertNotNull(result);
            assertTrue(result.contains("\"receiver\":\"none\""));
            assertTrue(result.contains("\"invalid\":false"));
            assertTrue(result.contains("\"state\":\"WAITING_RECEIVER\""));
        } finally {
            bridge.close();
        }
    }

    @Test
    void missingHealthServerTimesOut() throws Exception {
        assertNull(BridgeHealthQuery.query("127.0.0.1", ExporterTest.freePort(), 50));
    }

    @Test
    void reloadReleasesTheSamePortsBeforeRebind() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        var first = new LiveBridge("127.0.0.1", market, health, 100, "SYNTH", .25, () -> "{}");
        assertNotNull(BridgeHealthQuery.query("127.0.0.1", health, 1000));
        first.close();
        var second = new LiveBridge("127.0.0.1", market, health, 100, "SYNTH", .25, () -> "{}");
        try {
            String reply = BridgeHealthQuery.query("127.0.0.1", health, 1000);
            assertNotNull(reply);
            assertTrue(reply.contains("\"invalid\":false"));
        } finally {
            second.close();
        }
    }

    @Test
    void disconnectedTransportRetainsEventsForSameOwner() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        var bridge = new LiveBridge("127.0.0.1", market, health, 100, "SYNTH", .25, () -> "{}");
        try (var context = new ZContext()) {
            var first = context.createSocket(SocketType.DEALER);
            first.setIdentity("owner".getBytes());
            first.setLinger(0);
            first.setReceiveTimeOut(2000);
            first.connect("tcp://127.0.0.1:" + market);
            first.send("HELLO owner 0");
            assertTrue(first.recvStr().contains("WELCOME"));
            first.close();
            Thread.sleep(200); // Let ROUTER observe the closed peer, before its inactivity timeout.
            bridge.offer(new CanonicalEvent(1, 1, () -> "{\"seq\":1}"));
            Thread.sleep(200);
            assertFalse(bridge.invalid());
            assertEquals(1, bridge.depth());
            var replacement = context.createSocket(SocketType.DEALER);
            replacement.setIdentity("owner".getBytes());
            replacement.setLinger(0);
            replacement.setReceiveTimeOut(2000);
            replacement.connect("tcp://127.0.0.1:" + market);
            replacement.send("HELLO owner 0");
            assertTrue(replacement.recvStr().contains("WELCOME"));
            String event = replacement.recvStr();
            assertNotNull(event);
            assertTrue(event.contains("\"event\":{\"seq\":1}"));
            replacement.send("ACK owner 1");
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (bridge.depth() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(0, bridge.depth());
            assertFalse(bridge.invalid());
        } finally {
            bridge.close();
        }
    }

    @Test
    void historicalQueueWaitsForAckWithoutOverflow() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        var bridge = new LiveBridge("127.0.0.1", market, health, 1, "SYNTH", .25, () -> "{}");
        try (var context = new ZContext()) {
            assertTrue(bridge.offerHistorical(new CanonicalEvent(1, 1, () -> "{}")));
            var pending =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> bridge.offerHistorical(new CanonicalEvent(2, 2, () -> "{}")));
            Thread.sleep(100);
            assertFalse(pending.isDone());
            assertFalse(bridge.invalid());
            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(0);
            receiver.setReceiveTimeOut(2000);
            receiver.connect("tcp://127.0.0.1:" + market);
            receiver.send("HELLO owner 0");
            assertTrue(receiver.recvStr().contains("WELCOME"));
            assertTrue(receiver.recvStr().contains("\"type\":\"EVENT\""));
            receiver.send("ACK owner 1");
            assertTrue(pending.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(receiver.recvStr().contains("\"type\":\"EVENT\""));
            receiver.send("ACK owner 2");
            bridge.finishHistorical();
            assertEquals(0, bridge.depth());
            assertFalse(bridge.invalid());
        } finally {
            bridge.close();
        }
    }
}
