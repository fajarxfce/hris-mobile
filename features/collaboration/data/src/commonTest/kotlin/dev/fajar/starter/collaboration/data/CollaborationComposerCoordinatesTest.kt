package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AesGcmContentCipher
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class CollaborationComposerCoordinatesTest {
    private val access =
        CollaborationLocalAccess(
            "login",
            "https://hris.example.test/",
            historyId(9000),
            historyId(9001),
        )
    private val generation = historyId(6000)
    private val partition = "collaboration-history/composer/" + historyId(9001)
    private val draft =
        CollaborationDraft(
            historyId(9002),
            historyId(1001),
            "Legacy text",
            Instant.parse("2026-11-01T12:00:00Z"),
        )
    private val submission =
        CollaborationComposerSubmission(
            draft,
            historyId(1002),
            Instant.parse("2026-11-01T12:00:01Z"),
        )

    @Test
    fun companyAuthenticatedFramesKeepTheOriginalExactBytes() {
        val coordinates = CollaborationComposerCoordinates.Company(access)
        assertEquals(partition, coordinates.partition)
        assertEquals(
            """["hris-composer-content-v2","https://hris.example.test/","a1000000-0000-4000-8000-000000009000","a1000000-0000-4000-8000-000000009001","collaboration-history/composer/a1000000-0000-4000-8000-000000009001","a1000000-0000-4000-8000-000000006000","checkpoint","3",""]""",
            Json.encodeToString(coordinates.draftFrame(generation, "checkpoint", 3, "")),
        )
        assertEquals(
            """["hris-composer-outbox-v1","https://hris.example.test/","a1000000-0000-4000-8000-000000009000","a1000000-0000-4000-8000-000000009001","collaboration-history/composer/a1000000-0000-4000-8000-000000009001","a1000000-0000-4000-8000-000000006000","a1000000-0000-4000-8000-000000001001","3"]""",
            Json.encodeToString(coordinates.outboxFrame(generation, draft.revision, 3)),
        )
    }

    @Test
    fun independentLegacyCiphertextOpensWithTheCurrentCompanyDecoder() = runTest {
        val cipher = AesGcmContentCipher()
        val key = ByteArray(32) { it.toByte() }
        // Build pre-existing frames independently of either new coordinate or codec encoder.
        val prefix =
            listOf(
                "hris-composer-content-v2",
                access.audience,
                access.accountId,
                access.companyId,
                partition,
                generation,
            )
        suspend fun legacy(aad: List<String>, plaintext: String) =
            Base64.encode(
                cipher.encrypt(
                    key,
                    Json.encodeToString(aad).encodeToByteArray(),
                    plaintext.encodeToByteArray(),
                )
            )
        try {
            val checkpoint =
                ReplicaCheckpoint(
                    ReplicaHandle(historyScope(access), partition, generation),
                    3,
                    legacy(prefix + listOf("checkpoint", "3", ""), "composer-v2"),
                    true,
                )
            val document =
                ReplicaDocument(
                    draft.conversationId,
                    2,
                    draft.conversationId,
                    legacy(
                        prefix + listOf(draft.conversationId, "2", draft.conversationId),
                        """{"schema":2,"revision":"${draft.revision}","text":"Legacy text","savedAt":"2026-11-01T12:00:00Z"}""",
                    ),
                    emptySet(),
                )
            val outgoing =
                ReplicaDocument(
                    submission.id,
                    3,
                    submission.id,
                    legacy(
                        listOf(
                            "hris-composer-outbox-v1",
                            access.audience,
                            access.accountId,
                            access.companyId,
                            partition,
                            generation,
                            submission.id,
                            "3",
                        ),
                        """{"schema":1,"conversationId":"${draft.conversationId}","id":"${submission.id}","text":"Legacy text","draftSavedAt":"2026-11-01T12:00:00Z","replyToEventId":null,"nextRevision":"${submission.nextRevision}","submittedAt":"2026-11-01T12:00:01Z","reviewRequired":false}""",
                    ),
                    setOf(COMPOSER_OUTBOX_TAG),
                )
            val coordinates =
                CollaborationComposerCoordinates.Company(access.copy(sessionId = "renewed"))
            val codec = CollaborationDraftCodec(coordinates, cipher, key)
            val renewed =
                checkpoint.copy(
                    handle =
                        checkpoint.handle.copy(
                            scope = historyScope(access.copy(sessionId = "renewed"))
                        )
                )
            codec.checkpoint(renewed)
            assertEquals(draft, codec.decode(renewed, document))
            assertEquals(
                submission,
                CollaborationComposerSubmissionCodec(coordinates, cipher, key)
                    .decode(renewed, outgoing),
            )
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun equalCompanyAndGroupIdsKeepDifferentCryptographicNamespaces() {
        val company = CollaborationComposerCoordinates.Company(access)
        val local =
            CollaborationSpaceLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                access.companyId,
            )
        val group = CollaborationComposerCoordinates.Space(local)
        val other = CollaborationComposerCoordinates.Space(local.copy(spaceId = historyId(9003)))
        assertEquals(company.scope, group.scope)
        assertEquals("collaboration-history/space-composer/v1/" + access.companyId, group.partition)
        assertNotEquals(company.partition, group.partition)
        assertNotEquals(group.partition, other.partition)
        assertNotEquals(
            company.draftFrame(generation, "checkpoint", 3, ""),
            group.draftFrame(generation, "checkpoint", 3, ""),
        )
        assertNotEquals(
            company.outboxFrame(generation, submission.id, 3),
            group.outboxFrame(generation, submission.id, 3),
        )
        assertNotEquals(
            group.outboxFrame(generation, submission.id, 3),
            other.outboxFrame(generation, submission.id, 3),
        )
        assertEquals(
            group.outboxFrame(generation, submission.id, 3),
            CollaborationComposerCoordinates.Space(local.copy(sessionId = "renewed"))
                .outboxFrame(generation, submission.id, 3),
        )
    }
}
