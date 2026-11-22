package com.universalremote.tv.discovery

import com.universalremote.tv.storage.PairedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Result of checking a host for the Android TV remote service. */
data class ProbeResult(
    val host: String,
    val reachable: Boolean,
    val remotePort: Int,
    val pairingPort: Int,
    val detail: String,
) {
    val canPair: Boolean get() = reachable
}

/**
 * Checks whether a host runs the Android TV remote service.
 *
 * Both service ports are probed by opening and immediately closing a TCP connection: no data is
 * sent before pairing, so a reachable port means the TV is a candidate. Ports 6467 and 6466 are
 * tried in both roles because firmware varies in which one is pairing and which one is control.
 */
object TvProbe {

    private const val TIMEOUT_MS = 2500

    suspend fun probe(host: String, preferredRemotePort: Int = PairedDevice.DEFAULT_REMOTE_PORT): ProbeResult =
        withContext(Dispatchers.IO) {
            val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
            val remoteFirst = listOf(preferredRemotePort, if (preferredRemotePort == 6467) 6466 else 6467)
            val open = remoteFirst.filter { isOpen(cleanHost, it) }

            when {
                open.isEmpty() -> ProbeResult(
                    host = cleanHost,
                    reachable = false,
                    remotePort = preferredRemotePort,
                    pairingPort = if (preferredRemotePort == 6467) 6466 else 6467,
                    detail = "No Android TV remote service answered on $cleanHost. Check the IP address and that the TV is on.",
                )

                open.size == 1 -> {
                    val port = open.first()
                    // A single open port is enough: connect() falls back to the other one.
                    ProbeResult(
                        host = cleanHost,
                        reachable = true,
                        remotePort = port,
                        pairingPort = if (port == 6467) 6466 else 6467,
                        detail = "Found the remote service on port $port.",
                    )
                }

                else -> ProbeResult(
                    host = cleanHost,
                    reachable = true,
                    remotePort = 6467,
                    pairingPort = 6466,
                    detail = "Both remote service ports are open.",
                )
            }
        }

    private fun isOpen(host: String, port: Int): Boolean {
        if (host.isBlank()) return false
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
