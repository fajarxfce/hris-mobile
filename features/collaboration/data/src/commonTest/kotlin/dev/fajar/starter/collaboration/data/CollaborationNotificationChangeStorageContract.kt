package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.identity.identityPartition
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.data.readstate.readStatePartition
import dev.fajar.starter.collaboration.data.replica.listPartition
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationNotificationChangeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Actual SQLite/IndexedDB metadata signals. Synthetic payloads deliberately are not decrypted. */
abstract class CollaborationNotificationChangeStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun initialEmptyAndReopenedObservationCreateNoKeyOrRecords() = withDatabase { f ->
        assertEquals(AppResult.Success(Unit), f.changes().first())
        assertNull(f.keys.read())
        assertFalse(f.database.replicas.containsRecords(COLLABORATION_HISTORY_PREFIX))
        f.reopen()
        assertEquals(AppResult.Success(Unit), f.changes().first())
        assertNull(f.keys.read())
    }

    @Test
    fun allFivePublishedSourcesAndTheirRemovalWakeRevalidation() = withDatabase { f ->
        coroutineScope {
            val updates = Channel<AppResult<Unit>>(8)
            val job = launch { f.changes().collect { updates.send(it) } }
            try {
                assertEquals(AppResult.Success(Unit), updates.receive())
                for ((index, partition) in f.partitions().withIndex()) {
                    f.write(partition, index)
                    assertEquals(AppResult.Success(Unit), updates.receive(), partition)
                }
                for (partition in f.partitions()) {
                    assertTrue(f.database.replicas.clear(historyScope(f.access), partition))
                    assertEquals(AppResult.Success(Unit), updates.receive(), partition)
                }
                assertNull(f.keys.read())
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
                updates.close()
            }
        }
    }

    @Test
    fun publicationJournalOtherCompanyAndOtherConversationAreNotWatched() = withDatabase { f ->
        coroutineScope {
            val registrations = MutableStateFlow(emptySet<String>())
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observePublishedCheckpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ) = flow {
                        assertEquals(historyScope(f.access), scope)
                        registrations.update { it + partition }
                        emitAll(f.database.replicas.observePublishedCheckpoint(scope, partition))
                    }
                }
            val updates = Channel<AppResult<Unit>>(8)
            val job = launch { f.changes(store).collect { updates.send(it) } }
            try {
                assertEquals(AppResult.Success(Unit), updates.receive())
                assertEquals(f.partitions().toSet(), registrations.value)
                f.write(NOTIFICATION_PUBLICATION_PARTITION, 11)
                f.write(historyPartition(f.access, historyId(9200)), 12)
                f.write(listPartition(f.access.copy(companyId = historyId(9201))), 13)
                f.write(historyPartition(f.access, f.conversation), 14)
                assertEquals(AppResult.Success(Unit), updates.receive())
                assertTrue(updates.tryReceive().isFailure)
                assertEquals(f.partitions().toSet(), registrations.value)
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
                updates.close()
            }
        }
    }

    @Test
    fun stagedListMetadataIsExcludedUntilItsGenerationIsPublished() = withDatabase { f ->
        coroutineScope {
            val updates = Channel<AppResult<Unit>>(8)
            val job = launch { f.changes().collect { updates.send(it) } }
            try {
                assertEquals(AppResult.Success(Unit), updates.receive())
                val scope = historyScope(f.access)
                val partition = listPartition(f.access)
                assertTrue(
                    f.database.replicas.begin(scope, partition, historyId(30), "staged", null)
                )
                val raw = f.database.replicas.checkpoint(scope, partition)!!
                assertTrue(
                    f.database.replicas.commit(
                        raw.handle,
                        raw.revision,
                        "still staged",
                        emptyList(),
                        false,
                    )
                )
                f.write(historyPartition(f.access, f.conversation), 31)
                assertEquals(AppResult.Success(Unit), updates.receive())
                assertTrue(updates.tryReceive().isFailure)
                val latest = f.database.replicas.checkpoint(scope, partition)!!
                assertTrue(
                    f.database.replicas.commit(
                        latest.handle,
                        latest.revision,
                        "published",
                        emptyList(),
                        true,
                    )
                )
                assertEquals(AppResult.Success(Unit), updates.receive())
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
                updates.close()
            }
        }
    }

    @Test
    fun completingOrFailingOneSourceStopsAndJoinsAllRegistrations() = withDatabase { f ->
        for (throws in listOf(false, true)) coroutineScope {
            val finish = CompletableDeferred<Unit>()
            val active = MutableStateFlow(emptySet<String>())
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observePublishedCheckpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ) =
                        flow<ReplicaCheckpoint?> {
                            active.update { it + partition }
                            try {
                                emit(null)
                                if (partition == listPartition(f.access)) {
                                    finish.await()
                                    if (throws) error("Fictional source failure")
                                } else awaitCancellation()
                            } finally {
                                active.update { it - partition }
                            }
                        }
                }
            val values = Channel<AppResult<Unit>>(8)
            val job = launch { f.changes(store).collect { values.send(it) } }
            try {
                assertEquals(AppResult.Success(Unit), values.receive())
                assertEquals(5, active.value.size)
                finish.complete(Unit)
                assertIs<AppResult.Failed>(values.receive())
                job.join()
                assertTrue(active.value.isEmpty())
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
                values.close()
            }
        }
    }

    @Test
    fun invalidSelectionFailsBeforeSubscriptionAndOwnedCancellationJoinsEverySource() =
        withDatabase { f ->
            coroutineScope {
                val active = MutableStateFlow(0)
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observePublishedCheckpoint(
                            scope: ReplicaScope,
                            partition: String,
                        ) =
                            flow<ReplicaCheckpoint?> {
                                active.update { it + 1 }
                                try {
                                    emit(null)
                                    awaitCancellation()
                                } finally {
                                    active.update { it - 1 }
                                }
                            }
                    }
                val repo = StoredCollaborationNotificationChangeRepository(store)
                assertIs<AppResult.Failed>(repo.observe(f.access, "invalid").single())
                assertIs<AppResult.Failed>(
                    repo.observe(f.access.copy(companyId = "invalid"), f.conversation).single()
                )
                assertEquals(0, active.value)
                assertEquals(AppResult.Success(Unit), f.changes(store).first())
                assertEquals(0, active.value)
            }
        }

    private fun CollaborationHistoryFixture.changes(store: ReplicaStore = database.replicas) =
        StoredCollaborationNotificationChangeRepository(store).observe(access, conversation)

    private fun CollaborationHistoryFixture.partitions() =
        listOf(
            listPartition(access),
            identityPartition(access),
            historyPartition(access, conversation),
            notificationSettingsPartition(access, conversation),
            readStatePartition(access, conversation),
        )

    private suspend fun CollaborationHistoryFixture.write(partition: String, index: Int) {
        assertTrue(
            database.replicas.replace(
                historyScope(access),
                partition,
                null,
                ReplicaSnapshot(
                    historyId(9300 + index),
                    "Synthetic metadata; no content grant",
                    emptyList(),
                ),
            )
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
