package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.CollaborationMessageEditWindowMillis
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Real encrypted SQLite/IndexedDB evidence survives late delivery without becoming visible text.
 */
abstract class CollaborationEditEvidenceStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun lateProviderAcceptanceSurvivesRestartWithoutReplacingOriginalOrBecomingItsReceipt() =
        withDatabase { f ->
            val original = f.event()
            val accepted = edit(original)
            val late =
                edit(original, 2)
                    .copy(timestamp = original.timestamp + CollaborationMessageEditWindowMillis)
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(null), f.evidence(late))
            assertEquals(AppResult.Success(emptyList()), f.operations(late))
            assertNull(f.keys.read())
            f.merge(original)
            f.edits(accepted, late)
            val displayed = assertNotNull(f.lookup())
            assertEquals(accepted, displayed.latestEdit?.edit)
            assertEquals(original.text, displayed.text)
            assertEquals(listOf(original.eventId), displayed.eventIds)
            val proof =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationEditEvidence?>>(f.evidence(late)).value
                )
            assertEquals(evidence(late, eligible = false), proof)
            assertEquals(AppResult.Success(listOf(proof)), f.operations(late))
            assertEquals(
                AppResult.Success(null),
                f.repository.getEventFact(f.access, f.conversation, f.room, late.eventId),
            )
            assertNull(f.lookup(late.eventId))
            assertEquals(
                AppResult.Success(
                    CollaborationReplacementEventFact(
                        late.eventId,
                        late.sender,
                        late.timestamp,
                        false,
                    )
                ),
                f.repository.getReplacementFact(f.access, f.conversation, f.room, late.eventId),
            )
            val snapshot = f.raw()
            f.reopen()
            assertEquals(AppResult.Success(proof), f.evidence(late))
            assertEquals(AppResult.Success(listOf(proof)), f.operations(late))
            assertEquals(displayed, f.lookup())
            assertEquals(snapshot, f.raw())
            assertIs<AppResult.Failed>(
                f.repository.getEditEvidence(
                    f.access,
                    f.conversation,
                    "!other:example.test",
                    late.eventId,
                )
            )
        }

    @Test
    fun earlierOriginalAliasNarrowsEligibilityWithoutLosingAcceptedTransportIdentity() =
        withDatabase { f ->
            val early = f.event()
            val later = early.copy(eventId = "\$later", timestamp = early.timestamp + 600_000)
            val replacement = edit(later).copy(timestamp = early.timestamp + 1_100_000)
            f.merge(later)
            f.edits(replacement)
            assertEquals(AppResult.Success(evidence(replacement)), f.evidence(replacement))
            f.merge(early)
            assertNull(f.lookup()?.latestEdit)
            val before = f.raw()
            f.edits(replacement)
            assertEquals(before, f.raw())
            f.reopen()
            val expected = evidence(replacement, eligible = false)
            assertEquals(AppResult.Success(expected), f.evidence(replacement))
            assertEquals(AppResult.Success(listOf(expected)), f.operations(replacement))
            assertEquals(later.timestamp, f.lookup()?.timestamp)
        }

    @Test
    fun bothRedactionOrdersAndOriginalRedactionKeepProofWithoutResurrection() = withDatabase { f ->
        for (mode in 0..2) {
            val original = f.event(mode + 1)
            val replacement = edit(original, mode + 1)
            if (mode == 0)
                f.merge(
                    CollaborationHistoryChange.Redacted(replacement.eventId, replacement.sender)
                )
            f.merge(original)
            f.edits(replacement)
            if (mode == 1)
                f.merge(
                    CollaborationHistoryChange.Redacted(replacement.eventId, replacement.sender)
                )
            if (mode == 2)
                f.merge(CollaborationHistoryChange.Redacted(original.eventId, original.sender))
            f.reopen()
            val expected = evidence(replacement, eligible = false, redacted = true)
            assertEquals(AppResult.Success(expected), f.evidence(replacement))
            assertEquals(AppResult.Success(listOf(expected)), f.operations(replacement))
            assertEquals(
                AppResult.Success(null),
                f.repository.getEventFact(f.access, f.conversation, f.room, replacement.eventId),
            )
            assertNull(f.lookup(original.eventId)?.latestEdit)
            val before = f.raw()
            f.edits(replacement)
            assertEquals(before, f.raw())
            assertEquals(AppResult.Success(expected), f.evidence(replacement))
        }
    }

    @Test
    fun conflictingRejectedInputsRollbackTheWholeBatchAndCannotBecomeOriginalMessages() =
        withDatabase { f ->
            val original = f.event()
            val other = f.event(2)
            val late =
                edit(original)
                    .copy(timestamp = original.timestamp + CollaborationMessageEditWindowMillis)
            f.merge(original, other)
            f.edits(late)
            val before = f.raw()
            for (changed in
                listOf(
                    late.copy(text = "Changed while reusing the canonical fingerprint"),
                    late.copy(sender = "@other:example.test"),
                    late.copy(timestamp = late.timestamp + 1),
                    late.copy(targetEventId = other.eventId),
                    late.copy(contentHash = "e".repeat(64)),
                    late.copy(operationId = historyId(8000)),
                    late.copy(originalLogicalId = other.logicalId),
                    late.copy(replyToEventId = "\$quote"),
                    late.copy(forwarded = true),
                )) {
                assertIs<AppResult.Failed>(
                    f.repository.mergeEdits(
                        f.access,
                        f.conversation,
                        f.room,
                        listOf(edit(original, 3), changed),
                    )
                )
                assertEquals(before, f.raw())
            }
            assertIs<AppResult.Failed>(
                f.repository.merge(
                    f.access,
                    f.conversation,
                    f.room,
                    listOf(f.event(4).copy(eventId = late.eventId)),
                )
            )
            assertIs<AppResult.Failed>(
                f.repository.merge(
                    f.access,
                    f.conversation,
                    f.room,
                    listOf(CollaborationHistoryChange.Redacted(late.eventId, "@other:example.test")),
                )
            )
            assertEquals(before, f.raw())
            assertNull(f.lookup()?.latestEdit)
        }

    @Test
    fun operationLookupsStaySenderScopedAndRejectMoreThan128Identities() = withDatabase { f ->
        val original = f.event()
        val operation = historyId(7000)
        val values =
            (1..129).map {
                edit(original, it)
                    .copy(
                        operationId = operation,
                        timestamp = original.timestamp + CollaborationMessageEditWindowMillis + it,
                    )
            }
        f.merge(original)
        for (batch in values.take(128).chunked(16)) f.edits(*batch.toTypedArray())
        val foreign =
            edit(original, 130).copy(sender = "@other:example.test", operationId = operation)
        f.edits(foreign)
        assertEquals(
            128,
            assertIs<AppResult.Success<List<CollaborationEditEvidence>>>(
                    f.operations(values.first())
                )
                .value
                .size,
        )
        assertEquals(
            AppResult.Success(listOf(evidence(foreign, eligible = false))),
            f.operations(foreign),
        )
        assertEquals(
            AppResult.Success(emptyList()),
            f.operations(values.first().copy(operationId = historyId(7001))),
        )
        f.edits(values.last())
        f.reopen()
        assertIs<AppResult.Failed>(f.operations(values.first()))
        assertEquals(
            AppResult.Success(evidence(values.last(), eligible = false)),
            f.evidence(values.last()),
        )
        assertNull(f.lookup()?.latestEdit)
    }

    @Test
    fun evidenceReadsDrainCancellationAndRejectCheckpointOrAccountReplacement() =
        withDatabase { f ->
            val original = f.event()
            val replacement = edit(original)
            f.merge(original)
            f.edits(replacement)
            for (operation in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>()
                var active = 0
                val waiting =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun document(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): ReplicaDocument? {
                            active++
                            try {
                                entered.complete(Unit)
                                awaitCancellation()
                            } finally {
                                active--
                            }
                        }
                    }
                coroutineScope {
                    val job = async {
                        val repository = f.repository(waiting)
                        if (operation)
                            repository.getEditOperationEvidence(
                                f.access,
                                f.conversation,
                                f.room,
                                replacement.sender,
                                replacement.operationId!!,
                            )
                        else
                            repository.getEditEvidence(
                                f.access,
                                f.conversation,
                                f.room,
                                replacement.eventId,
                            )
                    }
                    entered.await()
                    job.cancelAndJoin()
                }
                assertEquals(0, active)
                for (logout in listOf(false, true)) {
                    var checkpoints = 0
                    val changed =
                        object : ReplicaStore by f.database.replicas {
                            override suspend fun checkpoint(
                                scope: ReplicaScope,
                                partition: String,
                            ): ReplicaCheckpoint? {
                                if (++checkpoints == 2) {
                                    if (logout)
                                        f.activate(
                                            f.access.copy(
                                                sessionId = "replaced",
                                                accountId = historyId(777),
                                            )
                                        )
                                    else f.merge(f.event(if (operation) 4 else 3))
                                }
                                return f.database.replicas.checkpoint(scope, partition)
                            }
                        }
                    val repository = f.repository(changed)
                    val result =
                        if (operation)
                            repository.getEditOperationEvidence(
                                f.access,
                                f.conversation,
                                f.room,
                                replacement.sender,
                                replacement.operationId!!,
                            )
                        else
                            repository.getEditEvidence(
                                f.access,
                                f.conversation,
                                f.room,
                                replacement.eventId,
                            )
                    assertIs<AppResult.Failed>(result)
                    f.activate()
                }
            }
            assertEquals(AppResult.Success(evidence(replacement)), f.evidence(replacement))
        }

    @Test
    fun substitutedQueriesAndTamperedPointRecordsCannotPublishPartialEvidence() =
        withDatabase { f ->
            val original = f.event()
            val first = edit(original)
            val second = edit(original, 2)
            f.merge(original)
            f.edits(first, second)
            val snapshot = f.raw()
            val proofs = snapshot.documents.filter { it.id.startsWith("p-") }
            assertEquals(2, proofs.size)
            val substituted =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) = flowOf(ReplicaQueryPage(snapshot.checkpoint, listOf(proofs)))
                }
            assertIs<AppResult.Failed>(
                f.repository(substituted)
                    .getEditOperationEvidence(
                        f.access,
                        f.conversation,
                        f.room,
                        first.sender,
                        first.operationId!!,
                    )
            )
            for (mode in 0..2) {
                val corrupted =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun document(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): ReplicaDocument? {
                            if (!id.startsWith("p-"))
                                return f.database.replicas.document(scope, partition, id)
                            val value = proofs.single { it.id == id }
                            return when (mode) {
                                0 -> proofs.single { it.id != id }
                                1 -> value.copy(tags = setOf("substituted-index"))
                                else -> value.copy(payload = "corrupted")
                            }
                        }
                    }
                assertIs<AppResult.Failed>(
                    f.repository(corrupted)
                        .getEditEvidence(f.access, f.conversation, f.room, first.eventId)
                )
            }
            assertEquals(snapshot, f.raw())
            assertEquals(AppResult.Success(evidence(first)), f.evidence(first))
        }

    private fun edit(original: CollaborationHistoryChange.Text, index: Int = 1) =
        CollaborationMessageEdit(
            "\$proof-edit-$index",
            original.sender,
            original.eventId,
            original.timestamp + index * 100L,
            "Revised fictional message $index",
            (index + 500).toString(16).padStart(64, '0'),
            historyId(1000 + index),
            original.logicalId,
            original.replyToEventId,
            original.forwarded,
        )

    private fun evidence(
        edit: CollaborationMessageEdit,
        eligible: Boolean = true,
        redacted: Boolean = false,
    ) =
        CollaborationEditEvidence(
            edit.eventId,
            edit.sender,
            edit.targetEventId,
            edit.timestamp,
            edit.contentHash,
            edit.operationId,
            eligible,
            redacted,
        )

    private suspend fun CollaborationHistoryFixture.evidence(edit: CollaborationMessageEdit) =
        repository.getEditEvidence(access, conversation, room, edit.eventId)

    private suspend fun CollaborationHistoryFixture.operations(edit: CollaborationMessageEdit) =
        repository.getEditOperationEvidence(
            access,
            conversation,
            room,
            edit.sender,
            edit.operationId!!,
        )

    private suspend fun CollaborationHistoryFixture.edits(vararg values: CollaborationMessageEdit) {
        assertEquals(
            AppResult.Success(true),
            repository.mergeEdits(access, conversation, room, values.toList()),
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
