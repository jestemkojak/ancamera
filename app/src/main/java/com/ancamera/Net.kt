package com.ancamera

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

object Net {
    /** The phone's LAN IPv4 address. Wi-Fi (wlan*) comes first. Null when there is no network. */
    fun lanIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: SocketException) {
        null
    }
}
