package com.universalremote.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.universalremote.tv.storage.PairedDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A TV found on the local network. */
data class DiscoveredTv(
    val name: String,
    val host: String,
    val model: String,
    val port: Int,
) {
    val id: String get() = host
}

/**
 * Finds Android TV devices advertising the remote control service over mDNS/DNS-SD.
 *
 * Modern Android TV and Google TV devices publish `_androidtvremote2._tcp.` with `fn` (friendly
 * name) and `md` (model) TXT records. The service port is the control port; pairing is assumed to
 * be one below it, and [TvProbe] confirms both before a connection is attempted.
 */
class MdnsDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _devices = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val devices: MutableStateFlow<List<DiscoveredTv>> = _devices

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val seen = ConcurrentHashMap<String, DiscoveredTv>()
    private val running = AtomicBoolean(false)
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var resolveInFlight = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val manager = nsdManager
        if (manager == null) {
            _errors.tryEmit("This device does not provide network service discovery.")
            running.set(false)
            return
        }
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "mDNS discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!running.get()) return
                if (serviceInfo.serviceType !in SERVICE_TYPES) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                seen.remove(host)
                _devices.value = seen.values.sortedBy { it.name }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to start ($errorCode)")
                _errors.tryEmit("Network discovery could not start (error $errorCode).")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to stop ($errorCode)")
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE_V2, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start discovery", e)
            _errors.tryEmit("Network discovery could not start: ${e.message}")
            running.set(false)
            releaseMulticastLock()
        }
    }

    /**
     * Resolves one service to an address.
     *
     * The platform resolver handles a single request at a time, so resolutions are serialised
     * through [resolveInFlight] and a short retry instead of being dropped.
     */
    private fun resolve(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        if (resolveInFlight) {
            // Try again shortly; discovery events keep coming while the resolver is busy.
            scheduleRetry(serviceInfo)
            return
        }
        resolveInFlight = true
        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolveInFlight = false
                    Log.w(TAG, "Resolve failed for ${info.serviceName} ($errorCode)")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolveInFlight = false
                    publish(info)
                }
            })
        } catch (e: Exception) {
            resolveInFlight = false
            Log.w(TAG, "Resolve threw", e)
        }
    }

    private fun scheduleRetry(serviceInfo: NsdServiceInfo) {
        // Best-effort retry without holding a thread: the next discovery event usually retries.
        val target = System.currentTimeMillis() + 1200
        Thread {
            while (System.currentTimeMillis() < target) Thread.sleep(200)
            if (running.get() && !resolveInFlight) resolve(serviceInfo)
        }.apply { isDaemon = true }.start()
    }

    private fun publish(info: NsdServiceInfo) {
        val host = info.host?.hostAddress
        if (host.isNullOrBlank()) return
        val cleanHost = host.substringBefore('%')
        val attributes = info.attributes ?: emptyMap()
        val friendly = attributes["fn"]?.let { String(it, Charsets.UTF_8) }
            ?: info.serviceName.substringBefore(".")
        val model = attributes["md"]?.let { String(it, Charsets.UTF_8) } ?: ""
        val port = if (info.port > 0) info.port else PairedDevice.DEFAULT_REMOTE_PORT
        val device = DiscoveredTv(
            name = friendly.trim(),
            host = cleanHost,
            model = model.trim(),
            port = port,
        )
        seen[cleanHost] = device
        _devices.value = seen.values.sortedBy { it.name.lowercase() }
        Log.i(TAG, "Found ${device.name} at $cleanHost:$port")
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            releaseMulticastLock()
            return
        }
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to stop discovery", e)
            }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    fun clear() {
        seen.clear()
        _devices.value = emptyList()
    }

    private fun acquireMulticastLock() {
        try {
            val lock = wifiManager?.createMulticastLock("universal-tv-remote-discovery")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "Multicast lock not acquired", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Multicast lock release failed", e)
        }
        multicastLock = null
    }

    companion object {
        private const val TAG = "MdnsDiscovery"
        const val SERVICE_TYPE_V2 = "_androidtvremote2._tcp."
        val SERVICE_TYPES = setOf(SERVICE_TYPE_V2)
    }
}

/**
 * SSDP based fallback discovery.
 *
 * Some Android TV boxes do not advertise the remote service over mDNS (multicast filtering on
 * the access point, or an older firmware). Almost all of them answer SSDP for DIAL, so the
 * addresses collected here are probed for the remote service ports by [TvProbe].
 */
class SsdpDiscovery {

    suspend fun search(timeoutMs: Long = 3000): List<String> = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        val found = linkedSetOf<String>()
        val targets = listOf(
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "upnp:rootdevice",
        ).distinct()
        java.net.MulticastSocket().use { socket ->
            socket.soTimeout = 600
            val group = java.net.InetAddress.getByName("239.255.255.250")
            val request = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: ${targets.first()}\r\n")
                append("\r\n")
            }.toByteArray()

            repeat(2) {
                socket.send(java.net.DatagramPacket(request, request.size, group, 1900))
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val address = packet.address?.hostAddress ?: continue
                    val body = String(packet.data, 0, packet.length)
                    if (body.contains("200 OK", ignoreCase = true)) {
                        found += address.substringBefore('%')
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // keep waiting until the deadline
                } catch (e: Exception) {
                    break
                }
            }
        }
        found.toList()
    }

    companion object {
        const val TAG = "SsdpDiscovery"
        val SSDP_SUPPORTED_ABOVE = Build.VERSION_CODES.N
    }
}
