package com.limacharlie.orderflow;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/** Read-only health probe for the Windows launcher. Never registers a market receiver. */
public final class BridgeHealthQuery {
    static String query(String host, int port, int timeoutMs) {
        if (port < 1 || port > 65535 || timeoutMs < 1)
            throw new IllegalArgumentException("Invalid port/timeout");
        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REQ);
            socket.setLinger(0);
            socket.setSendTimeOut(timeoutMs);
            socket.setReceiveTimeOut(timeoutMs);
            socket.connect("tcp://" + host + ":" + port);
            if (!socket.send("HEALTH")) return null;
            return socket.recvStr();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: BridgeHealthQuery HOST HEALTH_PORT SECONDS");
            System.exit(2);
        }
        int port = Integer.parseInt(args[1]), seconds = Integer.parseInt(args[2]);
        if (seconds < 1 || seconds > 3600)
            throw new IllegalArgumentException("Seconds must be 1..3600");
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            String health = query(args[0], port, 1000);
            System.out.println(
                    health == null
                            ? "{\"query_error\":\"Health channel did not reply. Check bridge"
                                  + " enable, bind address and health port.\"}"
                            : health);
            System.out.flush();
            Thread.sleep(500);
        }
    }
}
