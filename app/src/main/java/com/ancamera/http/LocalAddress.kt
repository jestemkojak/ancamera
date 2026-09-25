package com.ancamera.http

import java.net.Inet6Address
import java.net.InetAddress

/**
 * True when [a] is a LAN or loopback address: loopback, site-local (10/8, 172.16/12, 192.168/16),
 * link-local, or an IPv6 unique local address (fc00::/7). The web server serves only these clients.
 */
fun isLocalAddress(a: InetAddress): Boolean {
    if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
    if (a is Inet6Address) {
        val first = a.address[0].toInt() and 0xFF
        return first == 0xFC || first == 0xFD
    }
    return false
}
