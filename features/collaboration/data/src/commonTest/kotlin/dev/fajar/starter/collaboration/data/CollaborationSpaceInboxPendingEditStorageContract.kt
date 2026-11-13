package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceInboxRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

abstract class CollaborationSpaceInboxPendingEditStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun pendingEditsRoundTripWithoutMovingTheOriginalOrTyping() = withDatabase { f ->
        val original = f.add(101)
        val oldJson = Json.encodeToString(InboxActivityDto(original))
        assertFalse(oldJson.contains("pendingEdit"))
        assertEquals(original, Json.decodeFromString<InboxActivityDto>(oldJson).domain())
        f.type("Still typing")
        val before = f.current()
        val beforeVersion = assertNotNull(before.version)
        val pending = f.pending(original, 201, beforeVersion.revision + 1)
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(pending)))
        f.reopen()
        val after = f.current()
        assertEquals(before.draft, after.draft)
        assertEquals(before.entry!!.position, after.entry!!.position)
        assertEquals(original.position, after.candidates.single().position)
        assertEquals(pending, after.candidates.single())
        val afterVersion = assertNotNull(after.version)
        assertEquals(beforeVersion.lastOrdinal, afterVersion.lastOrdinal)
        val company =
            pending.copy(
                pendingEdit =
                    pending.pendingEdit!!.copy(
                        source =
                            CollaborationSpaceInboxEditSource.Company(
                                f.access.companyId,
                                f.conversation,
                                historyId(202),
                            ),
                        publishedAtRevision = afterVersion.revision + 1,
                    )
            )
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(company)))
        f.reopen()
        assertEquals(company, f.current().candidates.single())
    }

    @Test
    fun pendingEditMetadataDoesNotDuplicateTheMaximumSizeBody() = withDatabase { f ->
        val original = f.add(101)
        val maximum = "é".repeat(16_384)
        assertTrue(validCollaborationText(maximum))
        f.type(maximum)
        val pending =
            f.pending(original, 201, f.current().version!!.revision + 1).let {
                it.copy(preview = it.preview!!.copy(text = maximum))
            }
        assertFalse(
            Json.encodeToString(InboxPendingEditDto(pending.pendingEdit!!)).contains(maximum)
        )
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(pending)))
        f.reopen()
        val current = f.current()
        assertEquals(maximum, current.draft!!.value.text)
        val newest = assertNotNull(assertNotNull(current.entry).newest)
        assertEquals(maximum, assertNotNull(newest.preview).text)
        assertEquals(pending.pendingEdit, newest.pendingEdit)
    }

    @Test
    fun replayAndReceiptUpdatesKeepThePendingIntentAndPlacement() = withDatabase { f ->
        val original = f.add(101)
        val pending = f.pending(original, 201, f.current().version!!.revision + 1)
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(pending)))
        val before = f.rawInbox()
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(pending)))
        assertEquals(before, f.rawInbox())
        val confirmed =
            pending.copy(
                preview = pending.preview!!.copy(delivery = CollaborationMessageDelivery.SENT)
            )
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(confirmed)))
        assertEquals(pending.pendingEdit, f.current().entry!!.newest!!.pendingEdit)
        assertEquals(original.position, f.current().entry!!.newest!!.position)
        val unchanged = f.rawInbox()
        val staleText =
            confirmed.copy(preview = confirmed.preview!!.copy(text = "Stale server text"))
        assertIs<AppResult.Failed>(f.index().publish(f.local(), f.proposal(staleText)))
        assertEquals(unchanged, f.rawInbox())
    }

    @Test
    fun anExistingPendingOperationCannotChangeItsTargetOrPublicationStamp() = withDatabase { f ->
        val original = f.add(101)
        val pending = f.pending(original, 201, f.current().version!!.revision + 1)
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(pending)))
        val reference = pending.pendingEdit!!
        val before = f.rawInbox()
        for (changed in
            listOf(
                reference.copy(target = reference.target.copy(eventId = "\$another")),
                reference.copy(target = reference.target.copy(sender = "@other:example.test")),
                reference.copy(createdAt = reference.createdAt + 1),
                reference.copy(publishedAtRevision = reference.publishedAtRevision + 1),
            )) {
            assertIs<AppResult.Failed>(
                f.index().publish(f.local(), f.proposal(pending.copy(pendingEdit = changed)))
            )
            assertEquals(before, f.rawInbox())
        }
        val next = f.pending(original, 202, f.current().version!!.revision + 2)
        assertIs<AppResult.Failed>(f.index().publish(f.local(), f.proposal(next)))
        assertEquals(before, f.rawInbox())
    }

    @Test
    fun aLaterEditUsesPublicationOrderWithoutReorderingTheChat() = withDatabase { f ->
        val original = f.add(101)
        f.add(102, conversation = 2, timestamp = 4000)
        val order = f.page().entries.map { it.conversationId }
        val first = f.pending(original, 201, f.current().version!!.revision + 1)
        val captured = f.proposal(first)
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), captured))
        val second =
            f.pending(original, 202, f.current().version!!.revision + 1).let {
                it.copy(pendingEdit = it.pendingEdit!!.copy(createdAt = 500))
            }
        val ordinal = f.current().version!!.lastOrdinal
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(second)))
        assertEquals(AppResult.Success(false), f.index().publish(f.local(), captured))
        assertEquals(order, f.page().entries.map { it.conversationId })
        assertEquals(original.position, f.current().candidates.single().position)
        assertEquals(ordinal, f.current().version!!.lastOrdinal)
        val before = f.rawInbox()
        assertIs<AppResult.Failed>(f.index().publish(f.local(), f.proposal(first)))
        assertEquals(before, f.rawInbox())
    }

    @Test
    fun clearingProvenanceLeavesDraftAndOriginalPlacementIntact() = withDatabase { f ->
        val original = f.add(101)
        f.type("Independent draft")
        val pending = f.pending(original, 201, f.current().version!!.revision + 1)
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(pending)))
        val before = f.current()
        val retained =
            pending.copy(
                pendingEdit = null,
                preview = pending.preview!!.copy(text = "Retained applied edit"),
            )
        assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(retained)))
        f.reopen()
        val after = f.current()
        assertEquals(retained, after.candidates.single())
        assertEquals(before.draft, after.draft)
        assertEquals(before.entry!!.position, after.entry!!.position)
        assertEquals(before.version!!.lastOrdinal, after.version!!.lastOrdinal)
        assertEquals(original.position, after.candidates.single().position)
    }

    @Test
    fun hiddenAndDeletedPendingPreviewsCannotReviveByKeepingTheirStamp() = withDatabase { f ->
        for ((offset, kind) in
            listOf<CollaborationSpaceInboxContent?>(null, CollaborationSpaceInboxContent.DELETED)
                .withIndex()) {
            val original = f.add(101 + offset)
            val pending = f.pending(original, 201 + offset, f.current().version!!.revision + 1)
            assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(pending)))
            val hidden =
                pending.copy(preview = kind?.let { pending.preview!!.copy(kind = it, text = null) })
            assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(hidden)))
            val before = f.rawInbox()
            assertIs<AppResult.Failed>(f.index().publish(f.local(), f.proposal(pending)))
            assertEquals(before, f.rawInbox())
            val found =
                f.value(f.index().activities(f.local(), historyId(1), listOf(original.source)))
                    .values
                    .single()
            assertEquals(hidden, found)
        }
    }

    @Test
    fun lostPublicationReplyPreservesItsEditAndCannotOverwriteALaterOperation() =
        withDatabase { f ->
            val original = f.add(101)
            val first = f.pending(original, 201, f.current().version!!.revision + 1)
            val proposal = f.proposal(first)
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        throw CancellationException("caller disposed after edit publication")
                    }
                }
            assertFailsWith<CancellationException> { f.index(store).publish(f.local(), proposal) }
            f.reopen()
            assertEquals(first, f.current().candidates.single())
            val second = f.pending(original, 202, f.current().version!!.revision + 1)
            assertEquals(AppResult.Success(true), f.index().publish(f.local(), f.proposal(second)))
            val before = f.rawInbox()
            assertEquals(AppResult.Success(false), f.index().publish(f.local(), proposal))
            assertEquals(before, f.rawInbox())
            assertEquals(second, f.current().candidates.single())
        }

    private fun CollaborationHistoryFixture.local() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            historyId(9000),
        )

    private fun CollaborationHistoryFixture.index(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceInboxRepository(store, cipher, keys)

    private fun <T> CollaborationHistoryFixture.value(result: AppResult<T>) =
        assertIs<AppResult.Success<T>>(result).value

    private suspend fun CollaborationHistoryFixture.current(conversation: Int = 1) =
        value(index().conversation(local(), historyId(conversation), 33))

    private suspend fun CollaborationHistoryFixture.page() =
        assertIs<CollaborationSpaceInboxPage.Items>(value(index().observePage(local()).first()))

    private suspend fun CollaborationHistoryFixture.rawInbox() =
        database.replicas
            .observe(spaceHistoryScope(local()), spaceInboxPartition(local()), limit = 200)
            .first()

    private suspend fun CollaborationHistoryFixture.proposal(
        value: CollaborationSpaceInboxActivity,
        conversation: Int = 1,
    ): CollaborationSpaceInboxPublication {
        val current = current(conversation)
        val candidates =
            (current.candidates.filter { it.source != value.source } + value).filter {
                it.preview != null
            }
        val newest =
            candidates.maxWithOrNull { a, b ->
                compareCollaborationSpaceInboxPositions(a.position, b.position)
            }
        return CollaborationSpaceInboxPublication(
            current.version,
            maxOf(current.version?.lastOrdinal ?: 0, value.position.ordinal),
            collaborationSpaceInboxEntry(
                historyId(conversation),
                current.entry?.createdAt ?: 0,
                current.draft,
                newest,
            ),
            activities = listOf(value),
        )
    }

    private suspend fun CollaborationHistoryFixture.add(
        id: Int,
        conversation: Int = 1,
        timestamp: Long = 1000,
    ): CollaborationSpaceInboxActivity {
        val current = current(conversation)
        val value =
            CollaborationSpaceInboxActivity(
                CollaborationSpaceInboxSource.GroupOriginal(historyId(id)),
                CollaborationSpaceInboxPosition(timestamp, (current.version?.lastOrdinal ?: 0) + 1),
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.TEXT,
                    "Original $id",
                    timestamp,
                    CollaborationMessageDelivery.PENDING,
                ),
            )
        assertEquals(
            AppResult.Success(true),
            index().publish(local(), proposal(value, conversation)),
        )
        return value
    }

    private fun CollaborationHistoryFixture.pending(
        original: CollaborationSpaceInboxActivity,
        operation: Int,
        revision: Long,
    ): CollaborationSpaceInboxActivity {
        val id = (original.source as CollaborationSpaceInboxSource.GroupOriginal).id
        val target =
            CollaborationEditTarget(
                room,
                "\$original-$id",
                id,
                "@author:example.test",
                "a".repeat(64),
                1000,
                1000,
            )
        return original.copy(
            preview = original.preview!!.copy(text = "Edited $operation"),
            pendingEdit =
                CollaborationSpaceInboxPendingEdit(
                    CollaborationSpaceInboxEditSource.Group(historyId(operation)),
                    target,
                    2000,
                    revision,
                ),
        )
    }

    private suspend fun CollaborationHistoryFixture.type(text: String) {
        val current = current()
        val ordinal = current.version!!.lastOrdinal + 1
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    historyId(1),
                    historyId(20_000 + ordinal.toInt()),
                    text,
                    Instant.fromEpochMilliseconds(3000),
                ),
                CollaborationSpaceInboxPosition(3000, ordinal),
            )
        val entry =
            collaborationSpaceInboxEntry(
                historyId(1),
                current.entry!!.createdAt,
                draft,
                current.candidates.firstOrNull(),
            )
        assertEquals(
            AppResult.Success(true),
            index()
                .publish(
                    local(),
                    CollaborationSpaceInboxPublication(current.version, ordinal, entry, draft),
                ),
        )
    }

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
