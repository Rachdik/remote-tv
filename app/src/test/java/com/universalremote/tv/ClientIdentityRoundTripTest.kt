package com.universalremote.tv

import com.universalremote.tv.crypto.ClientIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature

class ClientIdentityRoundTripTest {

    @Test
    fun pkcs12RoundTripKeepsCertificateAndPrivateKey() {
        val identity = ClientIdentity.generate("Test Phone")
        val blob = identity.toPkcs12(ClientIdentity.defaultPassword())
        val restored = ClientIdentity.fromPkcs12(blob, ClientIdentity.defaultPassword())

        assertArrayEquals(identity.certificateDer, restored.certificateDer)
        assertEquals(
            identity.certificate.serialNumber,
            restored.certificate.serialNumber,
        )

        val payload = byteArrayOf(1, 2, 3, 4)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(restored.privateKey)
        signer.update(payload)
        val signature = signer.sign()

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(identity.certificate.publicKey)
        verifier.update(payload)
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun twoGeneratedIdentitiesAreDifferent() {
        val a = ClientIdentity.generate("A")
        val b = ClientIdentity.generate("B")
        assertTrue(a.certificate.serialNumber != b.certificate.serialNumber)
    }
}
