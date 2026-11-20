package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.zeromq.*;

/** D-05-1 bridge-level state-transition and counter invariants (deterministic, loopback only). */
class LiveBridgeAdmissionTest {
    static LiveBridge bridge(int capacity, long waitNanos) throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        while (health == market) health = ExporterTest.freePort();
        return new LiveBridge("127.0.0.1", market, health, capacity, "SYNTH", .25, () -> "{}", waitNanos);
    }

    static String field(String json, String key) {
        var m = Pattern.compile("\"" + key + "\":(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,}]*)").matcher(json);
        assertTrue(m.find(), key + " missing: " + json);
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    static CanonicalEvent event(long seq) {
        return new CanonicalEvent(seq, seq, () -> "{\"seq\":" + seq + "}");
    }

    @Test
    void productionKeepsTheTenSecondBound() throws Exception {
        assertEquals(10_000_000_000L, LiveBridge.DEFAULT_HISTORICAL_WAIT_NANOS);
        assertEquals(
                LiveBridge.DEFAULT_HISTORICAL_WAIT_NANOS,
                new BookmapOrderflowExporter().bridgeHistoricalWaitNanos);
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        while (health == market) health = ExporterTest.freePort();
        // The public (production) constructor cannot take a shorter value.
        try (var production = new LiveBridge("127.0.0.1", market, health, 4, "SYNTH", .25, () -> "{}")) {
            assertEquals(10_000_000_000L, production.historicalWaitNanos());
        }
        assertThrows(IllegalArgumentException.class, () -> bridge(4, 0));
        assertThrows(IllegalArgumentException.class, () -> bridge(4, -1));
    }

    @Test
    void firstFailureWinsAndShutdownDoesNotOverwriteTheRootCause() throws Exception {
        var bridge = bridge(4, 200_000_000L);
        try {
            assertTrue(bridge.offerHistorical(event(1)));
            assertTrue(bridge.offerHistorical(event(2)));
            bridge.invalidate("first cause");
            bridge.invalidate("second symptom");
            assertEquals("first cause", bridge.failureReason());
        } finally {
            bridge.close(); // would previously relabel to "shutdown with unacknowledged events"
        }
        String status = bridge.status();
        assertEquals("first cause", field(status, "reason"));
        assertEquals("BRIDGE_INVALID", field(status, "failure_code"));
        assertEquals("INVALID", field(status, "state"));
    }

    @Test
    void drainWithoutAdmissionIsAnAdmissionFailureEvenBelowCapacity() throws Exception {
        var bridge = bridge(4, 300_000_000L);
        try {
            assertTrue(bridge.offerHistorical(event(1)));
            assertTrue(bridge.offerHistorical(event(2)));
            assertTimeoutPreemptively(Duration.ofSeconds(5), bridge::finishHistorical);
            String status = bridge.status();
            assertEquals("RECEIVER_ADMISSION_TIMEOUT", field(status, "failure_code"));
            assertTrue(field(status, "reason").contains("historical receiver drain timeout"));
            assertTrue(field(status, "reason").contains("receiver admission failed"));
            assertTrue(field(status, "reason").contains("fresh START"));
            assertEquals("false", field(status, "delivery_complete"));
        } finally {
            bridge.close();
        }
    }

    @Test
    void timeoutTriggeringEventIsCountedAndInvalidBridgeNeverWaitsAgain() throws Exception {
        var bridge = bridge(1, 200_000_000L);
        try {
            assertTrue(bridge.offerHistorical(event(1)));
            long begun = System.nanoTime();
            assertFalse(bridge.offerHistorical(event(2))); // waits ~200 ms, then terminal failure
            long waitedMs = (System.nanoTime() - begun) / 1_000_000L;
            assertTrue(waitedMs >= 150 && waitedMs < 3_000, "bounded wait, was " + waitedMs);
            // Once terminal, further offers return immediately: no unbounded or repeated waiting.
            long again = System.nanoTime();
            for (long seq = 3; seq <= 5_000; seq++) assertFalse(bridge.offerHistorical(event(seq)));
            assertTrue((System.nanoTime() - again) / 1_000_000L < 1_000);
            bridge.finishHistorical();
        } finally {
            bridge.close();
        }
        String status = bridge.status();
        // event 1 discarded from the queue; events 2..5000 (incl. the trigger) dropped.
        assertEquals("5000", field(status, "last_offered_seq"));
        assertEquals("1", field(status, "discarded_unacknowledged"));
        assertEquals("4999", field(status, "dropped_after_invalid"));
        assertEquals("0", field(status, "events_published"));
        assertEquals("1", field(status, "unconfirmed_from_seq"));
        assertEquals("5000", field(status, "unconfirmed_through_seq"));
        assertEquals("RECEIVER_ADMISSION_TIMEOUT", field(status, "failure_code"));
    }

    @Test
    void admittedReceiverThatStopsAckingIsNotAnAdmissionFailure() throws Exception {
        var bridge = bridge(1, 300_000_000L);
        try (var context = new ZContext()) {
            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(0);
            receiver.setReceiveTimeOut(2000);
            receiver.connect("tcp://127.0.0.1:" + portOf(bridge));
            receiver.send("HELLO owner 0");
            assertTrue(receiver.recvStr().contains("WELCOME"));
            assertTrue(bridge.offerHistorical(event(1)));
            assertNotNull(receiver.recvStr()); // received, never ACKed
            assertFalse(bridge.offerHistorical(event(2)));
            String status = bridge.status();
            assertEquals("RECEIVER_ACK_TIMEOUT", field(status, "failure_code"));
            assertEquals(
                    "historical receiver backpressure timeout; start receiver then fresh START",
                    field(status, "reason"));
            assertEquals("owner", field(status, "receiver"));
        } finally {
            bridge.close();
        }
    }

    static int portOf(LiveBridge bridge) throws Exception {
        var f = LiveBridge.class.getDeclaredField("port");
        f.setAccessible(true);
        return f.getInt(bridge);
    }
}
