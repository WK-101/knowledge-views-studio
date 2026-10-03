package com.wkhan.hexis.web.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the phone's LAN IPv4 so the control screen can show a reachable URL. No permission needed. */
object LanAddress {
    fun ipv4(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
}
