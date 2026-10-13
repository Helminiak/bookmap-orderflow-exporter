package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EventBufferCapacityTest {
    @Test
    void capacityTwoRejectsThirdAndDrainsFifoWithoutRetention() throws Exception {
        EventBuffer buffer = new EventBuffer(2);
        CanonicalEvent a = new CanonicalEvent(1, 100, () -> "{\"seq\":1}");
        CanonicalEvent b = new CanonicalEvent(2, 200, () -> "{\"seq\":2}");
        CanonicalEvent c = new CanonicalEvent(3, 300, () -> "{\"seq\":3}");

        assertTrue(buffer.offer(a));
        assertTrue(buffer.offer(b));
        assertEquals(2, buffer.size());

        assertFalse(buffer.offer(c));
        assertEquals(2, buffer.size());

        assertSame(a, buffer.poll(1, TimeUnit.MILLISECONDS));
        assertEquals(1, buffer.size());
        assertSame(b, buffer.poll(1, TimeUnit.MILLISECONDS));
        assertEquals(0, buffer.size());

        assertNull(buffer.poll(1, TimeUnit.MILLISECONDS));
    }
}
