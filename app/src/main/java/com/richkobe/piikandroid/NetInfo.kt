package com.richkobe.piikandroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Network facts Go cannot read itself on Android: DNS servers (no
 * /etc/resolv.conf) and interface addresses (netlink is blocked for apps).
 */
object NetInfo {
    fun dnsServers(context: Context): String {
        val cm = context.service<ConnectivityManager>()
        val ordered = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        val seen = LinkedHashSet<String>()
        for (network in ordered) {
            cm.getLinkProperties(network)?.dnsServers?.forEach { addr -> addr.hostAddress?.let { seen.add(it.substringBefore('%')) } }
        }
        return seen.joinToString(",")
    }

    /** "address/interface,..." for active IPv4 networks, Wi-Fi first. */
    fun lanAddresses(context: Context): String {
        val cm = context.service<ConnectivityManager>()
        data class Entry(val address: String, val iface: String, val rank: Int)
        val entries = ArrayList<Entry>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val props = cm.getLinkProperties(network) ?: continue
            val rank = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 2
                else -> 3 // mobile data: rarely reachable by others, listed last
            }
            for (la in props.linkAddresses) {
                val ip = la.address
                if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) {
                    entries.add(Entry(ip.hostAddress ?: continue, props.interfaceName ?: "android", rank))
                }
            }
        }
        // Viewers can't reach a phone over its mobile-data address, so only
        // offer it when there is nothing else (Piik then suggests Public invite).
        val local = entries.filter { it.rank < 3 }
        return local.ifEmpty { entries }.sortedBy { it.rank }.joinToString(",") { "${it.address}/${it.iface}" }
    }

    fun onWifi(context: Context): Boolean {
        val cm = context.service<ConnectivityManager>()
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
