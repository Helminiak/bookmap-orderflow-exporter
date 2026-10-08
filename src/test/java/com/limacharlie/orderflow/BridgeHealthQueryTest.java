package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.zeromq.*;

class BridgeHealthQueryTest {
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
}
