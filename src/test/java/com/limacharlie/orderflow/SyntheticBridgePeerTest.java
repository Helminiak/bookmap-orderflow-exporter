package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.zeromq.*;

class SyntheticBridgePeerTest {
  @Test
  void concurrentPublisherStartupAndFirstHelloRemainObservable() throws Exception {
    for (int repetition = 0; repetition < 3; repetition++) {
      for (int workers : List.of(4, 8, 16)) {
        Set<Integer> selectedPorts = new HashSet<>();
        int[][] ports = new int[workers][2];
        for (int worker = 0; worker < workers; worker++) {
          ports[worker][0] = unusedPort(selectedPorts);
          ports[worker][1] = unusedPort(selectedPorts);
        }
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var releaseNanos = new AtomicLong();
        var pool = Executors.newFixedThreadPool(workers);
        try {
          List<java.util.concurrent.Future<?>> runs = new ArrayList<>();
          for (int worker = 0; worker < workers; worker++) {
            int workerId = worker;
            int market = ports[worker][0];
            int health = ports[worker][1];
            int round = repetition;
            runs.add(
                pool.submit(
                    () -> {
                      String id = "stress-r" + round + "-" + workers + "-" + workerId;
                      var phase = new AtomicReference<>("publisher startup");
                      long healthReadyNanos = -1;
                      var publisher =
                          new LiveBridge("127.0.0.1", market, health, 1, "SYNTH", .25, () -> "{}");
                      try {
                        ExporterTest.awaitBridgeReady(health);
                        healthReadyNanos = System.nanoTime();
                        assertTrue(
                            publisher.offer(
                                new CanonicalEvent(1, 1, () -> "{\"type\":\"START\",\"seq\":1}")),
                            "START queue failed id=" + id);
                        try (var context = new ZContext();
                            var peer =
                                new DiagnosticPeer(
                                    context, market, id, releaseNanos, publisher::status)) {
                          ready.countDown();
                          phase.set("barrier wait");
                          assertTrue(
                              start.await(30, TimeUnit.SECONDS),
                              "start barrier timed out id=" + id);
                          phase.set("HELLO send");
                          peer.send("HELLO " + id + " 0");
                          phase.set("WELCOME receive");
                          assertTrue(
                              peer.receive("WELCOME").contains("WELCOME"),
                              "WELCOME mismatch id=" + id);
                          phase.set("first START receive");
                          assertTrue(
                              peer.receive("START").contains("\"seq\":1"),
                              "first START mismatch id=" + id);
                          phase.set("complete");
                        }
                        return "id="
                            + id
                            + " market="
                            + market
                            + " health="
                            + health
                            + " healthReadyNanos="
                            + healthReadyNanos;
                      } catch (Throwable failure) {
                        throw new AssertionError(
                            "WELCOME stress failed level="
                                + workers
                                + " id="
                                + id
                                + " market="
                                + market
                                + " health="
                                + health
                                + " healthReadyNanos="
                                + healthReadyNanos
                                + " barrierReleaseNanos="
                                + releaseNanos.get()
                                + " phase="
                                + phase.get()
                                + " publisher="
                                + publisher.status(),
                            failure);
                      } finally {
                        publisher.close();
                        ready.countDown();
                      }
                    }));
          }
          assertTrue(
              ready.await(45, TimeUnit.SECONDS),
              "workers did not reach barrier round=" + repetition + " level=" + workers);
          releaseNanos.set(System.nanoTime());
          start.countDown();
          for (var run : runs) run.get(45, TimeUnit.SECONDS);
        } finally {
          start.countDown();
          pool.shutdownNow();
          assertTrue(
              pool.awaitTermination(10, TimeUnit.SECONDS),
              "workers did not terminate round=" + repetition + " level=" + workers);
        }
      }
    }
  }

  private static int unusedPort(Set<Integer> selected) throws Exception {
    int port;
    do {
      port = ExporterTest.freePort();
    } while (!selected.add(port));
    return port;
  }

  /** Polls dealer replies and its monitor together, preserving one two-second receive budget. */
  private static final class DiagnosticPeer implements AutoCloseable {
    private static final long RECEIVE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final ZMQ.Socket dealer;
    private final ZMQ.Socket monitor;
    private final ZMQ.Poller poller;
    private final int dealerIndex;
    private final int monitorIndex;
    private final String id;
    private final String endpoint;
    private final AtomicLong releaseNanos;
    private final java.util.function.Supplier<String> publisherStatus;
    private final long started = System.nanoTime();
    private final StringBuilder trace = new StringBuilder();
    private String lastObservedPublisher = "";

