package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.zeromq.*;

import java.util.UUID;
import java.util.function.Supplier;

/** Single-attempt test peer. Diagnostics never retry HELLO or extend the receive timeout. */
final class SyntheticBridgePeer implements AutoCloseable {
    private final ZMQ.Socket dealer;
    private final ZMQ.Socket monitor;
    private final Supplier<String> health;
    private final long started = System.nanoTime();
    private final StringBuilder trace = new StringBuilder();

    SyntheticBridgePeer(ZContext context, int port, Supplier<String> health) {
        this.health = health;
        dealer = context.createSocket(SocketType.DEALER);
        dealer.setLinger(0);
        dealer.setReceiveTimeOut(2000); // Preserve existing per-frame deadline.
        String endpoint = "inproc://synthetic-peer-" + UUID.randomUUID();
        assertTrue(dealer.monitor(endpoint, ZMQ.EVENT_ALL), "monitor setup failed");
        monitor = context.createSocket(SocketType.PAIR);
        monitor.setLinger(0);
        assertTrue(monitor.connect(endpoint), "monitor connection failed");
        assertTrue(dealer.connect("tcp://127.0.0.1:" + port), "DEALER connect rejected");
        mark("connect accepted port=" + port);
    }

    void send(String command) {
        boolean accepted = dealer.send(command);
        mark("send " + command.split(" ")[0] + " accepted=" + accepted);
        assertTrue(accepted, this::diagnostics);
    }

    String receive(String expected) {
        String reply = dealer.recvStr();
        mark("receive " + expected + " present=" + (reply != null));
        assertNotNull(reply, this::diagnostics);
        return reply;
    }

    private void mark(String event) {
        trace.append((System.nanoTime() - started) / 1_000_000)
                .append("ms ")
                .append(event)
                .append(';');
    }

    private String diagnostics() {
        ZMQ.Event event;
        while ((event = ZMQ.Event.recv(monitor, ZMQ.DONTWAIT)) != null)
            trace.append(" monitor=")
                    .append(event.getEvent())
                    .append(':')
                    .append(event.getAddress())
                    .append(';');
        return "Single-attempt bridge trace: " + trace + " health=" + health.get();
    }

    @Override
    public void close() {
        dealer.close();
        monitor.close();
    }
}
