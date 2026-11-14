package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceInboxHistorySourceStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absenceAndInvalidBatchesCreateNoKeysOrRecords() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.repository().current(f.access(), f.id))
        assertTrue(success(f.repository().sources(f.access(), null, 16)).sources.isEmpty())
        assertIs<AppResult.Failed>(
            f.repository()
                .mutate(f.access(), f.mutation(null, unresolved = List(17) { "\$event-$it" }))
        )
        assertIs<AppResult.Failed>(f.repository().current(f.access(), "invalid"))
        assertNull(f.base.keys.read())
        assertFalse(f.base.database.replicas.containsRecords(COLLABORATION_HISTORY_PREFIX))
    }

    @Test
    fun cursorAndUnresolvedReferencesSurviveDatabaseReopenTogether() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val cursor =
            CollaborationHistoryChangeCursor.After(initial.progress.owner, historyId(50), 7)
        val completed = initial.progress.copy(historyCursor = cursor, historyComplete = true)
        val saved = f.save(f.mutation(initial, completed, unresolved = listOf("\$pending")))
        val key = f.base.keys.read()
        f.base.reopen()
        assertEquals(saved, f.current())
        val page = f.pending(saved)
        assertEquals(listOf("\$pending"), page.references.map { it.eventId })
        assertEquals(cursor, saved.progress.historyCursor)
        assertEquals(saved.version.revision, page.references.single().insertedAtRevision)
        assertEquals(key, f.base.keys.read())
        assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun pendingIntervalsExcludeLaterInsertionsAndResumeAfterReopen() = withDatabase { f ->
        val initial = f.save(f.mutation(null, unresolved = listOf("\$a", "\$b")))
        val first = f.pending(initial, 1)
        assertEquals(1, first.references.size)
        assertNotNull(first.next)
        val window = first.window.copy(after = first.next)
        val saved =
            f.save(
                f.mutation(
                    initial,
                    initial.progress.copy(pendingWindow = window),
                    unresolved = listOf("\$later"),
                )
            )
        f.base.reopen()
        val last = f.pending(saved, 1)
        assertEquals(1, last.references.size)
        assertNull(last.next)
        assertEquals(
            setOf("\$a", "\$b"),
            (first.references + last.references).map { it.eventId }.toSet(),
        )
        assertTrue(last.references.all { it.insertedAtRevision <= window.throughRevision })
    }

    @Test
    fun retainingTheSameUnresolvedReferenceDoesNotRenewItsInsertionPosition() = withDatabase { f ->
        val first = f.save(f.mutation(null, unresolved = listOf("\$pending")))
        val before = f.pending(first).references.single()
        val repeated = f.save(f.mutation(first, unresolved = listOf("\$pending")))
        assertEquals(before, f.pending(repeated).references.single())
        assertTrue(repeated.version.revision > first.version.revision)
    }

    @Test
    fun staleCompletionCannotRemoveALaterReinsertedReference() = withDatabase { f ->
        val first = f.save(f.mutation(null, unresolved = listOf("\$pending")))
        val removed = f.save(f.mutation(first, retained = listOf("\$pending")))
        assertTrue(f.pending(removed).references.isEmpty())
        val reinserted = f.save(f.mutation(removed, unresolved = listOf("\$pending")))
        assertEquals(
            AppResult.Success(null),
            f.repository().mutate(f.access(), f.mutation(first, retained = listOf("\$pending"))),
        )
        assertEquals(
            reinserted.version.revision,
            f.pending(reinserted).references.single().insertedAtRevision,
        )
    }

    @Test
    fun indexAdvancementWithinItsGenerationDoesNotForceHistoryRestart() = withDatabase { f ->
        val receipt = f.publishIndex(20)
        f.publishIndex(21)
        val saved = f.save(f.mutation(null, destination = receipt))
        assertEquals(receipt.generation, saved.progress.destinationGeneration)
        assertEquals(saved, f.current())
    }

    @Test
    fun destinationReplacementRejectsOldReceiptAndKeepsPendingWork() = withDatabase { f ->
        val receipt = f.publishIndex(20)
        val initial =
            f.save(f.mutation(null, destination = receipt, unresolved = listOf("\$pending")))
        assertTrue(
            f.base.database.replicas.clear(
                spaceHistoryScope(f.access()),
                spaceInboxPartition(f.access()),
            )
        )
        val replacement = f.publishIndex(21)
        assertNotEquals(receipt.generation, replacement.generation)
        assertEquals(
            AppResult.Success(null),
            f.repository()
                .mutate(
                    f.access(),
                    f.mutation(initial, destination = receipt, retained = listOf("\$pending")),
                ),
        )
        assertEquals(initial, f.current())
        assertEquals(listOf("\$pending"), f.pending(initial).references.map { it.eventId })
    }

    @Test
    fun anIndexRaceRollsBackCursorAndReferenceRemoval() = withDatabase { f ->
        val receipt = f.publishIndex(20)
        val initial =
            f.save(f.mutation(null, destination = receipt, unresolved = listOf("\$pending")))
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.publishIndex(21)
                    return actual.mutate(scope, mutations, wake)
                }
            }
        val proposal =
            f.mutation(
                initial,
                initial.progress.copy(historyComplete = true),
                receipt,
                retained = listOf("\$pending"),
            )
        assertEquals(AppResult.Success(null), f.repository(store).mutate(f.access(), proposal))
        assertEquals(initial, f.current())
        assertEquals(1, f.pending(initial).references.size)
    }

    @Test
    fun indexCreationDuringAnAbsentCheckCannotPublishAnUnboundProgressRecord() = withDatabase { f ->
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.publishIndex(20)
                    return actual.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.repository(store)
                .mutate(f.access(), f.mutation(null, unresolved = listOf("\$pending"))),
        )
        assertNull(success(f.repository().current(f.access(), f.id)))
    }

    @Test
    fun aLostResponseKeepsTheWholeHandoffAvailableForRecovery() = withDatabase { f ->
        val first = f.save(f.mutation(null))
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    check(actual.mutate(scope, mutations, wake))
                    error("Fixture lost committed response")
                }
            }
        val proposal =
            f.mutation(
                first,
                first.progress.copy(historyComplete = true),
                unresolved = listOf("\$pending"),
            )
        assertIs<AppResult.Failed>(f.repository(store).mutate(f.access(), proposal))
        val retained = f.current()
        assertEquals(proposal.replacement, retained.progress)
        assertEquals(listOf("\$pending"), f.pending(retained).references.map { it.eventId })
        assertEquals(AppResult.Success(null), f.repository().mutate(f.access(), proposal))
    }

    @Test
    fun cancellationBeforeTheAtomicBoundaryLeavesBothPartsUnchanged() = withDatabase { f ->
        val initial = f.save(f.mutation(null, unresolved = listOf("\$pending")))
        val actual = f.base.database.replicas
        val entered = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = launch {
                f.repository(store)
                    .mutate(f.access(), f.mutation(initial, retained = listOf("\$pending")))
            }
            withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
            job.cancelAndJoin()
        }
        assertEquals(initial, f.current())
        assertEquals(1, f.pending(initial).references.size)
    }

    @Test
    fun concurrentHandoffsPublishOnlyOneExpectedState() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val actual = f.base.database.replicas
        val entered = Channel<Unit>(2)
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.send(Unit)
                    release.await()
                    return actual.mutate(scope, mutations, wake)
                }
            }
        coroutineScope {
            val writes =
                listOf("\$first", "\$second").map { event ->
                    async {
                        f.repository(store)
                            .mutate(f.access(), f.mutation(initial, unresolved = listOf(event)))
                    }
                }
            withContext(Dispatchers.Default) {
                withTimeout(5000) { repeat(2) { entered.receive() } }
            }
            release.complete(Unit)
            assertEquals(1, writes.awaitAll().count { success(it) != null })
        }
        assertEquals(1, f.pending(f.current()).references.size)
    }

    @Test
    fun renewalReopensTheSameSourceButAnotherAccountCannotReadIt() = withDatabase { f ->
        val first = f.save(f.mutation(null, unresolved = listOf("\$pending")))
        val renewed = f.base.access.copy(sessionId = "renewed")
        f.base.reopen(renewed)
        val local = f.access().copy(sessionId = renewed.sessionId)
        assertEquals(first, success(f.repository().current(local, f.id)))
        assertEquals(AppResult.Success(null), f.repository().current(f.access(), f.id))
        assertEquals(
            AppResult.Success(null),
            f.repository().mutate(f.access(), f.mutation(first, retained = listOf("\$pending"))),
        )
        val other = renewed.copy(accountId = historyId(8000))
        f.base.activate(other)
        assertNull(success(f.repository().current(local.copy(accountId = other.accountId), f.id)))
        assertEquals(AppResult.Success(null), f.repository().current(local, f.id))
        assertEquals(
            AppResult.Success(null),
            f.repository().mutate(local, f.mutation(first, retained = listOf("\$pending"))),
        )
        f.base.activate(renewed)
        assertEquals(first, success(f.repository().current(local, f.id)))
        val pending = assertNotNull(success(f.repository().pending(local, f.id, first.version, 16)))
        assertEquals(listOf("\$pending"), pending.references.map { it.eventId })
    }

    @Test
    fun malformedCheckpointAndMissingKeyCannotBeReplacedByNewProgress() = withDatabase { f ->
        val first = f.save(f.mutation(null, unresolved = listOf("\$pending")))
        val key = assertNotNull(f.base.keys.read())
        f.base.keys.write(null)
        assertIs<AppResult.Failed>(f.repository().current(f.access(), f.id))
        assertIs<AppResult.Failed>(f.repository().mutate(f.access(), f.mutation(first)))
        assertNull(f.base.keys.read())
        f.base.keys.write(key)
        val raw = assertNotNull(f.raw())
        assertTrue(
            f.base.database.replicas.replace(
                spaceHistoryScope(f.access()),
                raw.handle.partition,
                raw,
                ReplicaSnapshot(historyId(80), "corrupt", emptyList()),
            )
        )
        assertIs<AppResult.Failed>(f.repository().current(f.access(), f.id))
        assertIs<AppResult.Failed>(f.repository().mutate(f.access(), f.mutation(first)))
        assertEquals(key, f.base.keys.read())
    }

    @Test
    fun boundedMetadataPagesDoNotRegisterProgressAsIncomingHistory() = withDatabase { f ->
        for (id in 1..3) {
            val initial = f.value(historyId(id))
            f.save(f.mutation(null, initial))
        }
        val first = success(f.repository().sources(f.access(), null, 2))
        assertEquals(2, first.sources.size)
        val last = success(f.repository().sources(f.access(), assertNotNull(first.next), 2))
        assertEquals(1, last.sources.size)
        assertNull(last.next)
        val page =
            success(
                StoredCollaborationIncomingHistoryLocatorRepository(f.base.database.replicas)
                    .read(
                        CollaborationSpaceAccountAccess(
                            f.access().sessionId,
                            f.access().audience,
                            f.access().accountId,
                        ),
                        null,
                        16,
                    )
            )
        assertTrue(page.locators.isEmpty())
    }

    @Test
    fun pendingReadsRejectAStaleCheckpointAndCompletedWindowsStayEmpty() = withDatabase { f ->
        val first = f.save(f.mutation(null, unresolved = listOf("\$pending")))
        val window = f.pending(first).window.copy(complete = true)
        val saved = f.save(f.mutation(first, first.progress.copy(pendingWindow = window)))
        assertEquals(
            AppResult.Success(null),
            f.repository().pending(f.access(), f.id, first.version, 16),
        )
        assertTrue(f.pending(saved).references.isEmpty())
        assertNull(f.pending(saved).next)
    }

    private fun withDatabase(block: suspend (InboxHistorySourceFixture) -> Unit) = runTest {
        val f = InboxHistorySourceFixture(CollaborationHistoryFixture(databaseOwner()))
        try {
            f.base.activate()
            block(f)
        } finally {
            f.base.close()
        }
    }
}

