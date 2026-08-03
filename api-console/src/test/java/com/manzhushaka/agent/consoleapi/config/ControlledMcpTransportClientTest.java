package com.manzhushaka.agent.consoleapi.config;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledMcpTransportClientTest {
    @Test
    void rejectsLocalAndReservedDnsTargets() throws Exception {
        assertThrows(Exception.class, () -> ControlledMcpTransportClient.resolvePublicAddresses("localhost"));
        assertFalse(ControlledMcpTransportClient.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(ControlledMcpTransportClient.isPublicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(ControlledMcpTransportClient.isPublicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(ControlledMcpTransportClient.isPublicAddress(InetAddress.getByName("198.18.0.1")));
        assertFalse(ControlledMcpTransportClient.isPublicAddress(InetAddress.getByName("fc00::1")));
        assertTrue(ControlledMcpTransportClient.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(ControlledMcpTransportClient.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
}
