package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationEditTargetStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun originalContextAndAcceptedTextSurviveRestartWithoutChangingOriginalIdentity() =
        withDatabase { f ->
            val original = f.event().copy(replyToEventId = "\$quoted", forwarded = true)
            f.merge(original)
            val candidate = assertNotNull(f.candidate())
            assertEquals(original.eventId, candidate.target.eventId)
            assertEquals(original.logicalId, candidate.target.originalLogicalId)
            assertEquals(original.contentHash, candidate.target.originalContentHash)
            assertEquals(original.replyToEventId, candidate.target.replyToEventId)
            assertTrue(candidate.target.forwarded)
            assertEquals(f.room, candidate.target.roomId)
            val edit =
                CollaborationMessageEdit(
                    "\$edit",
                    original.sender,
                    original.eventId,
                    original.timestamp + 1,
                    " Updated text 🙂\n",
                    "b".repeat(64),
                    historyId(10),
                    original.logicalId,
                    original.replyToEventId,
                    original.forwarded,
                )
            assertEquals(
                AppResult.Success(true),
                f.repository.mergeEdits(f.access, f.conversation, f.room, listOf(edit)),
            )
            f.reopen()
            assertEquals(candidate.copy(text = edit.text), f.candidate())
            assertEquals(original.text, f.lookup()?.text)
            assertNull(f.candidate(edit.eventId))
        }

    @Test
    fun missingHiddenRedactedAndReplacementTargetsCannotReappearInTheEditor() = withDatabase { f ->
        assertNull(f.keys.read())
        assertNull(f.candidate())
        assertNull(f.keys.read())
        val original = f.event()
        f.merge(original, f.event(2))
        assertNotNull(f.candidate())
        assertEquals(
            AppResult.Success(true),
            f.repository.hide(
                f.access,
                f.conversation,
                f.room,
                listOf(
                    CollaborationMessageReference(
                        original.sender,
                        original.eventId,
                        original.logicalId,
                    )
                ),
            ),
        )
        f.merge(CollaborationHistoryChange.Redacted(f.event(2).eventId, original.sender))
        f.reopen()
        assertNull(f.candidate())
        assertNull(f.candidate(f.event(2).eventId))
        assertNotNull(f.lookup())
    }

    @Test
    fun legacyEarliestAliasIsReadWithoutWritingOrExtendingTheEditWindow() = withDatabase { f ->
        val early = f.event()
        val late = early.copy(eventId = "\$retry", timestamp = early.timestamp + 600_000)
        f.merge(late, early)
        val original = assertNotNull(f.lookup())
        val raw = assertNotNull(f.raw().checkpoint)
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { content, index ->
            val codec =
                CollaborationHistoryCodec(
                    f.access,
                    f.conversation,
                    f.room,
                    f.cipher,
                    content,
                    index,
                )
            val checkpoint = codec.checkpoint(raw)
            val revision = raw.revision + 1
            assertTrue(
                f.database.replicas.commit(
                    raw.handle,
                    raw.revision,
                    codec.checkpoint(
                        raw.handle.generation,
                        revision,
                        checkpoint.count,
                        checkpoint.hiddenRules,
                    ),
                    listOf(
                        codec.message(
                            raw.handle.generation,
                            revision,
                            original.copy(firstAcceptedAt = null),
                        )
                    ),
                    true,
                )
            )
        }
        f.reopen()
        val before = f.raw()
        val candidate = assertNotNull(f.candidate())
        assertEquals(late.eventId, candidate.target.eventId)
        assertEquals(late.timestamp, candidate.target.targetAcceptedAt)
        assertEquals(early.timestamp, candidate.target.firstAcceptedAt)
        assertEquals(candidate, f.candidate(late.eventId))
        assertNull(f.lookup()?.firstAcceptedAt)
        assertEquals(before, f.raw())
    }

    @Test
    fun wrongRoomSubstitutedAliasesAndLateCheckpointChangesFailWithoutPublishingText() =
        withDatabase { f ->
            f.merge(f.event(), f.event(2))
            assertIs<AppResult.Failed>(
                f.repository.getEditCandidate(
                    f.access,
                    f.conversation,
                    "!other:test",
                    f.event().eventId,
                )
            )
            val aliases = f.raw().documents.filter { it.id.startsWith("e-") }
            for (kind in 0..2) {
                var reads = 0
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun document(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): ReplicaDocument? {
                            if (kind == 0 && id.startsWith("e-"))
                                return aliases.first { it.id != id }
                            return f.database.replicas.document(scope, partition, id)?.let {
                                if (kind == 1 && id.startsWith("e-")) it.copy(payload = "corrupt")
                                else it
                            }
                        }

                        override suspend fun checkpoint(
                            scope: ReplicaScope,
                            partition: String,
                        ): ReplicaCheckpoint? {
                            if (kind == 2 && ++reads == 2) f.merge(f.event(3))
                            return f.database.replicas.checkpoint(scope, partition)
                        }
                    }
                assertIs<AppResult.Failed>(
                    f.repository(store)
                        .getEditCandidate(f.access, f.conversation, f.room, f.event().eventId)
                )
            }
        }

    @Test
    fun cancellationDrainsAndOwnerReplacementCannotPublishAnOldEditor() = withDatabase { f ->
        f.merge(f.event())
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun document(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): ReplicaDocument? {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        released.complete(Unit)
                    }
                }
            }
        coroutineScope {
            val pending = async {
                f.repository(store)
                    .getEditCandidate(f.access, f.conversation, f.room, f.event().eventId)
            }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(released.isCompleted)
        }
        var reads = 0
        val changed =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    if (++reads == 2)
                        f.activate(
                            f.access.copy(sessionId = "replacement", accountId = historyId(999))
                        )
                    return f.database.replicas.checkpoint(scope, partition)
                }
            }
        assertIs<AppResult.Failed>(
            f.repository(changed)
                .getEditCandidate(f.access, f.conversation, f.room, f.event().eventId)
        )
        f.activate()
        assertNotNull(f.candidate())
    }

    private suspend fun CollaborationHistoryFixture.candidate(eventId: String = event().eventId) =
        assertIs<AppResult.Success<CollaborationEditCandidate?>>(
                repository.getEditCandidate(access, conversation, room, eventId)
            )
            .value

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
