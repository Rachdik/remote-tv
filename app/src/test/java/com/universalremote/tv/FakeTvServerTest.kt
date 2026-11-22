package com.universalremote.tv

import com.universalremote.tv.connection.RemoteSession
import com.universalremote.tv.crypto.ClientIdentity
import com.universalremote.tv.pairing.PairingClient
import com.universalremote.tv.protocol.ProtoReader
import com.universalremote.tv.protocol.ProtoWriter
import com.universalremote.tv.protocol.RemoteField
import com.universalremote.tv.protocol.RemoteMessages
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.protocol.readFrame
import com.universalremote.tv.protocol.writeFrame
import com.universalremote.tv.storage.PairedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.OutputStream
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * End-to-end test of the client against a local server that speaks the Android TV side of the
 * protocol: the same TLS setup, the same Polo handshake, the same control messages.
 *
 * This proves the transport, framing, pairing secret and command encoding work together. It is
 * not a substitute for a physical TV (see docs/TESTING.md).
 */
class FakeTvServerTest {

    private val tvIdentity = ClientIdentity.generate("Fake TV")
    private val tvContext: SSLContext = ClientIdentity.sslContext(tvIdentity)

    private fun validCodeFor(phone: ClientIdentity): String {
        val suffix = "1A2B"
        val secret = ClientIdentity.pairingSecret(phone, tvIdentity.certificate, "00$suffix")
        return "%02X".format(secret[0].toInt() and 0xFF) + suffix
    }

    @Test
    fun `pairs with the on screen code and then controls the tv`() = runBlocking {
        val phone = ClientIdentity.generate("Test Phone")
        val code = validCodeFor(phone)
        FakeTv(tvContext, tvIdentity, code).use { tv ->
            // --- pairing ------------------------------------------------------------------
            val pairing = PairingClient("127.0.0.1", tv.pairingPort, phone, "Test Phone")
            val serverName = pairing.start()
            assertEquals("Fake TV", serverName)
            val result = pairing.submitCode(code)
            assertEquals("Fake TV", result.serverName)
            val accepted = withTimeout(5000) { tv.secretAccepted.await() }
            assertTrue("the TV must accept the derived secret", accepted)
        }
    }

    @Test
    fun `rejects a pairing code that does not match the tv certificate`() = runBlocking {
        val phone = ClientIdentity.generate("Test Phone")
        val code = validCodeFor(phone)
        FakeTv(tvContext, tvIdentity, code).use { tv ->
            val pairing = PairingClient("127.0.0.1", tv.pairingPort, phone, "Test Phone")
            pairing.start()
            val wrong = if (code.startsWith("00")) "01" + code.substring(2) else "00" + code.substring(2)
            val failure = runCatching { pairing.submitCode(wrong) }.exceptionOrNull()
            assertNotNull("a wrong code must be rejected before sending", failure)
            pairing.close()
        }
    }

