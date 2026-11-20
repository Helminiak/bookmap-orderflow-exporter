package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.zeromq.*;

/**
 * D-05-1 follow-up: delivery accounting must stay exact on every terminal path:
 * acknowledged + discarded + dropped == last_offered_seq once the bridge is closed.
 */
class LiveBridgeAccountingTest {
    static long num(String json, String key) {
        var m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue(m.find(), key + " missing: " + json);
        return Long.parseLong(m.group(1));
    }

    static void assertExact(String status) {
        assertEquals(
                num(status, "last_offered_seq"),
                num(status, "acknowledged_seq")
                        + num(status, "discarded_unacknowledged")
                        + num(status, "dropped_after_invalid"),
                status);
        assertEquals(0, num(status, "queue_depth"), status);
    }

    @Test
    void liveOverflowTriggerEventIsCountedAsDropped() throws Exception {
        var bridge = LiveBridgeAdmissionTest.bridge(2, 200_000_000L);
        try {
            assertTrue(bridge.offer(LiveBridgeAdmissionTest.event(1)));
            assertTrue(bridge.offer(LiveBridgeAdmissionTest.event(2)));
            assertFalse(bridge.offer(LiveBridgeAdmissionTest.event(3))); // overflow trigger
            assertFalse(bridge.offer(LiveBridgeAdmissionTest.event(4))); // after INVALID
        } finally {
            bridge.close();
        }
        String status = bridge.status();
        assertEquals(1, num(status, "overflows"));
        assertEquals("outbound buffer overflow", LiveBridgeAdmissionTest.field(status, "reason"));
        assertEquals("BRIDGE_INVALID", LiveBridgeAdmissionTest.field(status, "failure_code"));
        assertEquals(4, num(status, "last_offered_seq"));
        assertEquals(2, num(status, "discarded_unacknowledged")); // seq 1, 2 queued, never sent
        assertEquals(2, num(status, "dropped_after_invalid")); // seq 3 (trigger) and 4
        assertEquals(0, num(status, "events_published"));
        assertExact(status);
    }

    @Test
    void lateAckAfterInvalidDoesNotDoubleCountDiscardedEvents() throws Exception {
        var bridge = LiveBridgeAdmissionTest.bridge(8, 200_000_000L);
        try (var context = new ZContext()) {
            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(1000);
            receiver.setReceiveTimeOut(2000);
            receiver.connect("tcp://127.0.0.1:" + LiveBridgeAdmissionTest.portOf(bridge));
            receiver.send("HELLO owner 0");
            assertTrue(receiver.recvStr().contains("WELCOME"));
            for (long seq = 1; seq <= 4; seq++) assertTrue(bridge.offer(LiveBridgeAdmissionTest.event(seq)));
            for (int i = 0; i < 4; i++) assertNotNull(receiver.recvStr(), "event " + (i + 1)); // in flight, unACKed
            bridge.invalidate("test latch");
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (num(bridge.status(), "discarded_unacknowledged") < 4 && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertEquals(4, num(bridge.status(), "discarded_unacknowledged"));
            // The receiver's ACK for those same events arrives AFTER the latch. Commands from one
            // DEALER are processed in order, so the WELCOME to the HELLO that follows is a
            // deterministic barrier: its health snapshot is taken after the ACK was handled.
            receiver.send("ACK owner 4");
            receiver.send("HELLO owner 4");
            String welcome = receiver.recvStr();
            assertNotNull(welcome);
            assertTrue(welcome.contains("WELCOME"), welcome);
            assertEquals(0, num(welcome, "acknowledged_seq"), welcome);
            assertEquals(4, num(welcome, "discarded_unacknowledged"), welcome);
        } finally {
            bridge.close();
        }
        String status = bridge.status();
        assertEquals("test latch", LiveBridgeAdmissionTest.field(status, "reason"));
        assertEquals(0, num(status, "acknowledged_seq"));
        assertEquals(4, num(status, "discarded_unacknowledged"));
        assertEquals(1, num(status, "unconfirmed_from_seq"));
        assertEquals(4, num(status, "unconfirmed_through_seq"));
        assertEquals("false", LiveBridgeAdmissionTest.field(status, "delivery_complete"));
        assertExact(status);
    }

    @Test
    void ackBeforeInvalidStillCountsAsAcknowledged() throws Exception {
        var bridge = LiveBridgeAdmissionTest.bridge(8, 200_000_000L);
        try (var context = new ZContext()) {
            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(1000);
            receiver.setReceiveTimeOut(2000);
            receiver.connect("tcp://127.0.0.1:" + LiveBridgeAdmissionTest.portOf(bridge));
            receiver.send("HELLO owner 0");
            assertTrue(receiver.recvStr().contains("WELCOME"));
            for (long seq = 1; seq <= 4; seq++) assertTrue(bridge.offer(LiveBridgeAdmissionTest.event(seq)));
            for (int i = 0; i < 4; i++) assertNotNull(receiver.recvStr());
            receiver.send("ACK owner 2");
            receiver.send("HELLO owner 2"); // barrier: ACK 2 handled while still valid
            assertTrue(receiver.recvStr().contains("WELCOME"));
            bridge.invalidate("test latch");
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (num(bridge.status(), "discarded_unacknowledged") < 2 && System.nanoTime() < deadline)
                Thread.sleep(5);
        } finally {
            bridge.close();
        }
        String status = bridge.status();
        assertEquals(2, num(status, "acknowledged_seq")); // acknowledged while valid: stays counted
        assertEquals(2, num(status, "discarded_unacknowledged")); // seq 3, 4
        assertEquals(3, num(status, "unconfirmed_from_seq"));
        assertEquals(4, num(status, "unconfirmed_through_seq"));
        assertExact(status);
    }
}
