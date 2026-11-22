package com.universalremote.tv.discovery

import com.universalremote.tv.protocol.Ports
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
 * Both services have fixed ports: [Ports.CONTROL] for the remote channel and [Ports.PAIRING] for
 * pairing. One open TCP port is enough to make the host a candidate, since nothing can be sent
 * before pairing, but the roles never change: pairing on the control port would complete TLS and
 * then hang, because the control service ignores pairing frames.
 */
object TvProbe {

    private const val TIMEOUT_MS = 2500

    suspend fun probe(host: String): ProbeResult = withContext(Dispatchers.IO) {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        val controlOpen = isOpen(cleanHost, Ports.CONTROL)
        val pairingOpen = isOpen(cleanHost, Ports.PAIRING)

        when {
            controlOpen && pairingOpen -> ProbeResult(
                host = cleanHost,
                reachable = true,
                remotePort = Ports.CONTROL,
                pairingPort = Ports.PAIRING,
                detail = "Found the remote service on ports ${Ports.CONTROL} and ${Ports.PAIRING}.",
            )

            controlOpen || pairingOpen -> {
                val port = if (controlOpen) Ports.CONTROL else Ports.PAIRING
                ProbeResult(
                    host = cleanHost,
                    reachable = true,
                    remotePort = Ports.CONTROL,
                    pairingPort = Ports.PAIRING,
                    detail = "Found the remote service on port $port.",
                )
            }

            else -> ProbeResult(
                host = cleanHost,
                reachable = false,
                remotePort = Ports.CONTROL,
                pairingPort = Ports.PAIRING,
                detail = "No Android TV remote service answered on $cleanHost. Check the IP address and that the TV is on.",
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
