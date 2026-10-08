package com.limacharlie.orderflow;

import java.util.function.Supplier;

/** Immutable callback copy; JSON is materialized only by background workers. */
public final class CanonicalEvent {
    public final long seq, marketNs;
    private final Supplier<String> encoder;
    private volatile String json;

    public CanonicalEvent(long seq, long marketNs, Supplier<String> encoder) {
        this.seq = seq;
        this.marketNs = marketNs;
        this.encoder = encoder;
    }

    public String json() {
        String value = json;
        if (value == null) {
            value = encoder.get();
            json = value;
        }
        return value;
    }
}