private class InboxHistorySourceFixture(val base: CollaborationHistoryFixture) {
    val id = historyId(9002)

    fun access() =
        CollaborationSpaceLocalAccess(
            base.access.sessionId,
            base.access.audience,
            base.access.accountId,
            historyId(9003),
        )

    fun repository(store: ReplicaStore = base.database.replicas) =
        EncryptedCollaborationSpaceInboxHistorySourceRepository(store, base.cipher, base.keys)

    fun value(conversationId: String = id) =
        CollaborationSpaceInboxHistorySourceProgress(
            conversationId,
            CollaborationHistoryChangeOwner(
                access().audience,
                access().accountId,
                CollaborationHistoryChangeOwnerKind.COMPANY,
                base.access.companyId,
                base.conversation,
                base.room,
            ),
            null,
            historyId(40),
            null,
            false,
            null,
        )

    fun mutation(
        expected: CollaborationSpaceInboxHistorySourceCheckpoint?,
        replacement: CollaborationSpaceInboxHistorySourceProgress = expected?.progress ?: value(),
        destination: CollaborationSpaceInboxVersion? = null,
        retained: List<String> = emptyList(),
        unresolved: List<String> = emptyList(),
    ) =
        CollaborationSpaceInboxHistorySourceMutation(
            expected,
            replacement.copy(destinationGeneration = destination?.generation),
            destination,
            retained,
            unresolved,
        )

