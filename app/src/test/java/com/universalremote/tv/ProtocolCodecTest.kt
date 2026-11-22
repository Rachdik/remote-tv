package com.universalremote.tv

import com.universalremote.tv.crypto.ClientIdentity
import com.universalremote.tv.protocol.PairingMessage
import com.universalremote.tv.protocol.ProtoReader
import com.universalremote.tv.protocol.ProtoWriter
import com.universalremote.tv.protocol.RemoteField
import com.universalremote.tv.protocol.RemoteMessages
import com.universalremote.tv.protocol.TvKey
import com.universalremote.tv.protocol.readFrame
import com.universalremote.tv.protocol.writeFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPublicKey

/** Wire-format tests for the protobuf codec, framing and pairing crypto. */
class ProtocolCodecTest {

    @Test
    fun `varint encodes and decodes the protobuf reference values`() {
        val cases = mapOf(
            0L to byteArrayOf(0x00),
            1L to byteArrayOf(0x01),
            127L to byteArrayOf(0x7F),
            128L to byteArrayOf(0x80.toByte(), 0x01),
            300L to byteArrayOf(0xAC.toByte(), 0x02),
            622L to byteArrayOf(0xEE.toByte(), 0x04),
        )
        for ((value, expected) in cases) {
            val encoded = ProtoWriter().varint(value).toByteArray()
            assertArrayEquals("encoding $value", expected, encoded)
            assertEquals("decoding $value", value, ProtoReader(encoded).readVarint())
        }
    }

    @Test
    fun `key inject matches the schema encoding`() {
        val payload = RemoteMessages.keyInject(TvKey.DPAD_UP)
        // field 10 (key_inject), length 4: key_code = 19, direction = SHORT (3)
        val expected = byteArrayOf(
            0x52, 0x04,
            0x08, 0x13,
            0x10, 0x03,
        )
        assertArrayEquals(expected, payload)
    }

    @Test
    fun `configure carries the requested feature mask`() {
        val payload = RemoteMessages.configure(0b1001100110)
        val reader = ProtoReader(payload)
        val field = reader.nextField()
        assertNotNull(field)
        assertEquals(RemoteField.CONFIGURE, field!!.number)
        val sub = reader.readMessage()
        val codeField = sub.nextField()!!
        assertEquals(1, codeField.number)
        assertEquals(0b1001100110, sub.readInt())
    }

    @Test
    fun `parser reads configure ping volume and voice begin`() {
        // RemoteConfigure { code1 = 622, device_info { model, vendor } }
        val configure = ProtoWriter().message(RemoteField.CONFIGURE) {
            int(1, 622)
            message(2) {
                string(1, "Chromecast")
                string(2, "Google")
            }
        }.toByteArray()
        val configureEvents = RemoteMessages.parse(configure)
        val cfg = configureEvents.filterIsInstance<RemoteMessages.Event.ConfigureReceived>().single()
        assertEquals(622, cfg.features)
        assertEquals("Chromecast", cfg.deviceInfo.model)
        assertEquals("Google", cfg.deviceInfo.vendor)

        val ping = ProtoWriter().message(RemoteField.PING_REQUEST) { int(1, 7) }.toByteArray()
        assertEquals(7, RemoteMessages.parse(ping).filterIsInstance<RemoteMessages.Event.Ping>().single().value)

        val volume = ProtoWriter().message(RemoteField.SET_VOLUME_LEVEL) {
            int(6, 100)
            int(7, 42)
        }.toByteArray()
        val info = RemoteMessages.parse(volume).filterIsInstance<RemoteMessages.Event.Volume>().single().info
        assertEquals(100, info.max)
        assertEquals(42, info.level)

        val voice = ProtoWriter().message(RemoteField.VOICE_BEGIN) { int(1, 3) }.toByteArray()
        assertEquals(
            3,
            RemoteMessages.parse(voice).filterIsInstance<RemoteMessages.Event.VoiceBegin>().single().sessionId,
        )
    }

    @Test
    fun `unknown fields are skipped instead of failing`() {
        val withUnknown = ProtoWriter().message(RemoteField.PING_REQUEST) {
            int(1, 9)
            string(77, "future field")
        }.toByteArray()
        val events = RemoteMessages.parse(withUnknown)
        assertEquals(9, events.filterIsInstance<RemoteMessages.Event.Ping>().single().value)
    }

