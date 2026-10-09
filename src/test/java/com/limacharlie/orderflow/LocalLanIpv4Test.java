package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class LocalLanIpv4Test {
    @Test
    void legitimateAdapterNameContainingVirtualLanIsNotDiscarded() {
        var a =
                new LocalLanIpv4.Address(
                        "Ethernet", "Virtual LAN - Production", "10.0.2.15", true, false, false);
        assertEquals(a.ip(), LocalLanIpv4.suggested(List.of(a)));
    }

    static LocalLanIpv4.Address address(String name, String ip) {
        return new LocalLanIpv4.Address(name, name, ip, true, false, false);
    }

    @Test
    void oneLanAddressSuggestedButMultipleAdaptersOrIpsAreAmbiguous() {
        var a = address("Ethernet", "192.168.10.20");
        assertEquals(a.ip(), LocalLanIpv4.suggested(List.of(a)));
        assertNull(LocalLanIpv4.suggested(List.of(a, address("Wi-Fi", "192.168.10.21"))));
        assertNull(LocalLanIpv4.suggested(List.of(a, address("Ethernet", "10.2.3.4"))));
        assertNull(LocalLanIpv4.suggested(List.of()));
    }

    @Test
    void autoExcludesVirtualVpnDownLoopbackPublicIpv6AndLinkLocal() {
        assertTrue(
                LocalLanIpv4.candidates(
                                List.of(
                                        address("WireGuard", "10.0.0.1"),
                                        address("TAP-Windows", "10.0.0.2"),
                                        address("VirtualBox", "192.168.1.1"),
                                        address("Ethernet", "169.254.2.3"),
                                        address("Ethernet", "8.8.8.8"),
                                        address("Ethernet", "::1"),
                                        address("Ethernet", "127.0.0.1"),
                                        new LocalLanIpv4.Address(
                                                "Ethernet",
                                                "Ethernet",
                                                "10.1.2.3",
                                                false,
                                                false,
                                                false),
                                        new LocalLanIpv4.Address(
                                                "Eth", "Eth", "10.1.2.4", true, true, false),
                                        new LocalLanIpv4.Address(
                                                "Eth", "Eth", "10.1.2.5", true, false, true)))
                        .isEmpty());
    }

    @Test
    void privateRangeBoundariesAndStableDeduplication() {
        assertEquals(
                List.of("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.2.3"),
                LocalLanIpv4.candidates(
                                List.of(
                                        address("d", "192.168.2.3"),
                                        address("c", "172.31.255.254"),
                                        address("b", "172.16.0.1"),
                                        address("a", "10.1.2.3"),
                                        address("e", "10.1.2.3"),
                                        address("f", "172.15.0.1"),
                                        address("g", "172.32.0.1"),
                                        address("h", "192.167.2.3")))
                        .stream()
                        .map(LocalLanIpv4.Address::ip)
                        .toList());
    }

    @Test
    void explicitManualWildcardLocalPublicAndVpnArePreserved() {
        var local =
                List.of(
                        address("WireGuard", "10.0.0.1"),
                        address("Ethernet", "8.8.8.8"),
                        address("Loopback", "127.0.0.1"));
        assertTrue(LocalLanIpv4.validBind("0.0.0.0", List.of()));
        for (String value : List.of("10.0.0.1", "8.8.8.8", "127.0.0.1"))
            assertTrue(LocalLanIpv4.validBind(value, local));
        assertFalse(
                LocalLanIpv4.validBind("192.168.10.20", local),
                "receiver/nonlocal address rejected");
        assertFalse(
                LocalLanIpv4.validBind("10.0.0.1", List.of(address("Eth", "10.0.0.2"))),
                "renumbered address rejected");
    }

    @Test
    void invalidInputNeverNeedsDnsResolution() {
        for (String value :
                List.of(
                        "",
                        "localhost",
                        "mini-pc.local",
                        "::1",
                        "999.1.1.1",
                        "-1.2.3.4",
                        "1.2.3",
                        "1.2.3.4.5")) assertFalse(LocalLanIpv4.validBind(value, List.of()));
        assertFalse(LocalLanIpv4.validBind(null, List.of()));
    }
}
