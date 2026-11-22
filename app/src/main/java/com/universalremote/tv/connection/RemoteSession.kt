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
import kotlinx.coroutines.delay
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
 *
 * Firmware differences are handled by trying both service ports: a TV that accepts the TLS
 * handshake but never sends `RemoteConfigure` is indistinguishable from one whose control
 * service lives on the other port, so the handshake is retried there before giving up.
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

    private var configured = CompletableDeferred<TvDeviceInfo>()

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null

    @Volatile
    private var closed = false

    /** Features the TV reported, already intersected with what this client supports. */
    @Volatile
    var negotiatedFeatures: Int = 0
        private set

    /** Port the control channel ended up on; the pairing port is stored alongside it. */
    var activePort: Int = device.remotePort
        private set

    val isConnected: Boolean get() = !closed

    /**
     * Opens the TLS channel and performs the `RemoteConfigure` exchange.
     *
     * @return the TV's model/vendor information.
     */
    suspend fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS): TvDeviceInfo = withContext(Dispatchers.IO) {
        val ports = listOf(device.remotePort, alternatePort(device.remotePort)).distinct()
        var lastError: Exception? = null

        for ((index, port) in ports.withIndex()) {
            try {
                val ssl = openTls(device.host, port, timeoutMs)
                socket = ssl
                output = ssl.outputStream
                activePort = port
                configured = CompletableDeferred()
                startReader(ssl)

                val info = try {
                    sendRaw(RemoteMessages.configure())
                    withTimeout(HANDSHAKE_TIMEOUT_MS) { configured.await() }
                } catch (e: Exception) {
                    // Silence here means either an unpaired certificate or the control service
                    // living on the other port, so drop this socket and try again.
                    abandon()
                    throw e
                }

                Log.i(TAG, "Control channel ready on ${device.host}:$port")
                return@withContext info
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Control handshake on ${device.host}:$port failed: ${e.message}")
                if (index < ports.lastIndex) delay(REHANDSHAKE_DELAY_MS)
            }
        }

        close(null)
        throw IOException(
            "The TV did not answer the configuration request on ${device.host} " +
                "(ports ${ports.joinToString("/")}).",
            lastError,
        )
    }

    private fun alternatePort(port: Int): Int = if (port == 6467) 6466 else 6467

    private fun openTls(host: String, port: Int, timeoutMs: Int): SSLSocket {
        val context = ClientIdentity.sslContext(identity)
        var raw: Socket? = null
        return try {
            raw = Socket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), timeoutMs)
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
            ssl
        } catch (e: Exception) {
            try {
                raw?.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }

    /** Drops the current socket without ending the session, so the next attempt can reuse it. */
    private fun abandon() {
        readerJob?.cancel()
        readerJob = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
        if (!configured.isCompleted) configured.cancel()
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
            if (event !is RemoteMessages.Event.Ping) {
                Log.i(TAG, "TV -> ${event::class.simpleName}")
            }
            when (event) {
                is RemoteMessages.Event.Ping -> sendRaw(RemoteMessages.pingResponse(event.value))
                is RemoteMessages.Event.ConfigureReceived -> {
                    // The TV announces its feature mask; answer with the subset we will use,
                    // exactly as the reference client does.
                    negotiatedFeatures = event.features and Feature.REQUESTED
                    sendRaw(RemoteMessages.configure(negotiatedFeatures))
                    if (!configured.isCompleted) configured.complete(event.deviceInfo)
                }
                is RemoteMessages.Event.SetActive -> {
                    // Only reply when the TV asks this client to become the active remote.
                    sendRaw(RemoteMessages.setActive(negotiatedFeatures))
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
        private const val HANDSHAKE_TIMEOUT_MS = 12000L
        private const val REHANDSHAKE_DELAY_MS = 500L
    }
}