    DiagnosticPeer(
        ZContext context,
        int port,
        String id,
        AtomicLong releaseNanos,
        java.util.function.Supplier<String> publisherStatus) {
      this.id = id;
      this.endpoint = "tcp://127.0.0.1:" + port;
      this.releaseNanos = releaseNanos;
      this.publisherStatus = publisherStatus;
      dealer = context.createSocket(SocketType.DEALER);
      dealer.setLinger(0);
      String monitorEndpoint = "inproc://welcome-diagnostic-" + java.util.UUID.randomUUID();
      assertTrue(dealer.monitor(monitorEndpoint, ZMQ.EVENT_ALL), "monitor setup failed id=" + id);
      monitor = context.createSocket(SocketType.PAIR);
      monitor.setLinger(0);
      assertTrue(monitor.connect(monitorEndpoint), "monitor connect failed id=" + id);
      poller = context.createPoller(2);
      dealerIndex = poller.register(dealer, ZMQ.Poller.POLLIN);
      monitorIndex = poller.register(monitor, ZMQ.Poller.POLLIN);
      boolean accepted = dealer.connect(endpoint);
      mark("connect call accepted=" + accepted + " endpoint=" + endpoint);
      assertTrue(accepted, diagnostics("connect rejected"));
      observePublisher("before HELLO");
    }

    void send(String command) {
      boolean accepted = dealer.send(command);
      mark("HELLO local send accepted=" + accepted + " command=" + command);
      observePublisher("after HELLO send");
      assertTrue(accepted, diagnostics("HELLO send rejected"));
    }

    String receive(String expected) {
      long deadline = System.nanoTime() + RECEIVE_BUDGET_NANOS;
      while (System.nanoTime() < deadline) {
        long remaining = deadline - System.nanoTime();
        long waitMillis = Math.max(1, Math.min(10, TimeUnit.NANOSECONDS.toMillis(remaining)));
        poller.poll(waitMillis);
        drainMonitor();
        observePublisher("while waiting for " + expected);
        if (poller.pollin(dealerIndex)) {
          String reply = dealer.recvStr(ZMQ.DONTWAIT);
          if (reply != null) {
            mark("dealer received " + expected + " frame");
            observePublisher("after receiving " + expected);
            return reply;
          }
        }
      }
      mark("dealer receive " + expected + " timed out");
      throw new AssertionError(diagnostics("missing " + expected));
    }

    private void drainMonitor() {
      if (!poller.pollin(monitorIndex)) return;
      ZMQ.Event event;
      while ((event = ZMQ.Event.recv(monitor, ZMQ.DONTWAIT)) != null) {
        mark(
            "monitor "
                + eventName(event.getEvent())
                + " code="
                + event.getEvent()
                + " endpoint="
                + event.getAddress());
      }
    }

    private void observePublisher(String stage) {
      String status = publisherStatus.get();
      String observation = field(status, "state") + "/receiver=" + field(status, "receiver");
      if (!observation.equals(lastObservedPublisher)) {
        lastObservedPublisher = observation;
        mark("publisher observation stage=" + stage + " " + observation);
      }
    }

    private String diagnostics(String reason) {
      drainMonitor();
      return "WELCOME diagnostic id="
          + id
          + " endpoint="
          + endpoint
          + " reason="
          + reason
          + " trace="
          + trace
          + " publisher="
          + publisherStatus.get();
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
      long release = releaseNanos.get();
      trace
          .append((System.nanoTime() - started) / 1_000_000)
          .append("ms ")
          .append(
              release == 0 ? "" : "(barrier+" + (System.nanoTime() - release) / 1_000_000 + "ms) ")
          .append(event)
          .append(';');
    }

    @Override
    public void close() {
      poller.close();
      dealer.close();
      monitor.close();
    }
  }

