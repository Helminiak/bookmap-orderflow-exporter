package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.zeromq.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

class SyntheticBridgePeerTest {
    @Test
    void concurrentPublisherStartupAndFirstHelloRemainObservable() throws Exception {
        int workers = 8;
        var pool = Executors.newFixedThreadPool(workers);
        try {
            List<java.util.concurrent.Future<?>> runs = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                runs.add(
                        pool.submit(
                                () -> {
                                    for (int iteration = 0; iteration < 2; iteration++) {
                                        int market = ExporterTest.freePort();
                                        int health = ExporterTest.freePort();
                                        while (health == market) health = ExporterTest.freePort();
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
                                            assertTrue(
                                                    publisher.offer(
                                                            new CanonicalEvent(
                                                                    1,
                                                                    1,
                                                                    () ->
                                                                            "{\"type\":\"START\",\"seq\":1}")));
                                            try (var context = new ZContext();
                                                    var peer =
                                                            new SyntheticBridgePeer(
                                                                    context,
                                                                    market,
                                                                    publisher::status)) {
                                                peer.send(
                                                        "HELLO stress-"
                                                                + workerId
                                                                + "-"
                                                                + iteration
                                                                + " 0");
                                                assertTrue(
                                                        peer.receive("WELCOME")
                                                                .contains("WELCOME"));
                                                assertTrue(
                                                        peer.receive("START").contains("\"seq\":1"),
                                                        publisher.status());
                                            }
                                        } finally {
                                            publisher.close();
                                        }
                                    }
                                    return null;
                                }));
            }
            for (var run : runs) run.get();
        } finally {
            pool.shutdownNow();
        }
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
