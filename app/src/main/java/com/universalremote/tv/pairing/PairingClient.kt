package com.universalremote.tv.pairing

import android.util.Log
import com.universalremote.tv.crypto.ClientIdentity
import com.universalremote.tv.protocol.PairingMessage
import com.universalremote.tv.protocol.readFrame
import com.universalremote.tv.protocol.writeFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/** Result of a completed pairing. */
data class PairingResult(val serverName: String, val serverCertificate: X509Certificate)

/**
 * The TV accepted the TCP (and usually TLS) connection but never finished the first half of the
 * handshake, so no code was shown. [stage] says exactly how far it got.
 */
class PairingStalledException(
    val stage: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * A fresh client certificate can only help when the TV ignored this client from the very
     * beginning. Once it acknowledged the request it has read our certificate, so retrying with a
     * new one would just repeat the same conversation.
     */
    val retryWithNewIdentity: Boolean
        get() = stage == STAGE_CONNECT || stage == STAGE_REQUEST

    companion object {
        const val STAGE_CONNECT = "connect"
        const val STAGE_REQUEST = "request"
        const val STAGE_OPTIONS = "options"
        const val STAGE_CONFIGURATION = "configuration"
    }
}

/**
 * Drives the Google TV pairing protocol ("Polo") against one TV.
 *
 * Lifecycle:
 * 1. [start] opens TLS, sends `PairingRequest`, negotiates a six-character hexadecimal code and
 *    returns once the TV is showing the code.
 * 2. [submitCode] verifies the code against the TV's certificate, sends the derived secret and
 *    waits for the TV to acknowledge it.
 *
 * Everything happens on the pairing port (6466 by default); the control connection uses the same
 * client certificate afterwards.
 */
