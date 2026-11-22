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
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/** Result of a completed pairing. */
data class PairingResult(val serverName: String, val serverCertificate: X509Certificate)

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

    /**
     * Connects and completes the first half of the handshake.
     *
     * @return the TV's advertised name.
     */
    suspend fun start(timeoutMs: Long = 20000): String = withContext(Dispatchers.IO) {
        val context = ClientIdentity.sslContext(identity)
        val candidates = listOf(port, if (port == 6466) 6467 else 6466).distinct()
        var lastError: Exception? = null
        var ssl: SSLSocket? = null

        for (candidate in candidates) {
            var raw: Socket? = null
            try {
                raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, candidate), 6000)
                raw.soTimeout = 8000
                val candidateSocket = context.socketFactory.createSocket(raw, host, candidate, true) as SSLSocket
                candidateSocket.useClientMode = true
                candidateSocket.sslParameters = candidateSocket.sslParameters.apply {
                    val available = candidateSocket.supportedProtocols.toSet()
                    protocols = listOf("TLSv1.3", "TLSv1.2").filter { it in available }
                        .ifEmpty { listOf("TLSv1.2") }.toTypedArray()
                }
                candidateSocket.startHandshake()
                candidateSocket.soTimeout = 0
                ssl = candidateSocket
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Pairing TLS to $host:$candidate failed: ${e.message}")
                try {
                    raw?.close()
                } catch (_: IOException) {
                }
            }
        }

        val connected = ssl ?: throw IOException(
            "Could not reach the TV's pairing service at $host.",
            lastError,
        )

        socket = connected
        output = connected.outputStream
        serverCertificate = connected.session.peerCertificates.firstOrNull() as? X509Certificate

        launchReader(connected)
        send(PairingMessage.request(SERVICE_NAME, clientName))

        try {
            withTimeout(timeoutMs) { started.await() }
        } catch (e: Exception) {
            close()
            throw IOException("The TV did not start the pairing dialog.", e)
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
            send(PairingMessage.secret(secret))
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

    private var serverName: String = ""

    private fun launchReader(sslSocket: SSLSocket) {
        scope.launch {
            try {
                val input = sslSocket.inputStream
                while (!closed) {
                    val frame = readFrame(input) ?: break
                    when (val event = PairingMessage.parse(frame)) {
                        is PairingMessage.Event.RequestAck -> {
                            serverName = event.serverName
                            send(PairingMessage.options())
                        }
                        PairingMessage.Event.Options -> send(PairingMessage.configuration())
                        PairingMessage.Event.ConfigurationAck -> {
                            if (!started.isCompleted) started.complete(serverName)
                        }
                        PairingMessage.Event.SecretAck -> {
                            if (!finished.isCompleted) finished.complete(Unit)
                        }
                        is PairingMessage.Event.Failure -> {
                            started.completeExceptionally(
                                IOException("The TV rejected the pairing (status ${event.status})."),
                            )
                            finished.completeExceptionally(
                                IOException("The TV rejected the pairing (status ${event.status})."),
                            )
                        }
                        PairingMessage.Event.Unknown -> Log.d(TAG, "Unhandled pairing message")
                    }
                }
                if (!closed) {
                    if (!finished.isCompleted) {
                        finished.completeExceptionally(IOException("The TV closed the pairing connection."))
                    }
                    if (!started.isCompleted) {
                        started.completeExceptionally(IOException("The TV closed the pairing connection."))
                    }
                }
            } catch (e: Exception) {
                if (!closed) {
                    started.completeExceptionally(e)
                    finished.completeExceptionally(e)
                }
            }
        }
    }

    private fun send(payload: ByteArray) {
        try {
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
    }
}

class InvalidPairingCodeException(message: String) : Exception(message)
