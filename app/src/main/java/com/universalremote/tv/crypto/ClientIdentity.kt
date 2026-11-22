package com.universalremote.tv.crypto

import com.universalremote.tv.protocol.PairingMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The long-lived client identity used for pairing and for every later remote connection.
 *
 * The Android TV pairing protocol binds a client to the TV through a self-signed certificate:
 * the TV stores our certificate's fingerprint when pairing succeeds and only accepts a client
 * presenting the same certificate on the control port. There is no CA involved, so the identity
 * is generated on the device and the private key never leaves it.
 */
class ClientIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val certificateDer: ByteArray,
) {
    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    fun toPkcs12(password: CharArray): ByteArray {
        val store = KeyStore.getInstance(KEYSTORE_TYPE)
        store.load(null, null)
        store.setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
        val out = ByteArrayOutputStream()
        store.store(out, password)
        return out.toByteArray()
    }

    companion object {
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val ALIAS = "atvremote-client"
        private val PASSWORD = "atvremote".toCharArray()

        /**
         * Builds a 2048-bit RSA key pair and a self-signed v3 certificate for it.
         *
         * A small DER encoder is used instead of a certificate library so the app stays
         * dependency-free and behaves identically from API 25 upwards.
         */
        fun generate(clientName: String): ClientIdentity {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val keyPair = generator.generateKeyPair()

            val subject = "CN=$clientName, O=Universal TV Remote"
            val now = System.currentTimeMillis()
            val notBefore = utcTime(Date(now - 24L * 60 * 60 * 1000))
            val notAfter = utcTime(Date(now + 20L * 365 * 24 * 60 * 60 * 1000))
            val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)

            val signatureAlgorithm = Der.sequence(
                Der.oid("1.2.840.113549.1.1.11"), // sha256WithRSAEncryption
                Der.nullValue(),
            )
            val name = Der.sequence(
                Der.set(
                    Der.sequence(Der.oid("2.5.4.3"), Der.utf8String(subject.substringAfter("CN=").substringBefore(","))),
                ),
            )
            val validity = Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter))
            val subjectPublicKeyInfo = keyPair.public.encoded // already DER SubjectPublicKeyInfo

            val tbsCertificate = Der.sequence(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // v3
                Der.integer(serial),
                signatureAlgorithm,
                name,
                validity,
                name,
                subjectPublicKeyInfo,
            )

            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(keyPair.private)
            signer.update(tbsCertificate)
            val signature = signer.sign()

            val certificateDer = Der.sequence(
                tbsCertificate,
                signatureAlgorithm,
                Der.bitString(signature),
            )

            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate

            return ClientIdentity(keyPair.private, certificate, certificateDer)
        }

        fun fromPkcs12(bytes: ByteArray, password: CharArray = PASSWORD): ClientIdentity {
            val store = KeyStore.getInstance(KEYSTORE_TYPE)
            store.load(ByteArrayInputStream(bytes), password)
            val key = store.getKey(ALIAS, password) as PrivateKey
            val certificate = store.getCertificate(ALIAS) as X509Certificate
            return ClientIdentity(key, certificate, certificate.encoded)
        }

        fun defaultPassword(): CharArray = PASSWORD.copyOf()

        private fun utcTime(date: Date): String =
            SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(date)

        /**
         * TLS context that presents [identity] as the client certificate.
         *
         * The TV's certificate is intentionally not validated against a CA: the protocol has no
         * PKI, and trust is established by the pairing secret derived from both certificates
         * (see [pairingSecret]). Hostname verification does not apply to this protocol either.
         */
        fun sslContext(identity: ClientIdentity): SSLContext {
            val store = KeyStore.getInstance(KEYSTORE_TYPE)
            store.load(null, null)
            store.setKeyEntry(ALIAS, identity.privateKey, PASSWORD, arrayOf(identity.certificate))

            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(store, PASSWORD)

            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            return SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, trustAll, SecureRandom())
            }
        }

        /**
         * The pairing secret: SHA-256 over both public keys and the last four digits of the code
         * shown on the TV. The first byte of the digest is what the code's first two hex digits
         * encode, which is how the phone verifies a mistyped code before sending it.
         */
        fun pairingSecret(identity: ClientIdentity, serverCertificate: X509Certificate, code: String): ByteArray =
            pairingSecret(identity.publicKey, serverCertificate, code)

        /** Same derivation from the TV's point of view, where only the client's certificate is known. */
        fun pairingSecret(
            clientPublicKey: RSAPublicKey,
            serverCertificate: X509Certificate,
            code: String,
        ): ByteArray {
            require(code.length == 6) { "pairing code must be 6 hex characters" }
            val serverKey = serverCertificate.publicKey as RSAPublicKey
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(unsignedBytes(clientPublicKey.modulus))
            digest.update(unsignedBytes(clientPublicKey.publicExponent))
            digest.update(unsignedBytes(serverKey.modulus))
            digest.update(unsignedBytes(serverKey.publicExponent))
            digest.update(hexToBytes(code.substring(2)))
            return digest.digest()
        }

        /**
         * @return null when the code is structurally valid and its check digits match the TV's
         * certificate, otherwise a human readable reason.
         */
        fun verifyCode(identity: ClientIdentity, serverCertificate: X509Certificate, code: String): String? {
            if (code.length != 6) return "The code is 6 characters long."
            if (!code.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return "Use only 0-9 and A-F."
            val secret = pairingSecret(identity, serverCertificate, code)
            val expected = code.substring(0, 2).toInt(16)
            if ((secret[0].toInt() and 0xFF) != expected) {
                return "That code does not match this TV. Check the code on the TV screen."
            }
            return null
        }

        /** Big-endian minimal-length unsigned representation, matching the reference implementation. */
        private fun unsignedBytes(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            var start = 0
            while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
            return if (start == 0) raw else raw.copyOfRange(start, raw.size)
        }

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length % 2 == 0) { "odd hex length" }
            return ByteArray(clean.length / 2) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
        }
    }
}

/** Tiny DER encoder, enough for a self-signed X.509 v3 certificate. */
private object Der {

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.concat())

    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.concat())

    fun explicit(tagNumber: Int, content: ByteArray): ByteArray = tlv(0xA0 + tagNumber, content)

    fun integer(value: BigInteger): ByteArray {
        val bytes = value.toByteArray() // already minimal and two's-complement
        return tlv(0x02, bytes)
    }

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            var v = arc
            val stack = ArrayDeque<Int>()
            do {
                stack.addFirst((v and 0x7f).toInt())
                v = v shr 7
            } while (v != 0L)
            while (stack.isNotEmpty()) {
                var b = stack.removeFirst()
                if (stack.isNotEmpty()) b = b or 0x80
                out.write(b)
            }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun utf8String(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    fun utcTime(value: String): ByteArray = tlv(0x17, value.toByteArray(Charsets.US_ASCII))

    fun bitString(value: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0x00) + value)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        out.write(encodeLength(content.size))
        out.write(content)
        return out.toByteArray()
    }

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        length < 0x10000 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        else -> byteArrayOf(
            0x83.toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )
    }

    private fun Array<out ByteArray>.concat(): ByteArray {
        val out = ByteArrayOutputStream(sumOf { it.size })
        for (part in this) out.write(part)
        return out.toByteArray()
    }
}
