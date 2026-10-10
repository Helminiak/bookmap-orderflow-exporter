package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.zeromq.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class SyntheticBridgePeerTest {
    @Test
    void concurrentPublisherStartupAndFirstHelloRemainObservable() throws Exception {
        for (int workers : List.of(4, 8, 16)) {
            Set<Integer> selectedPorts = new HashSet<>();
            int[][] ports = new int[workers][2];
            for (int worker = 0; worker < workers; worker++) {
                ports[worker][0] = unusedPort(selectedPorts);
                ports[worker][1] = unusedPort(selectedPorts);
            }
            var ready = new CountDownLatch(workers);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(workers);
            try {
                List<java.util.concurrent.Future<?>> runs = new ArrayList<>();
                for (int worker = 0; worker < workers; worker++) {
                    int workerId = worker;
                    int market = ports[worker][0];
                    int health = ports[worker][1];
                    runs.add(
                            pool.submit(
                                    () -> {
                                        String id = "stress-" + workers + "-" + workerId;
                                        var phase = new AtomicReference<>("publisher startup");
                                        var publisher =
                                                new LiveBridge(
                                                        "127.0.0.1",
                                                        market,
                                                        health,
                                                        1,
                                                        "SYNTH",
                                                        .25,
                                                        () -> "{}");
                                        try {
                                            ExporterTest.awaitBridgeReady(health);
                                            long healthReadyNanos = System.nanoTime();
                                            assertTrue(
                                                    publisher.offer(
                                                            new CanonicalEvent(
                                                                    1,
                                                                    1,
                                                                    () ->
                                                                            "{\"type\":\"START\",\"seq\":1}")),
                                                    "START queue failed id=" + id);
                                            try (var context = new ZContext();
                                                    var peer =
                                                            new SyntheticBridgePeer(
                                                                    context,
                                                                    market,
                                                                    publisher::status)) {
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
                                            return "id=" + id + " market=" + market + " health=" + health
                                                    + " healthReadyNanos=" + healthReadyNanos;
                                        } catch (Throwable failure) {
                                            throw new AssertionError(
                                                    "WELCOME stress failed level=" + workers
                                                            + " id=" + id
                                                            + " market=" + market
                                                            + " health=" + health
                                                            + " phase=" + phase.get()
                                                            + " publisher=" + publisher.status(),
                                                    failure);
                                        } finally {
                                            publisher.close();
                                            ready.countDown();
                                        }
                                    }));
                }
                assertTrue(ready.await(45, TimeUnit.SECONDS), "workers did not reach barrier level=" + workers);
                start.countDown();
                for (var run : runs) run.get(45, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not terminate level=" + workers);
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

    @Test
    void locallyAcceptedHelloWithoutRouterConnectionDoesNotReachPublisher() throws Exception {
        try (var context = new ZContext()) {
            int market = ExporterTest.freePort(), health = ExporterTest.freePort();
            while (health == market) health = ExporterTest.freePort();
            var publisher =
                    new LiveBridge("127.0.0.1", market, health, 1, "SYNTH", .25, () -> "{}");
            try {
                ExporterTest.awaitBridgeReady(health);
                try (var peer =
                        new SyntheticBridgePeer(context, ExporterTest.freePort(), publisher::status)) {
                    // DEALER accepts this into its local queue although no ROUTER owns this port.
                    peer.send("HELLO absent-route 0");
                    var failure =
                            assertThrows(AssertionError.class, () -> peer.receive("WELCOME"));
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
                assertTrue(
                        System.nanoTime() - start >= 1_800_000_000L, "original 2s budget retained");
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
                assertNotNull(identity);
                assertEquals("HELLO success 0", router.recvStr());
                assertTrue(router.send(identity, ZMQ.SNDMORE));
                assertTrue(router.send("WELCOME"));
                assertEquals("WELCOME", peer.receive("WELCOME"));
                assertEquals(
                        0, probes.get(), "diagnostics are lazy and do not change passing timing");
                assertNull(router.recv(ZMQ.DONTWAIT));
            }
        }
    }
}
