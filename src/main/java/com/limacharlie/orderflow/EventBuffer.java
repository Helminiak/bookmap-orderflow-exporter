package com.limacharlie.orderflow;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Bounded MPSC buffer. offer has no locks/waits; blocking put is historical-only. */
final class EventBuffer {
    private final ConcurrentLinkedQueue<CanonicalEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger depth = new AtomicInteger();
    private final int capacity;

    EventBuffer(int capacity) {
        this.capacity = capacity;
    }

    boolean offer(CanonicalEvent event) {
        int n;
        do {
            n = depth.get();
            if (n >= capacity) return false;
        } while (!depth.compareAndSet(n, n + 1));
        queue.add(event);
        return true;
    }

    void put(CanonicalEvent event) throws InterruptedException {
        while (!offer(event)) {
            if (Thread.interrupted()) throw new InterruptedException();
            LockSupport.parkNanos(100_000);
        }
    }

    CanonicalEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        do {
            CanonicalEvent e = queue.poll();
            if (e != null) {
                depth.decrementAndGet();
                return e;
            }
            if (Thread.interrupted()) throw new InterruptedException();
            LockSupport.parkNanos(Math.min(100_000, Math.max(1, deadline - System.nanoTime())));
        } while (System.nanoTime() < deadline);
        return null;
    }

    int size() {
        return depth.get();
    }
}
