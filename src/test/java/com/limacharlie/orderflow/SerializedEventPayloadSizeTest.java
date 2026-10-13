package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Deterministic serialized payload size check: two otherwise-identical events whose only
 * difference is the synthetic ASCII order ID. Measures UTF-8 byte length of json() output —
 * this is serialized payload size, NOT retained JVM heap. No queues or saturation modeled.
 */
class SerializedEventPayloadSizeTest {

    @Test
    void serializedUtf8PayloadGrowsExactlyWithOrderIdByteLength() {
        String shortId = "a"; // 1 ASCII byte
        String longId = "aaaaaaaaaaaaaaaa"; // 16 ASCII bytes, exactly 15 longer than the short ID

        CanonicalEvent shortEvent = new CanonicalEvent(1, 1_000_000_000L, () -> "{\"order_id\":\"" + shortId + "\"}");
        CanonicalEvent longEvent = new CanonicalEvent(2, 1_000_000_000L, () -> "{\"order_id\":\"" + longId + "\"}");

        byte[] shortPayload = shortEvent.json().getBytes(StandardCharsets.UTF_8);
        byte[] longPayload = longEvent.json().getBytes(StandardCharsets.UTF_8);

        int idByteLengthDifference =
                longId.getBytes(StandardCharsets.UTF_8).length - shortId.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(15, idByteLengthDifference);
        // Exact serialized payload size increase equals the ID byte-length difference.
        assertEquals(idByteLengthDifference, longPayload.length - shortPayload.length);
        assertTrue(longPayload.length > shortPayload.length);
    }
}
