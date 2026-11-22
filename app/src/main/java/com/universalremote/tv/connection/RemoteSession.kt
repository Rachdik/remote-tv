package com.universalremote.tv.connection

import android.util.Log
import com.universalremote.tv.crypto.ClientIdentity
import com.universalremote.tv.protocol.Feature
import com.universalremote.tv.protocol.KeyDirection
import com.universalremote.tv.protocol.RemoteMessages
import com.universalremote.tv.protocol.TvDeviceInfo
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.protocol.readFrame
import com.universalremote.tv.protocol.writeFrame
import com.universalremote.tv.storage.PairedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * A live control connection to one Android TV.
 *
 * The channel is mutually authenticated TLS carrying varint-length-delimited protobuf
 * `RemoteMessage` frames (see docs/PROTOCOL.md). Key presses, text edits and voice audio all
 * travel over this same socket; the TV keeps it open by sending ping requests, which must be
 * answered or the TV drops the client.
 */
class RemoteSession(
    val device: PairedDevice,
    private val identity: ClientIdentity,
    private val onEvent: (RemoteMessages.Event) -> Unit,
    private val onClosed: (reason: String?) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val closedOnce = AtomicBoolean(false)
    private val configured = CompletableDeferred<TvDeviceInfo>()

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null

    @Volatile
    private var closed = false

    /** Features the TV reported, already intersected with what this client supports. */
    @Volatile
    var negotiatedFeatures: Int = 0
        private set

    val isConnected: Boolean get() = !closed

    /**
     * Opens the TLS channel and performs the `RemoteConfigure` exchange.
     *
     * @return the TV's model/vendor information.
     */
    suspend fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS): TvDeviceInfo = withContext(Dispatchers.IO) {
        val (sslSocket, usedPort) = openTls(device.host, device.remotePort)
        socket = sslSocket
        output = sslSocket.outputStream
        lastPort = usedPort
        startReader(sslSocket)
        sendRaw(RemoteMessages.configure())
        val info = try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) { configured.await() }
        } catch (e: Exception) {
            close("The TV did not answer the configuration request", notify = true)
            throw IOException("The TV did not answer the configuration request", e)
        }
        info
    }

    private var lastPort: Int = device.remotePort

    /**
     * Tries the configured port, then its counterpart.
     *
     * Devices in the wild answer on 6467, and a minority listen on 6466 for control and 6467 for
     * pairing, so both are attempted before reporting a failure.
     */
    private fun openTls(host: String, preferredPort: Int): Pair<SSLSocket, Int> {
        val context = ClientIdentity.sslContext(identity)
        val candidates = listOf(preferredPort, if (preferredPort == 6467) 6466 else 6467).distinct()
        var lastError: Exception? = null
        for (port in candidates) {
            var raw: Socket? = null
            try {
                raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                raw.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
                val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                ssl.useClientMode = true
                ssl.sslParameters = ssl.sslParameters.apply {
                    val wanted = listOf("TLSv1.3", "TLSv1.2")
                    val available = ssl.supportedProtocols.toSet()
                    protocols = wanted.filter { it in available }.ifEmpty { listOf("TLSv1.2") }.toTypedArray()
                }
                ssl.startHandshake()
                ssl.soTimeout = 0
                Log.i(TAG, "TLS established with $host:$port")
                return ssl to port
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "TLS connection to $host:$port failed: ${e.message}")
                try {
                    raw?.close()
                } catch (_: IOException) {
                }
            }
        }
        throw IOException(
            "Could not open a secure connection to $host (ports ${candidates.joinToString("/")}).",
            lastError,
        )
    }

    private fun startReader(sslSocket: SSLSocket) {
        readerJob = scope.launch {
            try {
                val input = sslSocket.inputStream
                while (!closed) {
                    val frame = readFrame(input) ?: break
                    handleFrame(frame)
                }
                close("The TV closed the connection", notify = true)
            } catch (e: Exception) {
                if (!closed) close(e.message ?: "The connection was lost", notify = true)
            }
        }
    }

    private fun handleFrame(frame: ByteArray) {
        val events = try {
            RemoteMessages.parse(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping an undecodable message from the TV", e)
            return
        }
        for (event in events) {
            when (event) {
                is RemoteMessages.Event.Ping -> sendRaw(RemoteMessages.pingResponse(event.value))
                is RemoteMessages.Event.ConfigureReceived -> {
                    val supported = event.features
                    val negotiated = supported and Feature.REQUESTED
                    negotiatedFeatures = negotiated
                    // Reply with the negotiated mask so the TV knows what we will use, then mark
                    // this client as the active remote.
                    sendRaw(RemoteMessages.configure(negotiated))
                    sendRaw(RemoteMessages.setActive(negotiated))
                    if (!configured.isCompleted) configured.complete(event.deviceInfo)
                }
                is RemoteMessages.Event.SetActive -> {
                    // The TV asks the active client to confirm; answer with what we support.
                    sendRaw(RemoteMessages.setActive(negotiatedFeatures or Feature.KEY))
                }
                else -> Unit
            }
            onEvent(event)
        }
    }

    fun sendKey(key: TvKey, direction: KeyDirection = KeyDirection.SHORT) {
        sendRaw(RemoteMessages.keyInject(key, direction))
    }

    fun sendText(text: String, imeCounter: Int, fieldCounter: Int) {
        if (text.isEmpty()) return
        sendRaw(RemoteMessages.imeBatchEdit(text, imeCounter, fieldCounter))
    }

    fun launchApp(appLink: String) {
        sendRaw(RemoteMessages.launchApp(appLink))
    }

    fun sendVoiceBegin(sessionId: Int) = sendRaw(RemoteMessages.voiceBegin(sessionId))

    fun sendVoicePayload(sessionId: Int, samples: ByteArray) =
        sendRaw(RemoteMessages.voicePayload(sessionId, samples))

    fun sendVoiceEnd(sessionId: Int) = sendRaw(RemoteMessages.voiceEnd(sessionId))

    /** Queues a frame; ordering is preserved by the write mutex. */
    fun sendRaw(payload: ByteArray) {
        if (closed) return
        scope.launch {
            writeMutex.withLock {
                try {
                    output?.let { writeFrame(it, payload) }
                } catch (e: IOException) {
                    close(e.message ?: "Could not write to the TV", notify = true)
                }
            }
        }
    }

    fun close(reason: String? = null, notify: Boolean = false) {
        if (!closedOnce.compareAndSet(false, true)) return
        closed = true
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
        if (notify) onClosed(reason)
        readerJob?.cancel()
        scope.cancel()
        if (!configured.isCompleted) configured.completeExceptionally(IOException(reason ?: "closed"))
    }

    companion object {
        private const val TAG = "RemoteSession"
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val HANDSHAKE_TIMEOUT_MS = 8000L
    }
}
