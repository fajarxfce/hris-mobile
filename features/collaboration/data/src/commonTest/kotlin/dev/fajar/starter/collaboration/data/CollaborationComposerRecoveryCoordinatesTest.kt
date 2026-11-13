package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AesGcmContentCipher
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class CollaborationComposerRecoveryCoordinatesTest {
    private val access =
        CollaborationLocalAccess(
            "login",
            "https://hris.example.test/",
            historyId(9000),
            historyId(9001),
        )
    private val generation = historyId(6000)

    @Test
    fun originalCompanyCursorFramesStayExactAndCannotAliasGroupFrames() {
        val company = CollaborationComposerCoordinates.Company(access)
        val space =
            CollaborationComposerCoordinates.Space(
                CollaborationSpaceLocalAccess(
                    access.sessionId,
                    access.audience,
                    access.accountId,
                    access.companyId,
                )
            )
        assertEquals(
            "collaboration-history/composer-recovery/" + access.companyId,
            company.recoveryPartition,
        )
        assertEquals(
            "collaboration-history/space-composer-recovery/v1/" + access.companyId,
            space.recoveryPartition,
        )
        assertEquals(
            """["hris-collaboration-composer-recovery-cursor-v1","https://hris.example.test/","a1000000-0000-4000-8000-000000009000","a1000000-0000-4000-8000-000000009001","a1000000-0000-4000-8000-000000006000","0"]""",
            Json.encodeToString(company.recoveryFrame(generation)),
        )
        assertNotEquals(company.recoveryFrame(generation), space.recoveryFrame(generation))
        assertNotEquals(space.partition, space.recoveryPartition)
    }

    @Test
    fun independentLegacyCursorCiphertextOpensAfterLoginRenewal() = runTest {
        val cipher = AesGcmContentCipher()
        val key = ByteArray(32) { it.toByte() }
        val oldFrame =
            listOf(
                "hris-collaboration-composer-recovery-cursor-v1",
                access.audience,
                access.accountId,
                access.companyId,
                generation,
                "0",
            )
        val retainedId = historyId(1001)
        val payload =
            Base64.encode(
                cipher.encrypt(
                    key,
                    Json.encodeToString(oldFrame).encodeToByteArray(),
                    Json.encodeToString(retainedId).encodeToByteArray(),
                )
            )
        val renewed = CollaborationComposerCoordinates.Company(access.copy(sessionId = "renewed"))
        try {
            val checkpoint =
                ReplicaCheckpoint(
                    ReplicaHandle(renewed.scope, renewed.recoveryPartition, generation),
                    0,
                    payload,
                    true,
                )
            assertEquals(
                CollaborationComposerRecoveryCursor(generation, retainedId),
                CollaborationComposerRecoveryCursorCodec(renewed, cipher, key).decode(checkpoint),
            )
        } finally {
            key.fill(0)
        }
    }
}
