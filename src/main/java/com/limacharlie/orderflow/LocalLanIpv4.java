package com.limacharlie.orderflow;

import java.net.*;
import java.util.*;
import java.util.regex.Pattern;

/** Local OS metadata only: no DNS, routing probes, packets, persistence or shell commands. */
final class LocalLanIpv4 {
    record Address(
            String adapter,
            String displayName,
            String ip,
            boolean up,
            boolean loopback,
            boolean virtual) {}

    private static final Pattern VIRTUAL =
            Pattern.compile(
                    "(?i)(wireguard|wintun|tailscale|zerotier|virtualbox|vmware|hyper-v|loopback|(^|\\s)(tun|tap|veth|docker|br-))");

    static List<Address> snapshot() throws SocketException {
        List<Address> result = new ArrayList<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return result;
        while (interfaces.hasMoreElements()) {
            var nic = interfaces.nextElement();
            var ips = nic.getInetAddresses();
            while (ips.hasMoreElements()) {
                var ip = ips.nextElement();
                if (ip instanceof Inet4Address)
                    result.add(
                            new Address(
                                    nic.getName(),
                                    nic.getDisplayName(),
                                    ip.getHostAddress(),
                                    nic.isUp(),
                                    nic.isLoopback(),
                                    nic.isVirtual()));
            }
        }
        return List.copyOf(result);
    }

    private static int[] octets(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return null;
        var parts = value.split("\\.");
        int[] result = new int[4];
        for (int i = 0; i < 4; i++) {
            result[i] = Integer.parseInt(parts[i]);
            if (result[i] > 255) return null;
        }
        return result;
    }

    static List<Address> candidates(List<Address> snapshot) {
        var sorted = new ArrayList<Address>();
        for (var a : snapshot) {
            var ip = octets(a.ip());
            if (!a.up() || a.loopback() || a.virtual() || ip == null) continue;
            if (VIRTUAL.matcher(
                            Objects.toString(a.adapter(), "")
                                    + " "
                                    + Objects.toString(a.displayName(), ""))
                    .find()) continue;
            if (ip[0] == 10
                    || ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31
                    || ip[0] == 192 && ip[1] == 168) sorted.add(a);
        }
        sorted.sort(
                Comparator.comparing((Address a) -> Objects.toString(a.adapter(), ""))
                        .thenComparing(Address::ip));
        var unique = new LinkedHashMap<String, Address>();
        for (var a : sorted) unique.putIfAbsent(a.ip(), a);
        return List.copyOf(unique.values());
    }

    static String suggested(List<Address> snapshot) {
        var choices = candidates(snapshot);
        return choices.size() == 1 ? choices.get(0).ip() : null;
    }

    static boolean validBind(String value, List<Address> snapshot) {
        if (value == null) return false;
        String ip = value.trim();
        if (octets(ip) == null) return false;
        if (ip.equals("0.0.0.0")) return true;
        return snapshot.stream().anyMatch(a -> a.up() && a.ip().equals(ip));
    }

    static String label(Address a) {
        return Objects.toString(a.displayName(), Objects.toString(a.adapter(), "Local adapter"))
                + " ("
                + Objects.toString(a.adapter(), "unknown")
                + ") — "
                + a.ip();
    }
}