    @Test
    fun `ime batch edit encodes counters and text`() {
        val text = "Halo dunia"
        val payload = RemoteMessages.imeBatchEdit(text, imeCounter = 4, fieldCounter = 2)
        val reader = ProtoReader(payload)
        val field = reader.nextField()!!
        assertEquals(RemoteField.IME_BATCH_EDIT, field.number)
        val sub = reader.readMessage()
        var ime = 0
        var fld = 0
        var value = ""
        var start = -1
        var end = -1
        while (true) {
            val f = sub.nextField() ?: break
            when (f.number) {
                1 -> ime = sub.readInt()
                2 -> fld = sub.readInt()
                3 -> {
                    val edit = sub.readMessage()
                    while (true) {
                        val e = edit.nextField() ?: break
                        if (e.number == 2) {
                            val status = edit.readMessage()
                            while (true) {
                                val s = status.nextField() ?: break
                                when (s.number) {
                                    1 -> start = status.readInt()
                                    2 -> end = status.readInt()
                                    3 -> value = status.readString()
                                    else -> status.skip(s.wireType)
                                }
                            }
                        } else {
                            edit.skip(e.wireType)
                        }
                    }
                }
                else -> sub.skip(f.wireType)
            }
        }
        assertEquals(4, ime)
        assertEquals(2, fld)
        assertEquals(text, value)
        assertEquals(text.length, start)
        assertEquals(text.length, end)
    }

    @Test
    fun `framing survives split and coalesced messages`() {
        val first = RemoteMessages.keyInject(TvKey.HOME)
        val second = RemoteMessages.keyInject(TvKey.BACK)
        val stream = ByteArrayOutputStream()
        writeFrame(stream, first)
        writeFrame(stream, second)
        val bytes = stream.toByteArray()

        // Feed the parser one byte at a time, the worst case for a TCP stream.
        val input = ByteArrayInputStream(bytes)
        val chunked = object : java.io.InputStream() {
            override fun read(): Int = input.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                input.read(b, off, minOf(1, len))
        }
        chunked.use {
            assertArrayEquals(first, readFrame(it))
            assertArrayEquals(second, readFrame(it))
            assertNull(readFrame(it))
        }
    }

    @Test
    fun `pairing messages match the polo schema`() {
        val request = PairingMessage.request("atvremote", "Unit Test")
        val reader = ProtoReader(request)
        val version = reader.nextField()!!
        assertEquals(1, version.number)
        assertEquals(2, reader.readInt())
        val status = reader.nextField()!!
        assertEquals(2, status.number)
        assertEquals(200, reader.readInt())

        val options = PairingMessage.options()
        val optionsReader = ProtoReader(options)
        optionsReader.nextField()
        optionsReader.readInt()
        optionsReader.nextField()
        optionsReader.readInt()
        val optionsField = optionsReader.nextField()!!
        assertEquals(20, optionsField.number)
        val body = optionsReader.readMessage()
        val encoding = body.nextField()!!
        assertEquals(1, encoding.number)
        val encodingBody = body.readMessage()
        val typeField = encodingBody.nextField()!!
        assertEquals(1, typeField.number)
        assertEquals(3, encodingBody.readInt()) // ENCODING_TYPE_HEXADECIMAL
        val lengthField = encodingBody.nextField()!!
        assertEquals(2, lengthField.number)
        assertEquals(6, encodingBody.readInt())
        val roleField = body.nextField()!!
        assertEquals(3, roleField.number)
        assertEquals(1, body.readInt()) // ROLE_TYPE_INPUT

        val ack = PairingMessage.parse(
            ProtoWriter()
                .int(1, 2)
                .int(2, 200)
                .message(11) { string(1, "Living Room TV") }
                .toByteArray(),
        )
        assertEquals("Living Room TV", (ack as PairingMessage.Event.RequestAck).serverName)
    }

    @Test
    fun `client identity produces a usable self-signed certificate`() {
        val identity = ClientIdentity.generate("Unit Test Phone")
        val certificate = identity.certificate
        certificate.checkValidity()
        assertEquals("RSA", certificate.publicKey.algorithm)
        assertEquals(2048, (certificate.publicKey as RSAPublicKey).modulus.bitLength())

        val restored = ClientIdentity.fromPkcs12(
            identity.toPkcs12(ClientIdentity.defaultPassword()),
            ClientIdentity.defaultPassword(),
        )
        assertArrayEquals(certificate.encoded, restored.certificate.encoded)
    }

    @Test
    fun `pairing code check digits are verified against the certificate`() {
        val phone = ClientIdentity.generate("Unit Test Phone")
        val tv = ClientIdentity.generate("Unit Test TV")

        // The digest depends on the last four characters; the first two are its checksum.
        val suffix = "1A2B"
        val secret = ClientIdentity.pairingSecret(phone, tv.certificate, "00$suffix")
        val validCode = "%02X".format(secret[0].toInt() and 0xFF) + suffix

        assertNull(ClientIdentity.verifyCode(phone, tv.certificate, validCode))

        // Same digest seen from the TV side, which only has the client certificate.
        val fromTv = ClientIdentity.pairingSecret(phone.publicKey, tv.certificate, validCode)
        assertArrayEquals(secret, fromTv)

        val tampered = "FF" + suffix
        assertTrue(ClientIdentity.verifyCode(phone, tv.certificate, tampered) != null)
        assertTrue(ClientIdentity.verifyCode(phone, tv.certificate, "12") != null)
        assertTrue(ClientIdentity.verifyCode(phone, tv.certificate, "ZZZZZZ") != null)
    }
}
