package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * Regression tests for the R-12 bridge protocol findings and the R-03 safe-publication finding.
 * Each test fails against the pre-fix implementation:
 *
 * <ul>
 *   <li>{@code quote} emitted raw C0 controls (tab, \u0001, ...) into JSON strings — invalid per RFC 8259.
 *   <li>A non-numeric ACK third field threw NumberFormatException into the worker catch-all and invalidated the bridge.
 *   <li>One failed event send incremented {@code send_failures} twice (once in send(), once in sendEvent()).
 *   <li>{@code sequence}/{@code firstMarketNs}/{@code lastMarketNs} were plain longs read from other threads.
 * </ul>
 */
class BridgeProtocolHardeningTest {

    private static int sendFailures(String status) {
        Matcher m = Pattern.compile("\"send_failures\":(\\d+)").matcher(status);
        assertTrue(m.find(), "status must expose send_failures: " + status);
        return Integer.parseInt(m.group(1));
    }

    // Reflection keeps this test compiling against both the old (private) and new
    // (package-private) visibility of quote().
    private static String q(String value) throws Exception {
        Method m = LiveBridge.class.getDeclaredMethod("quote", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    @Test
    void quoteEscapesEveryC0ControlCharacter() throws Exception {
        StringBuilder all = new StringBuilder();
        for (int c = 0; c < 0x20; c++) all.append((char) c);
        String quoted = q(all.toString());
        // No raw control character may survive inside the JSON string.
        for (int i = 1; i < quoted.length() - 1; i++) {
            char c = quoted.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped byte of unicode/short escapes
                continue;
            }
            assertTrue(c >= 0x20, "raw control U+%04X leaked into JSON string: %s".formatted((int) c, quoted));
        }
        assertEquals("\"\\t\"", q("\t"));
        assertEquals("\"\\u0001\"", q("\u0001"));
        assertEquals("\"a\\\\b\\\"c\"", q("a\\b\"c"));
        assertEquals("\"line1\\nline2\\r\"", q("line1\nline2\r"));
    }

    @Test
    void malformedAckDoesNotTerminatePublisher() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        while (health == market) health = ExporterTest.freePort();
        var bridge = new LiveBridge("127.0.0.1", market, health, 4, "SYNTH", .25, () -> "{}");
        try (var context = new ZContext()) {
            String ready = null;
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (ready == null && !bridge.invalid() && System.nanoTime() < deadline)
                ready = BridgeHealthQuery.query("127.0.0.1", health, 200);
            assertNotNull(ready, bridge.status());

            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(0);
            receiver.setReceiveTimeOut(2000);
            receiver.connect("tcp://127.0.0.1:" + market);
            receiver.send("HELLO owner 0");
            assertTrue(receiver.recvStr().contains("WELCOME"), bridge.status());

            // The defect: a non-numeric third field used to throw NumberFormatException into the
            // worker catch-all and invalidate the whole bridge ("publisher failure").
            receiver.send("ACK owner xyz");
            Thread.sleep(300);
            assertFalse(bridge.invalid(), "malformed ACK must not invalidate the bridge: " + bridge.status());
            assertTrue(bridge.status().contains("\"state\":\"CONNECTED\""), bridge.status());

            // The worker must still serve normally afterwards.
            assertTrue(bridge.offer(new CanonicalEvent(1, 1, () -> "{\"seq\":1}")));
            String event = receiver.recvStr();
            assertNotNull(event, bridge.status());
            assertTrue(event.contains("\"seq\":1"));
            receiver.send("ACK owner 1");
            long drainDeadline = System.nanoTime() + 2_000_000_000L;
            while (bridge.depth() != 0 && System.nanoTime() < drainDeadline) Thread.sleep(1);
            assertEquals(0, bridge.depth());
            assertFalse(bridge.invalid(), bridge.status());
        } finally {
            bridge.close();
        }
    }

    @Test
    void failedEventSendCountsExactlyOnce() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        while (health == market) health = ExporterTest.freePort();
        var bridge = new LiveBridge("127.0.0.1", market, health, 4, "SYNTH", .25, () -> "{}");
        // A mandatory ROUTER with no connected peer makes every send throw — the exception path
        // that used to be double-counted (send() and sendEvent() both incremented).
        try (var context = new ZContext()) {
            var router = context.createSocket(SocketType.ROUTER);
            router.setRouterMandatory(true);
            Method sendEvent =
                    LiveBridge.class.getDeclaredMethod(
                            "sendEvent", ZMQ.Socket.class, byte[].class, CanonicalEvent.class, boolean.class);
            sendEvent.setAccessible(true);
            for (int i = 0; i < 2; i++) {
                assertFalse((Boolean) sendEvent.invoke(bridge, router, new byte[] {1}, new CanonicalEvent(i + 1, i + 1, () -> "{}"), false));
            }
            assertEquals(2, sendFailures(bridge.status()), "each failed send must count exactly once: " + bridge.status());
        } finally {
            bridge.close();
        }
    }

    @Test
    void exporterCountersAreVolatileForCrossThreadReads() throws Exception {
        // R-03 guard: these counters are written by Bookmap callbacks and read from UI/status and
        // shutdown threads. Volatile is the JMM contract; pin it so a refactor cannot regress it.
        for (String name : new String[] {"sequence", "firstMarketNs", "lastMarketNs"}) {
            Field f = BookmapOrderflowExporter.class.getDeclaredField(name);
            assertTrue(
                    Modifier.isVolatile(f.getModifiers()),
                    name + " must be volatile: callbacks write it, UI/shutdown threads read it");
        }
    }
}
