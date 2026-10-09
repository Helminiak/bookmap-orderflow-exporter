package com.limacharlie.orderflow;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Log2 nanosecond histogram: quantiles are upper bounds, no per-callback allocation. */
public final class CallbackMetrics {
    private final AtomicLong count = new AtomicLong(),
            total = new AtomicLong(),
            max = new AtomicLong();
    private final AtomicLongArray buckets = new AtomicLongArray(64);
    private final long started = System.nanoTime();
    private long window = started, windowCount;
    private volatile long peakRate;

    public void record(long elapsed) {
        elapsed = Math.max(1, elapsed);
        count.incrementAndGet();
        total.addAndGet(elapsed);
        max.accumulateAndGet(elapsed, Math::max);
        buckets.incrementAndGet(Math.min(63, 64 - Long.numberOfLeadingZeros(elapsed)));
        long now = System.nanoTime();
        windowCount++;
        if (now - window >= 1_000_000_000L) {
            peakRate = Math.max(peakRate, (long) (windowCount * 1e9 / (now - window)));
            window = now;
            windowCount = 0;
        }
    }

    private long quantile(double fraction) {
        if (count.get() == 0) return 0;
        long target = (long) Math.ceil(count.get() * fraction), sum = 0;
        for (int i = 0; i < 63; i++) {
            sum += buckets.get(i);
            if (sum >= target) return 1L << i;
        }
        return Long.MAX_VALUE;
    }

    public String display() {
        long n = count.get();
        return "count="
                + n
                + " mean="
                + (n == 0 ? 0 : total.get() / n / 1000.0)
                + " us; p50<="
                + quantile(.5) / 1000.0
                + " us; p95<="
                + quantile(.95) / 1000.0
                + " us; p99<="
                + quantile(.99) / 1000.0
                + " us; max="
                + max.get() / 1000.0
                + " us; peak="
                + peakRate
                + "/s";
    }

    public String json() {
        long n = count.get();
        return "{\"count\":"
                + n
                + ",\"mean_ns\":"
                + (n == 0 ? 0 : total.get() / n)
                + ",\"p50_upper_ns\":"
                + quantile(.5)
                + ",\"p95_upper_ns\":"
                + quantile(.95)
                + ",\"p99_upper_ns\":"
                + quantile(.99)
                + ",\"max_ns\":"
                + max.get()
                + ",\"rate_per_sec\":"
                + n * 1e9 / Math.max(1, System.nanoTime() - started)
                + ",\"peak_one_second_rate\":"
                + peakRate
                + "}";
    }
}
