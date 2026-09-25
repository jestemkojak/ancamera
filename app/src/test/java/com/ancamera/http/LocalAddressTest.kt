package com.ancamera.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class LocalAddressTest {
    private fun local(literal: String) = isLocalAddress(InetAddress.getByName(literal))

    @Test fun lanAddressesAreLocal() {
        for (a in listOf("127.0.0.1", "192.168.1.2", "10.0.0.1", "172.16.0.1", "169.254.1.1", "::1", "fe80::1", "fd00::1", "fc00::1")) {
            assertEquals(a, true, local(a))
        }
    }

    @Test fun publicAddressesAreNotLocal() {
        for (a in listOf("8.8.8.8", "2001:db8::1", "172.32.0.1", "1.1.1.1")) {
            assertEquals(a, false, local(a))
        }
    }
}
