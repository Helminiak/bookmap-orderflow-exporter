package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.zeromq.*;

/** Single-attempt diagnostic peer shared by bridge handshake tests. */
final class SyntheticBridgePeer implements AutoCloseable {
  private static final long RECEIVE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2);
  private final ZMQ.Socket dealer;
  private final ZMQ.Socket monitor;
  private final ZMQ.Poller poller;
  private final int dealerIndex;
  private final int monitorIndex;
  private final String id;
  private final String endpoint;
  private final AtomicLong releaseNanos;
  private final Supplier<String> health;
  private final long started = System.nanoTime();
  private final StringBuilder trace = new StringBuilder();
  private String lastObservedPublisher = "";

  SyntheticBridgePeer(ZContext context, int port, Supplier<String> health) {
    this(context, port, "peer", new AtomicLong(), health);
  }

  SyntheticBridgePeer(
      ZContext context,
      int port,
      String id,
      AtomicLong releaseNanos,
      Supplier<String> health) {
    this.id = id;
    endpoint = "tcp://127.0.0.1:" + port;
    this.releaseNanos = releaseNanos;
    this.health = health;
    dealer = context.createSocket(SocketType.DEALER);
    dealer.setLinger(0);
    String monitorEndpoint = "inproc://synthetic-peer-" + UUID.randomUUID();
    assertTrue(dealer.monitor(monitorEndpoint, ZMQ.EVENT_ALL), "monitor setup failed id=" + id);
    monitor = context.createSocket(SocketType.PAIR);
    monitor.setLinger(0);
    assertTrue(monitor.connect(monitorEndpoint), "monitor connect failed id=" + id);
    poller = context.createPoller(2);
    dealerIndex = poller.register(dealer, ZMQ.Poller.POLLIN);
    monitorIndex = poller.register(monitor, ZMQ.Poller.POLLIN);
    boolean accepted = dealer.connect(endpoint);
    mark("connect accepted=" + accepted + " endpoint=" + endpoint);
    assertTrue(accepted, diagnostics("connect rejected"));
    observePublisher("before HELLO");
  }

  void send(String command) {
    boolean accepted = dealer.send(command);
    mark("send " + command.split(" ")[0] + " accepted=" + accepted + " command=" + command);
    observePublisher("after " + command.split(" ")[0] + " send");
    assertTrue(accepted, diagnostics(command.split(" ")[0] + " send rejected"));
  }

  String receive(String expected) {
    long deadline = System.nanoTime() + RECEIVE_BUDGET_NANOS;
    while (System.nanoTime() < deadline) {
      long remaining = deadline - System.nanoTime();
      long waitMillis = Math.max(1, Math.min(5, TimeUnit.NANOSECONDS.toMillis(remaining)));
      poller.poll(waitMillis);
      drainMonitor();
      observePublisher("waiting for " + expected);
      if (poller.pollin(dealerIndex)) {
        String reply = dealer.recvStr(ZMQ.DONTWAIT);
        if (reply != null) {
          mark("DEALER received " + expected + " frame=" + reply);
          observePublisher("after receiving " + expected);
          return reply;
        }
      }
    }
    mark("receive " + expected + " present=false (DEALER timed out)");
    throw new AssertionError(diagnostics("missing " + expected));
  }

  private void drainMonitor() {
    if (!poller.pollin(monitorIndex)) return;
    ZMQ.Event event;
    while ((event = ZMQ.Event.recv(monitor, ZMQ.DONTWAIT)) != null)
      mark("monitor=" + eventName(event.getEvent()) + " code=" + event.getEvent()
          + " endpoint=" + event.getAddress());
  }

  private void observePublisher(String stage) {
    String status = health.get();
    String observation = field(status, "state") + "/receiver=" + field(status, "receiver");
    if (!observation.equals(lastObservedPublisher)) {
      lastObservedPublisher = observation;
      mark("publisher observation stage=" + stage + " " + observation);
    }
  }

  private String diagnostics(String reason) {
    drainMonitor();
    return "WELCOME diagnostic id=" + id + " endpoint=" + endpoint + " reason=" + reason
        + " trace=" + trace + " publisher=" + health.get();
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int start = json.indexOf(key);
    if (start < 0) return "unknown";
    start += key.length();
    int end = json.indexOf('"', start);
    return end < 0 ? "unknown" : json.substring(start, end);
  }

  private static String eventName(int event) {
    if (event == ZMQ.EVENT_CONNECTED) return "CONNECTED";
    if (event == ZMQ.EVENT_CONNECT_DELAYED) return "CONNECT_DELAYED";
    if (event == ZMQ.EVENT_CONNECT_RETRIED) return "CONNECT_RETRIED";
    if (event == ZMQ.EVENT_DISCONNECTED) return "DISCONNECTED";
    if (event == ZMQ.EVENT_HANDSHAKE_PROTOCOL) return "HANDSHAKE_PROTOCOL";
    return "OTHER";
  }

  private void mark(String event) {
    long now = System.nanoTime();
    long release = releaseNanos.get();
    trace.append((now - started) / 1_000_000).append("ms ")
        .append(release == 0 ? "" : "(barrier+" + (now - release) / 1_000_000 + "ms) ")
        .append(event).append(';');
  }

  @Override
  public void close() {
    poller.close();
    dealer.close();
    monitor.close();
  }
}
