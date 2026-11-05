package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.CollaborationMessageEditWindowMillis
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Actual encrypted storage, checkpoint races and restart on SQLite and IndexedDB. */
abstract class CollaborationMessageEditStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun revisionsSurviveRestartWithoutChangingOriginalReceiptIdentityOrOrdering() =
        withDatabase { f ->
            val original = f.event().copy(replyToEventId = "\$quoted", forwarded = true)
            val alias = original.copy(eventId = "\$retry", timestamp = original.timestamp + 1)
            f.merge(original, alias, f.event(2))
            val before = f.read(limit = 1)
            val identity = assertNotNull(f.lookup())
            val revision = edit(original).copy(targetEventId = alias.eventId)
            f.edits(revision)
            val edited = assertNotNull(f.lookup(alias.eventId))
            assertEquals(identity, edited.copy(latestEdit = null))
            assertEquals(revision, edited.latestEdit?.edit)
            assertEquals(alias.timestamp, edited.latestEdit?.targetAcceptedAt)
            assertEquals(before, f.read(limit = 1))
            assertEquals(
                AppResult.Success(null),
                f.repository.getEventFact(f.access, f.conversation, f.room, revision.eventId),
            )
            assertEquals(
                AppResult.Success(null),
                f.repository.getByEvent(f.access, f.conversation, f.room, revision.eventId),
            )
            assertEquals(
                AppResult.Success(
                    listOf(
                        CollaborationQuotedMessage(alias.eventId, original.sender, revision.text)
                    )
                ),
                f.repository
                    .observeQuotes(f.access, f.conversation, f.room, listOf(alias.eventId))
                    .first(),
            )
            val raw = f.raw()
            f.edits(revision)
            assertEquals(raw, f.raw())
            val key = f.keys.read()
            f.reopen()
            assertEquals(edited, f.lookup())
            assertEquals(key, f.keys.read())
            assertTrue(
                f.raw().documents.all {
                    original.text !in (it.payload ?: "") &&
                        revision.text !in (it.payload ?: "") &&
                        revision.eventId !in it.sortKey
                }
            )
        }

    @Test
    fun olderAndIneligibleRevisionsDoNotReplaceTheNewestAndConflictsRollbackEveryWrite() =
        withDatabase { f ->
            val original = f.event()
            f.merge(original)
            val newest = edit(original, 3)
            f.edits(newest, edit(original, 1), edit(original, 2))
            assertEquals(newest, f.lookup()?.latestEdit?.edit)
            val raw = f.raw()
            f.edits(
                edit(original, 4)
                    .copy(timestamp = original.timestamp + CollaborationMessageEditWindowMillis),
                edit(original, 5).copy(sender = "@other:example.test"),
            )
            assertEquals(raw, f.raw())
            assertIs<AppResult.Failed>(
                f.repository.mergeEdits(
                    f.access,
                    f.conversation,
                    f.room,
                    listOf(edit(original, 6), newest.copy(text = "Conflicting reuse")),
                )
            )
            assertEquals(raw, f.raw())
            assertIs<AppResult.Failed>(
                f.repository.mergeEdits(
                    f.access,
                    f.conversation,
                    f.room,
                    listOf(edit(original, 6).copy(eventId = original.eventId)),
                )
            )
            assertEquals(raw, f.raw())
        }

    @Test
    fun redactionFallsBackAcrossRetainedRevisionsAndNeverBecomesOriginalSendEvidence() =
        withDatabase { f ->
            val original = f.event()
            f.merge(original)
            val edits = (1..3).map { edit(original, it) }
            f.edits(*edits.toTypedArray())
            f.merge(CollaborationHistoryChange.Redacted(edits[2].eventId, original.sender))
            assertEquals(edits[1], f.lookup()?.latestEdit?.edit)
            f.reopen()
            f.edits(edits[2])
            assertEquals(edits[1], f.lookup()?.latestEdit?.edit)
            f.merge(
                CollaborationHistoryChange.Redacted(edits[1].eventId, original.sender),
                CollaborationHistoryChange.Redacted(edits[0].eventId, original.sender),
            )
            assertNull(f.lookup()?.latestEdit)
            assertEquals(original.text, f.lookup()?.text)
            f.reopen()
            f.edits(*edits.toTypedArray())
            assertNull(f.lookup()?.latestEdit)
            assertEquals(listOf(original.eventId), f.lookup()?.eventIds)
        }

    @Test
    fun redactionBeforeReplacementAndOriginalRedactionRemainClosedAcrossReplayAndRestart() =
        withDatabase { f ->
            val original = f.event()
            val early = edit(original)
            f.merge(CollaborationHistoryChange.Redacted(early.eventId, original.sender))
            f.merge(original)
            f.edits(early)
            assertNull(f.lookup()?.latestEdit)
            f.edits(edit(original, 2))
            f.merge(CollaborationHistoryChange.Redacted(original.eventId, original.sender))
            f.reopen()
            f.edits(edit(original, 3), edit(original, 2))
            val deleted = assertNotNull(f.lookup())
            assertTrue(deleted.redacted)
            assertNull(deleted.latestEdit)
            assertNull(deleted.text)
            assertEquals(
                AppResult.Success(emptyList()),
                f.repository
                    .observeQuotes(f.access, f.conversation, f.room, listOf(original.eventId))
                    .first(),
            )
        }

    @Test
    fun localHideRemainsIndependentOfEditsFallbackAndQuotePublication() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val reference =
            CollaborationMessageReference(original.sender, original.eventId, original.logicalId)
        assertEquals(
            AppResult.Success(true),
            f.repository.hide(f.access, f.conversation, f.room, listOf(reference)),
        )
        f.edits(edit(original), edit(original, 2))
        f.merge(CollaborationHistoryChange.Redacted(edit(original, 2).eventId, original.sender))
        f.reopen()
        assertTrue(assertNotNull(f.lookup()).locallyHidden)
        assertEquals(edit(original), f.lookup()?.latestEdit?.edit)
        assertTrue(f.read().messages.isEmpty())
        assertEquals(
            AppResult.Success(emptyList()),
            f.repository
                .observeQuotes(f.access, f.conversation, f.room, listOf(original.eventId))
                .first(),
        )
    }

    @Test
    fun checkpointCompetitorWinsWithoutHalfOfTheLosingRevisionOrPointer() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val winner = edit(original, 2)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    f.edits(winner)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                    )
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(store).mergeEdits(f.access, f.conversation, f.room, listOf(edit(original))),
        )
        assertEquals(winner, f.lookup()?.latestEdit?.edit)
        val revisions = f.raw().documents.filter { it.id.startsWith("r-") }
        assertEquals(1, revisions.size)
        f.edits(edit(original))
        assertEquals(winner, f.lookup()?.latestEdit?.edit)
    }

    @Test
    fun cancellationAndOwnerReplacementBeforeCommitCannotPublishARevision() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val before = f.raw()
        val cancelled =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    throw CancellationException("Cancel before admission")
                }
            }
        assertFailsWith<CancellationException> {
            f.repository(cancelled)
                .mergeEdits(f.access, f.conversation, f.room, listOf(edit(original)))
        }
        assertEquals(before, f.raw())
        val switched =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    f.activate(
                        f.access.copy(sessionId = "foreign-login", accountId = historyId(1234))
                    )
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                    )
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(switched)
                .mergeEdits(f.access, f.conversation, f.room, listOf(edit(original))),
        )
        f.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun tiedServerTimesRecoverByEventIdentityAcrossOpaqueIndexPages() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val edits = (1..65).map { edit(original, it).copy(timestamp = original.timestamp + 100) }
        for (chunk in edits.chunked(16)) f.edits(*chunk.toTypedArray())
        val ordered = edits.sortedByDescending { it.eventId }
        assertEquals(ordered[0], f.lookup()?.latestEdit?.edit)
        f.merge(
            CollaborationHistoryChange.Redacted(ordered[0].eventId, original.sender),
            CollaborationHistoryChange.Redacted(ordered[1].eventId, original.sender),
        )
        assertEquals(ordered[2], f.lookup()?.latestEdit?.edit)
        f.reopen()
        assertEquals(ordered[2], f.lookup()?.latestEdit?.edit)
    }

    @Test
    fun absentTargetWrongRoomAndMalformedBatchesDoNotCreateOrOverwriteOwnedFacts() =
        withDatabase { f ->
            val original = f.event()
            assertIs<AppResult.Failed>(
                f.repository.mergeEdits(f.access, f.conversation, f.room, listOf(edit(original)))
            )
            assertNull(f.keys.read())
            f.merge(original)
            val before = f.raw()
            for (room in listOf("!foreign:example.test", "malformed")) assertIs<AppResult.Failed>(
                f.repository.mergeEdits(f.access, f.conversation, room, listOf(edit(original)))
            )
            for (edits in
                listOf(
                    emptyList(),
                    (1..17).map { edit(original, it) },
                    listOf(edit(original).copy(targetEventId = "\$absent")),
                )) assertIs<AppResult.Failed>(
                f.repository.mergeEdits(f.access, f.conversation, f.room, edits)
            )
            assertEquals(before, f.raw())
        }

    private fun edit(original: CollaborationHistoryChange.Text, index: Int = 1) =
        CollaborationMessageEdit(
            "\$revision-" + index,
            original.sender,
            original.eventId,
            original.timestamp + index * 100L,
            "Revised fictional text " + index,
            (index + 100).toString(16).padStart(64, '0'),
            historyId(1000 + index),
            original.logicalId,
            original.replyToEventId,
            original.forwarded,
        )

    @Test
    fun firstObservedRetryKeepsItsOrderingWhileAnEarlierTargetsEditRemainsValid() =
        withDatabase { f ->
            val early = f.event()
            val late = early.copy(eventId = "\$late", timestamp = early.timestamp + 600_000)
            f.merge(late, early)
            val accepted = edit(early).copy(timestamp = early.timestamp + 1_000)
            f.edits(accepted)
            val stored = assertNotNull(f.lookup())
            assertEquals(late.timestamp, stored.timestamp)
            assertEquals(late.eventId, stored.primaryEventId)
            assertEquals(early.timestamp, stored.firstAcceptedAt)
            assertEquals(accepted, stored.latestEdit?.edit)
            f.reopen()
            assertEquals(stored, f.lookup())
        }

    @Test
    fun newlyAcquiredEarlierAliasesNarrowTheWindowAndReprojectRetainedRevisions() =
        withDatabase { f ->
            val early = f.event()
            val middle = early.copy(eventId = "\$middle", timestamp = early.timestamp + 300_000)
            val late = early.copy(eventId = "\$late", timestamp = early.timestamp + 600_000)
            f.merge(late, middle)
            val safe = edit(middle, 1).copy(timestamp = early.timestamp + 500_000)
            val narrowedOut = edit(late, 2).copy(timestamp = early.timestamp + 1_100_000)
            f.edits(safe, narrowedOut)
            assertEquals(narrowedOut, f.lookup(late.eventId)?.latestEdit?.edit)
            f.merge(early)
            val narrowed = assertNotNull(f.lookup())
            assertEquals(early.timestamp, narrowed.firstAcceptedAt)
            assertEquals(late.timestamp, narrowed.timestamp)
            assertEquals(safe, narrowed.latestEdit?.edit)
            f.edits(narrowedOut, edit(late, 3).copy(timestamp = early.timestamp + 1_000_000))
            f.reopen()
            assertEquals(narrowed, f.lookup())
            val earliest = early.copy(eventId = "\$earliest", timestamp = early.timestamp - 800_000)
            f.merge(earliest)
            assertNull(f.lookup()?.latestEdit)
            assertEquals(earliest.timestamp, f.lookup()?.firstAcceptedAt)
            assertEquals(late.timestamp, f.lookup()?.timestamp)
        }

    @Test
    fun legacyHistoriesIndexEveryRetainedAliasBeforeApplyingAnEdit() = withDatabase { f ->
        val early = f.event()
        val late = early.copy(eventId = "\$late", timestamp = early.timestamp + 600_000)
        f.merge(late, early)
        val original = assertNotNull(f.lookup())
        val raw = assertNotNull(f.raw().checkpoint)
        val key = f.keys.read()
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
        assertNull(f.lookup()?.firstAcceptedAt)
        f.edits(edit(late).copy(timestamp = early.timestamp + 1_000_000))
        assertEquals(original, f.lookup())
        assertNull(f.lookup()?.latestEdit)
        assertEquals(key, f.keys.read())
    }

    private suspend fun CollaborationHistoryFixture.edits(vararg edits: CollaborationMessageEdit) {
        assertEquals(
            AppResult.Success(true),
            repository.mergeEdits(access, conversation, room, edits.toList()),
        )
    }

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