  @Test
  void locallyAcceptedHelloWithoutRouterConnectionDoesNotReachPublisher() throws Exception {
    try (var context = new ZContext()) {
      int market = ExporterTest.freePort(), health = ExporterTest.freePort();
      while (health == market) health = ExporterTest.freePort();
      var publisher = new LiveBridge("127.0.0.1", market, health, 1, "SYNTH", .25, () -> "{}");
      try {
        ExporterTest.awaitBridgeReady(health);
        try (var peer =
            new SyntheticBridgePeer(context, ExporterTest.freePort(), publisher::status)) {
          // DEALER accepts this into its local queue although no ROUTER owns this port.
          peer.send("HELLO absent-route 0");
          var failure = assertThrows(AssertionError.class, () -> peer.receive("WELCOME"));
          assertTrue(failure.getMessage().contains("send HELLO accepted=true"));
          assertTrue(failure.getMessage().contains("receive WELCOME present=false"));
          assertTrue(failure.getMessage().contains("\"state\":\"WAITING_RECEIVER\""));
          assertTrue(failure.getMessage().contains("\"receiver\":\"none\""));
          assertEquals(0, publisher.depth());
          assertFalse(publisher.invalid());
        }
      } finally {
        publisher.close();
      }
    }
  }

  @Test
  void withheldWelcomeFailsOnceWithConnectionSendReceiveAndHealthEvidence() throws Exception {
    try (var context = new ZContext()) {
      int port = ExporterTest.freePort();
      var router = context.createSocket(SocketType.ROUTER);
      router.setLinger(0);
      router.setReceiveTimeOut(2000);
      assertTrue(router.bind("tcp://127.0.0.1:" + port));
      var probes = new AtomicInteger();
      try (var peer =
          new SyntheticBridgePeer(
              context,
              port,
              () -> {
                probes.incrementAndGet();
                return "synthetic server intentionally withheld WELCOME";
              })) {
        peer.send("HELLO withheld 0");
        assertNotNull(router.recv());
        assertEquals("HELLO withheld 0", router.recvStr());
        long start = System.nanoTime();
        var failure = assertThrows(AssertionError.class, () -> peer.receive("WELCOME"));
        assertTrue(System.nanoTime() - start >= 1_800_000_000L, "original 2s budget retained");
        assertTrue(failure.getMessage().contains("connect accepted"));
        assertTrue(failure.getMessage().contains("send HELLO accepted=true"));
        assertTrue(failure.getMessage().contains("receive WELCOME present=false"));
        assertTrue(failure.getMessage().contains("monitor="));
        assertTrue(failure.getMessage().contains("intentionally withheld"));
        assertEquals(1, probes.get());
        assertNull(router.recv(ZMQ.DONTWAIT), "no retry HELLO is sent after timeout");
      }
    }
  }

  @Test
  void successfulReplyDoesNotProbeHealthOrRetry() throws Exception {
    try (var context = new ZContext()) {
      long started = System.nanoTime();
      int port = ExporterTest.freePort();
      var router = context.createSocket(SocketType.ROUTER);
      router.setLinger(0);
      router.setReceiveTimeOut(2000);
      assertTrue(router.bind("tcp://127.0.0.1:" + port));
      var probes = new AtomicInteger();
      try (var peer =
          new SyntheticBridgePeer(
              context,
              port,
              () -> {
                probes.incrementAndGet();
                return "unused";
              })) {
        peer.send("HELLO success 0");
        byte[] identity = router.recv();
        assertNotNull(identity, "ROUTER did not receive HELLO identity");
        String hello = router.recvStr();
        long helloObserved = System.nanoTime();
        assertEquals("HELLO success 0", hello);
        assertTrue(router.send(identity, ZMQ.SNDMORE), "ROUTER identity frame send failed");
        assertTrue(router.send("WELCOME"), "ROUTER WELCOME send failed");
        long welcomeEmitted = System.nanoTime();
        assertEquals("WELCOME", peer.receive("WELCOME"));
        long welcomeReceived = System.nanoTime();
        String stages =
            "ROUTER_HELLO_OBSERVED_MS="
                + (helloObserved - started) / 1_000_000
                + " ROUTER_WELCOME_EMITTED_MS="
                + (welcomeEmitted - started) / 1_000_000
                + " DEALER_WELCOME_RECEIVED_MS="
                + (welcomeReceived - started) / 1_000_000;
        assertTrue(helloObserved <= welcomeEmitted, stages);
        assertTrue(welcomeEmitted <= welcomeReceived, stages);
        assertEquals(
            0, probes.get(), "diagnostics are lazy and do not change passing timing: " + stages);
        assertNull(router.recv(ZMQ.DONTWAIT));
      }
    }
  }
}