    @Test
    fun `control channel negotiates features answers pings and sends commands`() = runBlocking {
        val phone = ClientIdentity.generate("Test Phone")
        val code = validCodeFor(phone)
        FakeTv(tvContext, tvIdentity, code).use { tv ->
            val events = mutableListOf<RemoteMessages.Event>()
            val voiceBegin = CompletableDeferred<Int>()
            val session = RemoteSession(
                device = PairedDevice(
                    id = "test",
                    name = "Fake TV",
                    host = "127.0.0.1",
                    model = "",
                    remotePort = tv.controlPort,
                    pairingPort = tv.pairingPort,
                ),
                identity = phone,
                onEvent = { event ->
                    synchronized(events) { events += event }
                    if (event is RemoteMessages.Event.VoiceBegin) voiceBegin.complete(event.sessionId)
                },
                onClosed = {},
            )
            try {
                val info = session.connect()
                assertEquals("FakeTV", info.model)
                assertEquals("FakeVendor", info.vendor)

                // The TV answered our configure with its feature mask, and the client negotiated it.
                withTimeout(5000) { tv.handshakeDone.await() }
                assertEquals(623, tv.negotiatedFeatures)
                assertEquals(623, session.negotiatedFeatures)

                // Keep-alive: the TV sent a ping request and the client answered with the same value.
                assertEquals(5, withTimeout(5000) { tv.pingAnswered.await() })

                // Volume and power come back as events.
                val volume = withTimeout(5000) {
                    var found: RemoteMessages.Event.Volume? = null
                    while (found == null) {
                        found = synchronized(events) {
                            events.filterIsInstance<RemoteMessages.Event.Volume>().firstOrNull()
                        }
                        if (found == null) delay(50)
                    }
                    found
                }
                assertEquals(42, volume.info.level)
                assertEquals(100, volume.info.max)

                val started = withTimeout(5000) {
                    var found: RemoteMessages.Event.Started? = null
                    while (found == null) {
                        found = synchronized(events) {
                            events.filterIsInstance<RemoteMessages.Event.Started>().firstOrNull()
                        }
                        if (found == null) delay(50)
                    }
                    found!!
                }
                assertTrue(started.started)

                // Keys reach the TV with the right key codes.
                session.sendKey(TvKey.DPAD_UP)
                session.sendKey(TvKey.DPAD_CENTER)
                session.sendKey(TvKey.BACK)
                val received = mutableListOf<Int>()
                for (i in 0 until 3) {
                    val key = tv.keys.poll(5, TimeUnit.SECONDS)
                    assertNotNull("key $i was not received by the TV", key)
                    received += key!!
                }
                assertEquals(listOf(19, 23, 4), received)

                // Text goes out as an IME batch edit carrying the echoed counters.
                session.sendText("Halo dunia", imeCounter = 4, fieldCounter = 2)
                val text = tv.texts.poll(5, TimeUnit.SECONDS)
                assertEquals("Halo dunia", text)

                // Voice: the TV opens a session when it receives SEARCH, then accepts PCM chunks.
                session.sendKey(TvKey.SEARCH)
                val sessionId = withTimeout(5000) { voiceBegin.await() }
                assertEquals(7, sessionId)
                session.sendVoiceBegin(sessionId)
                session.sendVoicePayload(sessionId, ByteArray(1600) { 1 })
                session.sendVoiceEnd(sessionId)
                val payload = tv.voicePayloads.poll(5, TimeUnit.SECONDS)
                assertNotNull("the TV should have received a voice payload", payload)
                assertArrayEquals(ByteArray(1600) { 1 }, payload)
                assertTrue("voice begin must reach the TV", tv.voiceBegins.poll(5, TimeUnit.SECONDS) == 7)
                assertTrue("voice end must reach the TV", tv.voiceEnds.poll(5, TimeUnit.SECONDS) == 7)
            } finally {
                session.close()
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Minimal server-side implementation of the TV half of the protocol. */
    private class FakeTv(
        context: SSLContext,
        private val identity: ClientIdentity,
        private val expectedCode: String,
    ) : Closeable {

        private val pairingServer = newServer(context)
        private val controlServer = newServer(context)
        private var pairingClient: SSLSocket? = null
        private var controlClient: SSLSocket? = null

        val pairingPort: Int get() = pairingServer.localPort
        val controlPort: Int get() = controlServer.localPort

        val secretAccepted = CompletableDeferred<Boolean>()
        val handshakeDone = CompletableDeferred<Unit>()
        val pingAnswered = CompletableDeferred<Int>()
        val keys = LinkedBlockingQueue<Int>()
        val texts = LinkedBlockingQueue<String>()
        val voicePayloads = LinkedBlockingQueue<ByteArray>()
        val voiceBegins = LinkedBlockingQueue<Int>()
        val voiceEnds = LinkedBlockingQueue<Int>()

        @Volatile
        var negotiatedFeatures: Int = -1


        private val threads = mutableListOf<Thread>()

        init {
            startPairingThread()
            startControlThread()
        }

        private fun newServer(context: SSLContext): SSLServerSocket {
            val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            socket.needClientAuth = true
            socket.soTimeout = 20000
            return socket
        }

        private fun startPairingThread() {
            threads += Thread {
                try {
                    val socket = pairingServer.accept() as SSLSocket
                    pairingClient = socket
                    socket.startHandshake()
                    val clientCertificate = socket.session.peerCertificates.first() as X509Certificate
                    val input = socket.inputStream
                    val output = socket.outputStream

                    // PairingRequest -> PairingRequestAck
                    assertNotNull(readFrame(input))
                    writeFrame(output, envelope { message(11) { string(1, "Fake TV") } })

                    // Options -> Options
                    assertNotNull(readFrame(input))
                    writeFrame(
                        output,
                        envelope {
                            message(20) {
                                message(1) {
                                    int(1, 3)
                                    int(2, 6)
                                }
                                int(3, 1)
                            }
                        },
                    )

                    // Configuration -> ConfigurationAck (the TV now shows the code)
                    assertNotNull(readFrame(input))
                    writeFrame(output, envelope { message(31) { } })

                    // Secret -> check it, then SecretAck
                    val secretFrame = readFrame(input)
                    assertNotNull(secretFrame)
                    val provided = parseSecret(secretFrame!!)
                    val expected = ClientIdentity.pairingSecret(
                        clientCertificate.publicKey as RSAPublicKey,
                        identity.certificate,
                        expectedCode,
                    )
                    val ok = provided.contentEquals(expected)
                    secretAccepted.complete(ok)
                    writeFrame(output, envelope { message(41) { bytes(1, provided) } })
                    Thread.sleep(50)
                    socket.close()
                } catch (t: Throwable) {
                    secretAccepted.completeExceptionally(t)
                }
            }.apply { isDaemon = true; start() }
        }

        private fun startControlThread() {
            threads += Thread {
                try {
                    val socket = controlServer.accept() as SSLSocket
                    controlClient = socket
                    socket.startHandshake()
                    val input = socket.inputStream
                    val output = socket.outputStream
                    var handshakeSent = false

                    while (true) {
                        val frame = readFrame(input) ?: break
                        val reader = ProtoReader(frame)
                        while (true) {
                            val field = reader.nextField() ?: break
                            when (field.number) {
                                RemoteField.CONFIGURE -> {
                                    val mask = readConfigureMask(reader.readMessage())
                                    if (!handshakeSent) {
                                        // First configure from the client: answer with our features.
                                        handshakeSent = true
                                        negotiatedFeatures = mask
                                        writeFrame(
                                            output,
                                            ProtoWriter().message(RemoteField.CONFIGURE) {
                                                int(1, 623, writeDefault = true)
                                                message(2) {
                                                    string(1, "FakeTV")
                                                    string(2, "FakeVendor")
                                                    string(6, "1.0")
                                                }
                                            }.toByteArray(),
                                        )
                                        sendPingAndState(output)
                                        handshakeDone.complete(Unit)
                                    } else {
                                        negotiatedFeatures = mask
                                    }
                                }

                                RemoteField.PING_RESPONSE -> {
                                    val sub = reader.readMessage()
                                    var value = 0
                                    while (true) {
                                        val f = sub.nextField() ?: break
                                        if (f.number == 1) value = sub.readInt() else sub.skip(f.wireType)
                                    }
                                    pingAnswered.complete(value)
                                }

                                RemoteField.KEY_INJECT -> {
                                    val sub = reader.readMessage()
                                    var code = 0
                                    while (true) {
                                        val f = sub.nextField() ?: break
                                        if (f.number == 1) code = sub.readInt() else sub.skip(f.wireType)
                                    }
                                    keys.offer(code)
                                    if (code == TvKey.SEARCH.code) {
                                        writeFrame(
                                            output,
                                            ProtoWriter()
                                                .message(RemoteField.VOICE_BEGIN) { int(1, 7, writeDefault = true) }
                                                .toByteArray(),
                                        )
                                    }
                                }

                                RemoteField.IME_BATCH_EDIT -> {
                                    val sub = reader.readMessage()
                                    while (true) {
                                        val f = sub.nextField() ?: break
                                        if (f.number == 3 && f.wireType == ProtoWriter.WIRE_LENGTH_DELIMITED) {
                                            val edit = sub.readMessage()
                                            while (true) {
                                                val e = edit.nextField() ?: break
                                                if (e.number == 2) {
                                                    val status = edit.readMessage()
                                                    while (true) {
                                                        val s = status.nextField() ?: break
                                                        if (s.number == 3) texts.offer(status.readString())
                                                        else status.skip(s.wireType)
                                                    }
                                                } else {
                                                    edit.skip(e.wireType)
                                                }
                                            }
                                        } else {
                                            sub.skip(f.wireType)
                                        }
                                    }
                                }

                                RemoteField.VOICE_BEGIN -> voiceBegins.offer(readIntField(reader, 1))
                                RemoteField.VOICE_END -> voiceEnds.offer(readIntField(reader, 1))
                                RemoteField.VOICE_PAYLOAD -> {
                                    val sub = reader.readMessage()
                                    while (true) {
                                        val f = sub.nextField() ?: break
                                        if (f.number == 2) voicePayloads.offer(sub.readBytes()) else sub.skip(f.wireType)
                                    }
                                }

                                else -> reader.skip(field.wireType)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    handshakeDone.completeExceptionally(t)
                }
            }.apply { isDaemon = true; start() }
        }

        private fun sendPingAndState(output: OutputStream) {
            writeFrame(output, ProtoWriter().message(RemoteField.PING_REQUEST) { int(1, 5) }.toByteArray())
            writeFrame(
                output,
                ProtoWriter().message(RemoteField.SET_VOLUME_LEVEL) {
                    int(6, 100)
                    int(7, 42)
                }.toByteArray(),
            )
            writeFrame(output, ProtoWriter().message(RemoteField.START) { bool(1, true) }.toByteArray())
        }

        private fun readIntField(reader: ProtoReader, number: Int): Int {
            val sub = reader.readMessage()
            var value = 0
            while (true) {
                val f = sub.nextField() ?: break
                if (f.number == number) value = sub.readInt() else sub.skip(f.wireType)
            }
            return value
        }

        private fun readConfigureMask(reader: ProtoReader): Int {
            var mask = 0
            while (true) {
                val field = reader.nextField() ?: break
                when (field.number) {
                    1 -> mask = reader.readInt()
                    else -> reader.skip(field.wireType)
                }
            }
            return mask
        }

        private fun envelope(body: ProtoWriter.() -> Unit): ByteArray =
            ProtoWriter()
                .int(1, 2, writeDefault = true)
                .int(2, 200, writeDefault = true)
                .apply(body)
                .toByteArray()

        private fun parseSecret(payload: ByteArray): ByteArray {
            val reader = ProtoReader(payload)
            while (true) {
                val field = reader.nextField() ?: break
                if (field.number == 40) {
                    val sub = reader.readMessage()
                    while (true) {
                        val f = sub.nextField() ?: break
                        if (f.number == 1) return sub.readBytes() else sub.skip(f.wireType)
                    }
                } else {
                    reader.skip(field.wireType)
                }
            }
            throw IllegalStateException("no secret in pairing message")
        }

        override fun close() {
            try {
                pairingClient?.close()
            } catch (_: Exception) {
            }
            try {
                controlClient?.close()
            } catch (_: Exception) {
            }
            try {
                pairingServer.close()
            } catch (_: Exception) {
            }
            try {
                controlServer.close()
            } catch (_: Exception) {
            }
            threads.forEach { it.interrupt() }
        }
    }

}
