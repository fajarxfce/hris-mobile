package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationHistoryEditSnapshotStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absentSourcesReturnEveryRequestedEmptyOperationWithoutCreatingKeys() = withDatabase { f ->
        val operations = listOf(f.operation(), f.operation(2))
        val result = f.snapshot(operations)
        assertNull(result.revision)
        assertNull(result.events.single().original)
        assertEquals(
            operations.map { CollaborationHistoryEditOperationEvidence(it, emptyList()) },
            result.editOperations,
        )
        assertNull(f.base.keys.read())
        assertNull(f.checkpoint())
    }

    @Test
    fun invalidAndOversizedOperationQueriesFailBeforeKeyAcquisition() = withDatabase { f ->
        for (operations in
            listOf(
                List(17) { f.operation(it + 1) },
                listOf(f.operation(), f.operation()),
                listOf(f.operation().copy(sender = "invalid sender")),
                listOf(f.operation().copy(id = "not-an-operation")),
            )) assertIs<AppResult.Failed>(f.read(operations))
        assertNull(f.base.keys.read())
        assertNull(f.checkpoint())
    }

    @Test
    fun originalsAndReplacementResultsRemainCoherentAcrossReopen() = withDatabase { f ->
        f.merge(f.event)
        val edit = f.edit()
        f.edits(edit)
        val before = f.checkpoint()
        val result = f.snapshot(listOf(f.operation(2), f.operation()))
        assertEquals(edit, result.events.single().original!!.latestEdit!!.edit)
        assertTrue(result.editOperations.first().events.isEmpty())
        assertEquals(edit.eventId, result.editOperations.last().events.single().eventId)
        assertTrue(result.editOperations.last().events.single().eligible)
        assertEquals(before, f.checkpoint())
        f.base.reopen()
        assertEquals(result, f.snapshot(listOf(f.operation(2), f.operation())))
    }

    @Test
    fun actualCompanyAndGroupNamespacesNeverBorrowTheOtherCopiesEvidence() = withDatabase { f ->
        f.merge(f.event)
        f.edits(f.edit())
        val other = Fixture(f.base, !f.company)
        other.merge(f.event)
        other.edits(f.edit().copy(text = "Other physical history", contentHash = "f".repeat(64)))
        val own = f.snapshot(listOf(f.operation()))
        val foreign = other.snapshot(listOf(f.operation()))
        assertEquals(f.edit().contentHash, own.editOperations.single().events.single().contentHash)
        assertEquals("f".repeat(64), foreign.editOperations.single().events.single().contentHash)
        assertNotEquals(own.events, foreign.events)
    }

    @Test
    fun acceptedIneligibleAndRedactedReplacementsKeepTheirActualOutcomes() = withDatabase { f ->
        f.merge(f.event)
        val accepted = f.edit()
        val rejected = f.edit(2).copy(timestamp = f.event.timestamp + 900_000)
        val removed = f.edit(3)
        f.edits(accepted, rejected, removed)
        f.merge(CollaborationHistoryChange.Redacted(removed.eventId, removed.sender))
        val result = f.snapshot(listOf(f.operation(), f.operation(2), f.operation(3)))
        val facts = result.editOperations.map { it.events.single() }
        assertTrue(facts[0].eligible)
        assertFalse(facts[1].eligible)
        assertFalse(facts[1].redacted)
        assertTrue(facts[2].redacted)
        assertEquals(accepted, result.events.single().original!!.latestEdit!!.edit)
    }

    @Test
    fun anOperationFromAnotherSenderDoesNotReturnMatchingIdsFromTheRealAuthor() =
        withDatabase { f ->
            f.merge(f.event)
            f.edits(f.edit())
            val other = f.operation().copy(sender = "@another:example.test")
            val result = f.snapshot(listOf(other, f.operation()))
            assertEquals(other, result.editOperations.first().operation)
            assertTrue(result.editOperations.first().events.isEmpty())
            assertEquals(1, result.editOperations.last().events.size)
        }

    @Test
    fun existingSnapshotsWithoutOperationQueriesPerformNoNewIndexRead() = withDatabase { f ->
        f.merge(f.event)
        f.edits(f.edit())
        f.store =
            object : ReplicaStore by f.base.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> =
                    error("No operation index read without a requested operation")
            }
        val result = f.snapshot(emptyList())
        assertTrue(result.editOperations.isEmpty())
        assertEquals(f.edit(), result.events.single().original!!.latestEdit!!.edit)
    }

    @Test
    fun aChangedCheckpointBetweenOriginalsAndOperationsReturnsNoMixedSnapshot() =
        withDatabase { f ->
            f.merge(f.event)
            f.edits(f.edit())
            var queryCount = 0
            f.store =
                object : ReplicaStore by f.base.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) =
                        flow {
                                queries.size.let { assertEquals(1, it) }
                                f.edits(f.edit(2))
                                emit(
                                    f.base.database.replicas
                                        .observeQueries(scope, partition, queries)
                                        .first()
                                )
                            }
                            .onStart { queryCount++ }
                }
            assertEquals(AppResult.Success(null), f.read(listOf(f.operation())))
            assertEquals(1, queryCount)
        }

    @Test
    fun aNewCheckpointAfterAnOperationQueryCannotPublishTheOlderRevision() = withDatabase { f ->
        f.merge(f.event)
        f.edits(f.edit())
        f.store =
            object : ReplicaStore by f.base.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    val page =
                        f.base.database.replicas.observeQueries(scope, partition, queries).first()
                    f.merge(f.base.event(2))
                    emit(page)
                }
            }
        assertEquals(AppResult.Success(null), f.read(listOf(f.operation())))
    }

    @Test
    fun missingHistoryKeysNeverReplaceRetainedCiphertextOrCreateNewKeys() = withDatabase { f ->
        f.merge(f.event)
        f.edits(f.edit())
        val key = f.base.keys.read()!!
        val before = f.checkpoint()
        f.base.keys.write(null)
        assertIs<AppResult.Failed>(f.read(listOf(f.operation())))
        assertNull(f.base.keys.read())
        assertEquals(before, f.checkpoint())
        f.base.keys.write(key)
        assertEquals(1, f.snapshot(listOf(f.operation())).editOperations.single().events.size)
    }

    @Test
    fun pendingCancellationJoinsTheLastReadWithoutReturningPrivateContent() = withDatabase { f ->
        f.merge(f.event)
        f.edits(f.edit())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store =
            object : ReplicaStore by f.base.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    val page =
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            f.base.database.replicas
                                .observeQueries(scope, partition, queries)
                                .first()
                        }
                    emit(page)
                }
            }
        coroutineScope {
            var reported = false
            val reader = launch {
                f.read(listOf(f.operation()))
                reported = true
            }
            try {
                entered.await()
                reader.cancel()
                yield()
                assertFalse(reader.isCompleted)
            } finally {
                release.complete(Unit)
            }
            reader.join()
            assertTrue(reader.isCancelled)
            assertFalse(reported)
        }
        f.store = null
        assertEquals(1, f.snapshot(listOf(f.operation())).editOperations.single().events.size)
    }

    @Test
    fun operationQueriesCaptureMutableCallerInputsBeforeSuspending() = withDatabase { f ->
        f.merge(f.event)
        f.edits(f.edit())
        val operations = mutableListOf(f.operation())
        val requestedIds = mutableListOf(f.event.eventId)
        f.store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun workingDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    ids: List<String>,
                ): ReplicaPage {
                    operations.clear()
                    requestedIds.clear()
                    return f.base.database.replicas.workingDocuments(scope, partition, ids)
                }
            }
        val result = assertNotNull(success(f.read(operations, requestedIds)))
        assertEquals(f.event.eventId, result.events.single().eventId)
        assertEquals(f.operation(), result.editOperations.single().operation)
        assertEquals(1, result.editOperations.single().events.size)
    }

    @Test
    fun allOneHundredTwentyEightAliasesRemainAvailableWithoutTruncation() = withDatabase { f ->
        f.merge(f.event)
        for (batch in (1..128).map { f.edit().copy(eventId = "${'$'}alias-$it") }.chunked(16)) f
            .edits(*batch.toTypedArray())
        val events = f.snapshot(listOf(f.operation())).editOperations.single().events
        assertEquals(128, events.size)
        assertEquals((1..128).map { "${'$'}alias-$it" }.toSet(), events.map { it.eventId }.toSet())
    }

    @Test
    fun sixteenDistinctOperationsRetainTheRequestedOrderAndExactIdentity() = withDatabase { f ->
        f.merge(f.event)
        f.edits(*(1..16).map(f::edit).toTypedArray())
        val operations = (16 downTo 1).map(f::operation)
        val result = f.snapshot(operations)
        assertEquals(operations, result.editOperations.map { it.operation })
        for (operation in result.editOperations) {
            val event = operation.events.single()
            assertEquals(operation.operation.id, event.operationId)
            assertEquals(operation.operation.sender, event.sender)
        }
        assertEquals(f.edit(16), result.events.single().original!!.latestEdit!!.edit)
    }

    @Test
    fun accountReplacementDuringTheOperationReadReturnsNoFormerAccountsContent() =
        withDatabase { f ->
            f.merge(f.event)
            f.edits(f.edit())
            val replacement =
                f.base.access.copy(sessionId = "replacement", accountId = historyId(9999))
            f.store =
                object : ReplicaStore by f.base.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) = flow {
                        f.base.activate(replacement)
                        emit(
                            f.base.database.replicas
                                .observeQueries(scope, partition, queries)
                                .first()
                        )
                    }
                }
            val result = f.read(listOf(f.operation()))
            if (result is AppResult.Success) assertNull(result.value)
        }

    @Test
    fun overflowingOneOperationIsAnExplicitFailureInsteadOfPartialEvidence() = withDatabase { f ->
        f.merge(f.event)
        for (batch in (1..129).map { f.edit().copy(eventId = "${'$'}alias-$it") }.chunked(16)) f
            .edits(*batch.toTypedArray())
        assertIs<AppResult.Failed>(f.read(listOf(f.operation())))
    }

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value

    private class Fixture(val base: CollaborationSpaceHistoryFixture, val company: Boolean) {
        val event = base.event()
        val source =
            CollaborationSpaceHistoryLocation.Original(
                base.legacyAccess,
                base.conversation,
                base.room,
            )
        var store: ReplicaStore? = null

        fun companyRepository(replicas: ReplicaStore = base.database.replicas) =
            EncryptedCollaborationHistoryRepository(replicas, base.cipher, base.keys)

        fun operation(index: Int = 1) =
            CollaborationHistoryEditOperation(event.sender, historyId(20_000 + index))

        fun edit(index: Int = 1) =
            CollaborationMessageEdit(
                "${'$'}edit-$index",
                event.sender,
                event.eventId,
                event.timestamp + index * 100L,
                "Edited content $index",
                index.toString(16).padStart(64, '0'),
                operation(index).id,
                event.logicalId,
                event.replyToEventId,
                event.forwarded,
            )

        suspend fun read(
            operations: List<CollaborationHistoryEditOperation>,
            ids: List<String> = listOf(event.eventId),
        ) =
            if (company)
                companyRepository(store ?: base.database.replicas).snapshot(source, ids, operations)
            else
                base
                    .repository(store ?: base.database.replicas)
                    .snapshot(base.access, base.conversation, base.room, ids, operations)

        suspend fun snapshot(operations: List<CollaborationHistoryEditOperation>) =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSpaceHistorySnapshot?>>(read(operations))
                    .value
            )

        suspend fun merge(vararg events: CollaborationHistoryChange) {
            val result =
                if (company)
                    companyRepository()
                        .merge(source.access, source.conversationId, source.roomId, events.toList())
                else
                    base
                        .repository()
                        .merge(base.access, base.conversation, base.room, events.toList())
            assertEquals(AppResult.Success(true), result)
        }

        suspend fun edits(vararg edits: CollaborationMessageEdit) {
            val result =
                if (company)
                    companyRepository()
                        .mergeEdits(
                            source.access,
                            source.conversationId,
                            source.roomId,
                            edits.toList(),
                        )
                else
                    base
                        .repository()
                        .mergeEdits(base.access, base.conversation, base.room, edits.toList())
            assertEquals(AppResult.Success(true), result)
        }

        suspend fun checkpoint() =
            base.database.replicas.checkpoint(
                spaceHistoryScope(base.access),
                if (company) historyPartition(source.access, source.conversationId)
                else spaceHistoryPartition(base.access, base.conversation),
            )
    }

    private fun withDatabase(block: suspend (Fixture) -> Unit) = runTest {
        for (company in listOf(false, true)) {
            val base = CollaborationSpaceHistoryFixture(databaseOwner())
            try {
                base.activate()
                block(Fixture(base, company))
            } finally {
                base.close()
            }
        }
    }
}