class PairingClient(
    private val host: String,
    private val port: Int,
    private val identity: ClientIdentity,
    private val clientName: String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = CompletableDeferred<String>()
    private val finished = CompletableDeferred<Unit>()
    private val closedOnce = AtomicBoolean(false)

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null

    @Volatile
    private var closed = false

    private var serverCertificate: X509Certificate? = null
    private var serverName: String = ""
    private var activePort: Int = port
    private var lastTlsError: String? = null

    /** Where the handshake currently stands; reported in the failure message and in logcat. */
    @Volatile
    private var stage: String = PairingStalledException.STAGE_CONNECT

    @Volatile
    private var tlsSummary: String = ""

    /**
     * Connects and completes the first half of the handshake.
     *
     * @return the TV's advertised name.
     */
    suspend fun start(timeoutMs: Long = 12000): String = withContext(Dispatchers.IO) {
        connect()
        stage = PairingStalledException.STAGE_REQUEST
        send(PairingMessage.request(SERVICE_NAME, clientName), "PairingRequest")

        try {
            withTimeout(timeoutMs) { started.await() }
        } catch (e: Exception) {
            val stalledAt = stage
            val detail = stallDetail(stalledAt, e)
            Log.w(TAG, "Pairing stalled at '$stalledAt': $detail", e)
            close()
            throw PairingStalledException(
                stalledAt,
                "The TV did not start the pairing dialog. $detail",
                e,
            )
        }
    }

    /**
     * Verifies the on-screen code and finishes pairing.
     *
     * @throws InvalidPairingCodeException when the check digits do not match this TV.
     */
    suspend fun submitCode(code: String, timeoutMs: Long = 60000): PairingResult =
        withContext(Dispatchers.IO) {
            val certificate = serverCertificate
                ?: throw IllegalStateException("Pairing has not started")
            val problem = ClientIdentity.verifyCode(identity, certificate, code)
            if (problem != null) throw InvalidPairingCodeException(problem)

            val secret = ClientIdentity.pairingSecret(identity, certificate, code)
            send(PairingMessage.secret(secret), "PairingSecret")
            try {
                withTimeout(timeoutMs) { finished.await() }
            } catch (e: Exception) {
                throw IOException(
                    if (closed) "The TV closed the pairing dialog. Start pairing again."
                    else "The TV did not confirm the pairing code.",
                    e,
                )
            }
            val name = serverName.ifBlank { "Android TV" }
            close()
            PairingResult(name, certificate)
        }

    // --- transport --------------------------------------------------------------------------

    private fun connect() {
        stage = PairingStalledException.STAGE_CONNECT
        val context = ClientIdentity.sslContext(identity)
        val candidates = listOf(port, if (port == 6466) 6467 else 6466).distinct()

        for (candidate in candidates) {
            var raw: Socket? = null
            try {
                raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, candidate), 6000)
                raw.soTimeout = 8000
                val candidateSocket =
                    context.socketFactory.createSocket(raw, host, candidate, true) as SSLSocket
                candidateSocket.useClientMode = true
                candidateSocket.sslParameters = candidateSocket.sslParameters.apply {
                    val available = candidateSocket.supportedProtocols.toSet()
                    protocols = listOf("TLSv1.3", "TLSv1.2").filter { it in available }
                        .ifEmpty { listOf("TLSv1.2") }.toTypedArray()
                }
                candidateSocket.startHandshake()
                candidateSocket.soTimeout = 0

                activePort = candidate
                socket = candidateSocket
                output = candidateSocket.outputStream
                serverCertificate = candidateSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                tlsSummary = describeTls(candidateSocket)
                Log.i(TAG, "Pairing TLS ready on $host:$candidate — $tlsSummary")

                launchReader(candidateSocket)
                return
            } catch (e: Exception) {
                lastTlsError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Pairing TLS to $host:$candidate failed: $lastTlsError")
                try {
                    raw?.close()
                } catch (_: IOException) {
                }
            }
        }

        throw PairingStalledException(
            PairingStalledException.STAGE_CONNECT,
            stallDetail(PairingStalledException.STAGE_CONNECT, null),
        )
    }

    /**
     * Records the negotiated session and, importantly, whether the TV actually received this
     * phone's certificate. Without it the TV can never compute the pairing secret.
     */
    private fun describeTls(sslSocket: SSLSocket): String {
        val session = sslSocket.session
        val localChain = session.localCertificates
        val presented = !localChain.isNullOrEmpty()
        val peer = session.peerCertificates.firstOrNull() as? X509Certificate
        return buildString {
            append(session.protocol).append(' ').append(session.cipherSuite)
            append(", client certificate ")
            append(if (presented) "presented" else "NOT presented")
            peer?.let { append(", TV certificate ").append(fingerprint(it)) }
        }
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .take(4)
            .joinToString("") { "%02x".format(it) }

    /** Human readable reason for a stalled handshake, safe to show in the UI. */
    private fun stallDetail(stalledAt: String, cause: Throwable?): String {
        val where = when (stalledAt) {
            PairingStalledException.STAGE_CONNECT ->
                "Nothing answered on port $activePort" +
                    (lastTlsError?.let { " ($it)" } ?: "") + "."
            PairingStalledException.STAGE_REQUEST ->
                "It accepted the connection but never answered the pairing request, so no code appeared on the TV."
            PairingStalledException.STAGE_OPTIONS ->
                "It acknowledged the pairing request but never sent its pairing options back."
            PairingStalledException.STAGE_CONFIGURATION ->
                "It accepted the pairing options but never confirmed the configuration that makes it show a code."
            else -> "It stopped before the TV showed a code."
        }
        val dropped = cause?.message?.contains("closed", ignoreCase = true) == true
        return buildString {
            append(where)
            if (dropped) append(" The TV closed the pairing connection.")
            if (tlsSummary.isNotBlank()) append(" Connection: $tlsSummary.")
        }
    }

    private fun launchReader(sslSocket: SSLSocket) {
        scope.launch {
            try {
                val input = sslSocket.inputStream
                while (!closed) {
                    val frame = readFrame(input) ?: break
                    Log.d(TAG, "TV -> ${frame.size}B ${hexPreview(frame)}")
                    when (val event = PairingMessage.parse(frame)) {
                        is PairingMessage.Event.RequestAck -> {
                            serverName = event.serverName
                            Log.i(TAG, "Pairing request acknowledged by \"${event.serverName}\"")
                            stage = PairingStalledException.STAGE_OPTIONS
                            send(PairingMessage.options(), "PairingOptions")
                        }
                        PairingMessage.Event.Options -> {
                            Log.i(TAG, "Pairing options received")
                            stage = PairingStalledException.STAGE_CONFIGURATION
                            send(PairingMessage.configuration(), "PairingConfiguration")
                        }
                        PairingMessage.Event.ConfigurationAck -> {
                            Log.i(TAG, "Pairing configuration acknowledged; the TV should show a code now")
                            if (!started.isCompleted) started.complete(serverName)
                        }
                        PairingMessage.Event.SecretAck -> {
                            Log.i(TAG, "Pairing secret acknowledged")
                            if (!finished.isCompleted) finished.complete(Unit)
                        }
                        is PairingMessage.Event.Failure -> {
                            Log.w(TAG, "The TV rejected the pairing with status ${event.status}")
                            started.completeExceptionally(
                                IOException("The TV rejected the pairing (status ${event.status})."),
                            )
                            finished.completeExceptionally(
                                IOException("The TV rejected the pairing (status ${event.status})."),
                            )
                        }
                        PairingMessage.Event.Unknown ->
                            Log.w(TAG, "Unhandled pairing message: ${hexPreview(frame)}")
                    }
                }
                if (!closed) {
                    Log.i(TAG, "The TV closed the pairing connection at stage '$stage'")
                    if (!finished.isCompleted) {
                        finished.completeExceptionally(IOException("The TV closed the pairing connection."))
                    }
                    if (!started.isCompleted) {
                        started.completeExceptionally(IOException("The TV closed the pairing connection."))
                    }
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.w(TAG, "Pairing reader stopped: ${e.message}")
                    started.completeExceptionally(e)
                    finished.completeExceptionally(e)
                }
            }
        }
    }

    private fun send(payload: ByteArray, name: String) {
        try {
            Log.d(TAG, "TV <- $name ${payload.size}B ${hexPreview(payload)}")
            output?.let { writeFrame(it, payload) }
        } catch (e: IOException) {
            throw IOException("Could not write to the TV", e)
        }
    }

    fun close() {
        if (!closedOnce.compareAndSet(false, true)) return
        closed = true
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "PairingClient"
        const val SERVICE_NAME = "atvremote"

        private fun hexPreview(payload: ByteArray, limit: Int = 32): String =
            payload.take(limit).joinToString("") { "%02x".format(it) } +
                if (payload.size > limit) "…" else ""
    }
}

class InvalidPairingCodeException(message: String) : Exception(message)
