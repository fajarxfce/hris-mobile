package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkIdentityData
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationSdkIdentityMapperTest {
    private val raw =
        CollaborationSdkIdentityData(
            "@native:proof.invalid",
            "PROOF_NATIVE",
            Base64.encode(ByteArray(32) { it.toByte() }),
            Base64.encode(ByteArray(32) { (it + 32).toByte() }),
        )

    @Test
    fun canonicalKeysMatchServerVectorAcrossBase64PaddingAndBindBothIds() = runTest {
        val identity = raw.toLocalDeviceIdentity()
        assertEquals(
            "5a82e4a4e4d17683c4354f1947220fa73f65383750bbbd59e041c0ad772621fc",
            identity.fingerprint,
        )
        assertEquals(raw.userId, identity.matrixUserId)
        assertEquals(raw.deviceId, identity.matrixDeviceId)
        assertEquals(
            identity,
            raw.copy(
                    ed25519 = raw.ed25519!!.trimEnd('='),
                    curve25519 = raw.curve25519!!.trimEnd('='),
                )
                .toLocalDeviceIdentity(),
        )
        assertNotEquals(
            identity.fingerprint,
            raw.copy(userId = "@other:proof.invalid").toLocalDeviceIdentity().fingerprint,
        )
        assertNotEquals(
            identity.fingerprint,
            raw.copy(deviceId = "PROOF_OTHER").toLocalDeviceIdentity().fingerprint,
        )
        assertNotEquals(
            identity.fingerprint,
            raw.copy(ed25519 = raw.curve25519, curve25519 = raw.ed25519)
                .toLocalDeviceIdentity()
                .fingerprint,
        )
        assertFalse(identity.toString().contains(raw.userId))
        assertFalse(raw.toString().contains(raw.ed25519!!))
    }

    @Test
    fun unavailableMalformedAndNoncanonicalKeysCannotBecomeIdentity() = runTest {
        val invalid =
            listOf(
                null,
                "",
                "x".repeat(1000),
                "a".repeat(43) + "==",
                "_".repeat(43),
                Base64.encode(ByteArray(31)),
                Base64.encode(ByteArray(33)),
                raw.ed25519!!.dropLast(2) + "9=",
            )
        for (value in invalid) {
            assertFailsWith<IllegalArgumentException> {
                raw.copy(ed25519 = value).toLocalDeviceIdentity()
            }
            assertFailsWith<IllegalArgumentException> {
                raw.copy(curve25519 = value).toLocalDeviceIdentity()
            }
        }
        for (value in
            listOf(
                "",
                "@a",
                "@a:",
                "@a\u0000:proof.invalid",
                "@a:proof.invalid ",
                "@" + "a".repeat(320),
            )) assertFailsWith<IllegalArgumentException> {
            raw.copy(userId = value).toLocalDeviceIdentity()
        }
        for (value in
            listOf("", "a".repeat(256), "PROOF\u0000NATIVE", "PROOF NATIVE")) assertFailsWith<
            IllegalArgumentException
        > {
            raw.copy(deviceId = value).toLocalDeviceIdentity()
        }
    }
}
