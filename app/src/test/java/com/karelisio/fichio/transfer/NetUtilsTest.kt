package com.karelisio.fichio.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetUtilsTest {
    @Test fun privateIpv4() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.42", "169.254.10.3", "127.0.0.1")
            .forEach { assertTrue(it, NetUtils.isPrivateAddress(it)) }
    }

    @Test fun publicIpv4() {
        listOf("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.64.0.1", "11.0.0.1", "0.0.0.0", "224.0.0.1")
            .forEach { assertFalse(it, NetUtils.isPrivateAddress(it)) }
    }

    @Test fun ipv6() {
        listOf("fe80::1", "fe80::1%wlan0", "fd12:3456::1", "fc00::1", "::1", "[fe80::abcd]", "::ffff:192.168.1.1")
            .forEach { assertTrue(it, NetUtils.isPrivateAddress(it)) }
        listOf("2001:db8::1", "2a00:1450:4007::200e", "::ffff:8.8.8.8", "ff02::1")
            .forEach { assertFalse(it, NetUtils.isPrivateAddress(it)) }
    }

    @Test fun garbageAndHostnamesAreRejected() {
        listOf(null, "", " ", "localhost", "example.com", "192.168.1", "192.168.1.1.1", "999.1.1.1", "192.168.1.1; rm")
            .forEach { assertFalse("$it", NetUtils.isPrivateAddress(it)) }
    }
}
