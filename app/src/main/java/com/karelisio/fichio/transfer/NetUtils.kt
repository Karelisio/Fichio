package com.karelisio.fichio.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Local-network address helpers. [isPrivateAddress] is pure JVM (unit-tested). */
object NetUtils {
    private val literal = Regex("^[0-9a-fA-F:.%]+$")

    /**
     * True for addresses a LAN client may have: RFC 1918 (10/8, 172.16/12, 192.168/16),
     * link-local (169.254/16, fe80::/10), IPv6 unique-local (fc00::/7) and loopback.
     * Anything else (public, CGNAT, multicast, hostnames) is rejected.
     */
    fun isPrivateAddress(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val a = address.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (!literal.matches(a) || (!a.contains(':') && a.count { it == '.' } != 3)) return false
        val inet = runCatching { InetAddress.getByName(a) }.getOrNull() ?: return false
        return isPrivate(inet)
    }

    fun isPrivate(inet: InetAddress): Boolean = when (inet) {
        is Inet4Address -> isPrivateV4(inet.address)
        is Inet6Address -> {
            val b = inet.address
            val mappedV4 = b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } &&
                b[10].toInt() and 0xff == 0xff && b[11].toInt() and 0xff == 0xff
            when {
                mappedV4 -> isPrivateV4(b.copyOfRange(12, 16))
                inet.isLoopbackAddress || inet.isLinkLocalAddress -> true
                else -> (b[0].toInt() and 0xfe) == 0xfc // fc00::/7
            }
        }
        else -> false
    }

    private fun isPrivateV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xff
        val o1 = b[1].toInt() and 0xff
        return when {
            o0 == 10 -> true
            o0 == 172 && o1 in 16..31 -> true
            o0 == 192 && o1 == 168 -> true
            o0 == 169 && o1 == 254 -> true
            o0 == 127 -> true
            else -> false
        }
    }

    /**
     * IPv4 address of the Wi-Fi interface (or of the hotspot this phone provides), or null
     * when no local network is available. Never returns a mobile-data address.
     */
    fun findWifiAddress(context: Context): Inet4Address? {
        val cm = context.getSystemService<ConnectivityManager>()
        // 1. Connected Wi-Fi network.
        runCatching {
            @Suppress("DEPRECATION")
            cm?.allNetworks?.forEach { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@forEach
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) return@forEach
                cm.getLinkProperties(n)?.linkAddresses?.forEach { la ->
                    val ad = la.address
                    if (ad is Inet4Address && isPrivate(ad) && !ad.isLoopbackAddress) return ad
                }
            }
        }
        // 2. Hotspot / Wi-Fi Direct interfaces, not represented as a ConnectivityManager network.
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && WIFI_IFACES.any { p -> it.name.startsWith(p) } }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { isPrivate(it) && !it.isLoopbackAddress }
        }.getOrNull()
    }

    /** Whether [address] is still assigned to an interface of this device. */
    fun isStillAssigned(address: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp }
            .any { i -> i.inetAddresses.toList().any { it.hostAddress == address } }
    }.getOrDefault(false)

    private val WIFI_IFACES = listOf("wlan", "ap", "swlan", "softap", "wigig", "p2p", "eth")
}
