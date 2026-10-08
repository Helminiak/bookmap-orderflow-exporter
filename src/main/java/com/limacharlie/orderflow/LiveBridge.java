package com.limacharlie.orderflow;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Single receiver, cumulative ACKs, bounded unacknowledged events. All sockets belong to this
 * worker.
 */
public final class LiveBridge implements AutoCloseable {
    public static final String PROTOCOL = "orderflow-live-v0.1";
    private final String bind, alias, session = UUID.randomUUID().toString();
    private final int port, healthPort, capacity;
    private final double pips;
    private final Supplier<String> journalHealth;
    private final ConcurrentLinkedQueue<CanonicalEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger depth = new AtomicInteger(), high = new AtomicInteger();
    private final AtomicLong overflows = new AtomicLong();
    private volatile boolean running = true, invalid;
    private volatile String reason = "", state = "STARTING", receiver = "none";
    private volatile long discarded,
            dropped,
            published,
            bytes,
            lastSeq,
            lastMarketNs,
            acknowledged,
            sendFailures,
            reconnects;
    private final long started = System.nanoTime();
    private final Thread worker;

    public LiveBridge(
            String bind,
            int port,
            int healthPort,
            int capacity,
            String alias,
            double pips,
            Supplier<String> journalHealth) {
        if (!Double.isFinite(pips)
                || pips <= 0
                || capacity < 1
                || port < 1
                || port > 65535
                || healthPort < 1
                || healthPort > 65535
                || port == healthPort)
            throw new IllegalArgumentException("Invalid bridge capacity/ports");
        this.bind = bind;
        this.port = port;
        this.healthPort = healthPort;
        this.capacity = capacity;
        this.alias = alias;
        this.pips = pips;
        this.journalHealth = journalHealth;
        worker = new Thread(this::run, "orderflow-live-publisher");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * No queue waits, network, JSON or disk I/O. CAS reservation bounds queued + in-flight memory.
     */
    public boolean offer(CanonicalEvent event) {
        if (invalid || !running) {
            dropped++;
            return false;
        }
        int n;
        do {
            n = depth.get();
            if (n >= capacity) {
                overflows.incrementAndGet();
                invalidate("outbound buffer overflow");
                return false;
            }
        } while (!depth.compareAndSet(n, n + 1));
        high.accumulateAndGet(n + 1, Math::max);
        queue.add(event);
        return true;
    }

    /** Historical catch-up may wait for ACK capacity; LIVE must always use offer(). */
    public boolean offerHistorical(CanonicalEvent event) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (depth.get() >= capacity && running && !invalid) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                invalidate(
                        "historical receiver backpressure timeout; start receiver then fresh"
                            + " START");
                return false;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
        return offer(event);
    }

    /** Drain historical backlog before the callback stream switches to non-waiting LIVE. */
    public void finishHistorical() {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (depth.get() > 0 && running && !invalid) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                invalidate("historical receiver drain timeout; fresh START required");
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
    }

    public void invalidate(String why) {
        reason = why;
        invalid = true;
        state = "INVALID";
    }

    public boolean invalid() {
        return invalid;
    }

    public int depth() {
        return depth.get();
    }

    private static String quote(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                + "\"";
    }

    public String display() {
        double seconds = Math.max(.001, (System.nanoTime() - started) / 1e9);
        return state
                + " | receiver="
                + (receiver.equals("none") ? "waiting" : "registered")
                + " | published="
                + published
                + " | rate="
                + String.format("%.0f/s", published / seconds)
                + " | last seq="
                + lastSeq
                + " | acknowledged="
                + acknowledged
                + " | queue="
                + depth
                + "/"
                + capacity
                + " | high water="
                + high
                + " | overflows="
                + overflows
                + " | invalid="
                + invalid
                + " "
                + reason;
    }

    public String status() {
        double seconds = Math.max(.001, (System.nanoTime() - started) / 1e9);
        return "{\"protocol\":"
                + quote(PROTOCOL)
                + ",\"addon_version\":\"0.5.0\",\"session\":"
                + quote(session)
                + ",\"alias\":"
                + quote(alias)
                + ",\"pips\":"
                + pips
                + ",\"state\":"
                + quote(state)
                + ",\"invalid\":"
                + invalid
                + ",\"reason\":"
                + quote(reason)
                + ",\"receiver\":"
                + quote(receiver)
                + ",\"queue_depth\":"
                + depth
                + ",\"queue_capacity\":"
                + capacity
                + ",\"queue_high_water\":"
                + high
                + ",\"discarded_unacknowledged\":"
                + discarded
                + ",\"dropped_after_invalid\":"
                + dropped
                + ",\"overflows\":"
                + overflows
                + ",\"events_published\":"
                + published
                + ",\"bytes_published\":"
                + bytes
                + ",\"events_per_sec\":"
                + published / seconds
                + ",\"bytes_per_sec\":"
                + bytes / seconds
                + ",\"last_published_seq\":"
                + lastSeq
                + ",\"market_ns\":"
                + lastMarketNs
                + ",\"acknowledged_seq\":"
                + acknowledged
                + ",\"send_failures\":"
                + sendFailures
                + ",\"reconnects\":"
                + reconnects
                + ",\"journal\":"
                + journalHealth.get()
                + "}";
    }