    suspend fun save(value: CollaborationSpaceInboxHistorySourceMutation) =
        assertNotNull(success(repository().mutate(access(), value)))

    suspend fun current() = assertNotNull(success(repository().current(access(), id)))

    suspend fun pending(current: CollaborationSpaceInboxHistorySourceCheckpoint, limit: Int = 16) =
        assertNotNull(success(repository().pending(access(), id, current.version, limit)))

    suspend fun raw() =
        base.database.replicas.checkpoint(
            spaceHistoryScope(access()),
            spaceInboxHistorySourcePartition(access(), id),
        )

    suspend fun publishIndex(index: Int): CollaborationSpaceInboxVersion {
        val inbox =
            EncryptedCollaborationSpaceInboxRepository(
                base.database.replicas,
                base.cipher,
                base.keys,
            )
        val current = success(inbox.conversation(access(), historyId(index), 2))
        assertEquals(
            AppResult.Success(true),
            inbox.publish(
                access(),
                CollaborationSpaceInboxPublication(
                    current.version,
                    current.version?.lastOrdinal ?: 0,
                    collaborationSpaceInboxEntry(historyId(index), 0, null, null),
                ),
            ),
        )
        return assertNotNull(success(inbox.conversation(access(), historyId(index), 2)).version)
    }
}

private fun <T> success(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value