    private void run() {
        ArrayDeque<CanonicalEvent> flight = new ArrayDeque<>();
        byte[] identity = null;
        String owner = null;
        long active = 0;
        try (ZContext context = new ZContext()) {
            ZMQ.Socket data = context.createSocket(SocketType.ROUTER),
                    health = context.createSocket(SocketType.REP);
            data.setLinger(0);
            health.setLinger(0);
            data.setRouterMandatory(true);
            data.setSndHWM(512);
            data.setRcvHWM(512);
            health.setSendTimeOut(0);
            if (!data.bind("tcp://" + bind + ":" + port)
                    || !health.bind("tcp://" + bind + ":" + healthPort))
                throw new IllegalStateException("Unable to bind ports");
            state = "WAITING_RECEIVER";
            while (running) {
                byte[] id = data.recv(ZMQ.DONTWAIT);
                if (id != null) {
                    String command = data.recvStr();
                    // Exactly one payload frame; reject malformed multipart commands.
                    boolean malformed = data.hasReceiveMore();
                    while (data.hasReceiveMore()) data.recv();
                    String[] fields = command.split(" ");
                    if (!malformed
                            && fields.length == 3
                            && (fields[0].equals("HELLO") || fields[0].equals("ACK"))) {
                        long ack = Long.parseLong(fields[2]);
                        if (owner != null && !owner.equals(fields[1]))
                            invalidate("receiver restarted; fresh exporter START required");
                        else if (ack < acknowledged || ack > lastSeq)
                            invalidate("invalid receiver acknowledgement");
                        else {
                            boolean hello = fields[0].equals("HELLO");
                            if (owner == null && (!hello || ack != 0))
                                invalidate("receiver must begin at START");
                            else {
                                if (owner != null && hello) reconnects++;
                                owner = fields[1];
                                identity = id;
                                receiver = owner;
                                active = System.nanoTime();
                                acknowledged = ack;
                                while (!flight.isEmpty() && flight.peek().seq <= ack) {
                                    flight.remove();
                                    depth.decrementAndGet();
                                }
                                if (!invalid) state = "CONNECTED";
                                if (hello) {
                                    if (!send(
                                            data,
                                            id,
                                            "{\"type\":\"WELCOME\",\"health\":" + status() + "}")) {
                                        identity = null;
                                        state = "DISCONNECTED";
                                    } else {
                                        for (CanonicalEvent e : flight) {
                                            if (!sendEvent(data, id, e, true)) {
                                                identity = null;
                                                state = "DISCONNECTED";
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (health.recv(ZMQ.DONTWAIT) != null) health.send(status(), ZMQ.DONTWAIT);
                if (invalid) {
                    state = "INVALID";
                    discarded += depth.getAndSet(0);
                    queue.clear();
                    flight.clear();
                } else if (identity != null && System.nanoTime() - active < 2_000_000_000L) {
                    int batch = 0;
                    while (flight.size() < 256 && batch++ < 256) {
                        CanonicalEvent e = queue.poll();
                        if (e == null) break;
                        flight.add(e);
                        if (!sendEvent(data, identity, e, false)) {
                            // Retain the event in flight until this owner reconnects and ACKs.
                            // A failed send does not prove delivery; HELLO resumes from its ACK.
                            identity = null;
                            state = "DISCONNECTED";
                            break;
                        }
                    }
                } else if (identity != null) {
                    identity = null;
                    state = "DISCONNECTED";
                }
                // Worker pacing only. Never sleeps a Bookmap callback.
                java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
            }
        } catch (Throwable failure) {
            invalidate("publisher failure: " + failure);
        }
    }

    private boolean send(ZMQ.Socket socket, byte[] identity, String body) {
        try {
            return socket.send(identity, ZMQ.SNDMORE | ZMQ.DONTWAIT)
                    && socket.send(body, ZMQ.DONTWAIT);
        } catch (RuntimeException failure) {
            sendFailures++;
            return false;
        }
    }

    private boolean sendEvent(
            ZMQ.Socket socket, byte[] identity, CanonicalEvent event, boolean replay) {
        String body =
                "{\"type\":\"EVENT\",\"protocol\":"
                        + quote(PROTOCOL)
                        + ",\"session\":"
                        + quote(session)
                        + ",\"sent_epoch_ns\":"
                        + System.currentTimeMillis() * 1_000_000L
                        + ",\"replay\":"
                        + replay
                        + ",\"event\":"
                        + event.json()
                        + "}";
        if (!send(socket, identity, body)) {
            sendFailures++;
            return false;
        }
        published++;
        bytes += body.getBytes(StandardCharsets.UTF_8).length;
        lastSeq = Math.max(lastSeq, event.seq);
        lastMarketNs = event.marketNs;
        return true;
    }

    /**
     * Lifecycle-only shutdown. Finish ACK grace and release sockets before a settings reload binds
     * again.
     */
    @Override
    public void close() {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (!invalid && depth.get() > 0 && System.nanoTime() < deadline)
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        if (depth.get() > 0) invalidate("shutdown with unacknowledged events");
        running = false;
        worker.interrupt();
        if (Thread.currentThread() != worker) {
            try {
                worker.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
