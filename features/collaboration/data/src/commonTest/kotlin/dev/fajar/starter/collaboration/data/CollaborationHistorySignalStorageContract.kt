package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationHistorySignalRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

abstract class CollaborationHistorySignalStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun anEmptySignalReadDoesNotCreateHistoryOrKeys() = withDatabase { f ->
        assertNull(signal(f))
        assertNull(f.keys.read())
        assertNull(f.raw().checkpoint)
    }

    @Test
    fun sourceAndSignalSurviveReopenAndLoginRenewalTogether() = withDatabase { f ->
        f.merge(f.event())
        val initial = assertNotNull(signal(f))
        f.reopen()
        assertEquals(initial, signal(f))
        assertEquals(f.event().text, assertNotNull(f.lookup()).text)
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        assertEquals(initial, signal(f, renewed))
        assertNull(signal(f))
    }

    @Test
    fun companyHistoryBeforeCanonicalBindingSignalsTheSameAccount() = withDatabase { f ->
        val company = f.legacyAccess.copy(companyId = historyId(701))
        assertEquals(
            AppResult.Success(true),
            f.legacyRepository()
                .merge(company, historyId(702), "!company:example.test", listOf(f.event())),
        )
        val first = assertNotNull(signal(f))
        assertEquals(first, signal(f, f.access.copy(spaceId = historyId(703))))
        f.merge(f.event(2))
        assertNotEquals(first, signal(f))
    }

    @Test
    fun exactHistoryAndHideReplaysDoNotRenewTheSignal() = withDatabase { f ->
        f.merge(f.event())
        val first = signal(f)
        repeat(3) { f.merge(f.event()) }
        assertEquals(first, signal(f))
        val company = f.legacyRepository()
        assertEquals(
            AppResult.Success(true),
            company.merge(f.legacyAccess, f.conversation, f.room, listOf(f.event())),
        )
        val companySignal = signal(f)
        assertEquals(
            AppResult.Success(true),
            company.merge(f.legacyAccess, f.conversation, f.room, listOf(f.event())),
        )
        assertEquals(companySignal, signal(f))
        val ref =
            CollaborationMessageReference(f.event().sender, f.event().eventId, f.event().logicalId)
        assertEquals(
            AppResult.Success(true),
            company.hide(f.legacyAccess, f.conversation, f.room, listOf(ref)),
        )
        val hidden = signal(f)
        assertNotEquals(companySignal, hidden)
        assertEquals(
            AppResult.Success(true),
            company.hide(f.legacyAccess, f.conversation, f.room, listOf(ref)),
        )
        assertEquals(hidden, signal(f))
    }

    @Test
    fun editsAndRedactionsSignalWithoutChangingOriginalIdentity() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val initial = signal(f)
        val edit =
            CollaborationMessageEdit(
                "\$revision",
                original.sender,
                original.eventId,
                original.timestamp + 100,
                "Edited fictional text",
                "f".repeat(64),
                historyId(1001),
                original.logicalId,
                original.replyToEventId,
                original.forwarded,
            )
        assertEquals(
            AppResult.Success(true),
            f.repository.mergeEdits(f.access, f.conversation, f.room, listOf(edit)),
        )
        val edited = signal(f)
        assertNotEquals(initial, edited)
        assertEquals(
            AppResult.Success(true),
            f.repository.mergeEdits(f.access, f.conversation, f.room, listOf(edit)),
        )
        assertEquals(edited, signal(f))
        f.merge(CollaborationHistoryChange.Redacted(edit.eventId, original.sender))
        assertNotEquals(edited, signal(f))
        assertEquals(original.eventId, assertNotNull(f.lookup()).primaryEventId)
    }

    @Test
    fun aSignalConflictCannotPublishThePreparedHistory() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    val old =
                        f.database.replicas.checkpoint(scope, CollaborationHistorySignal.PARTITION)
                    assertTrue(
                        f.database.replicas.replace(
                            scope,
                            CollaborationHistorySignal.PARTITION,
                            old,
                            CollaborationHistorySignal.snapshot(historyId(704)),
                        )
                    )
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(store).merge(f.access, f.conversation, f.room, listOf(f.event(2))),
        )
        assertEquals(before, f.raw())
        assertEquals(historyId(704), signal(f))
    }

    @Test
    fun aCompetingSourceWriteCannotBeOverwrittenByThePreparedPair() = withDatabase { f ->
        f.merge(f.event())
        var calls = 0
        var competingSignal: String? = null
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    calls++
                    f.merge(f.event(3))
                    competingSignal = signal(f)
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(store).merge(f.access, f.conversation, f.room, listOf(f.event(2))),
        )
        assertEquals(1, calls)
        assertNull(f.lookup(f.event(2).eventId))
        assertNotNull(f.lookup(f.event(3).eventId))
        assertEquals(competingSignal, signal(f))
    }

    @Test
    fun anUncertainResponseRetainsBothFactsAndReplayDoesNotCreateAnotherSignal() =
        withDatabase { f ->
            f.merge(f.event())
            val old = signal(f)
            var calls = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        calls++
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        error("Fixture lost response")
                    }
                }
            assertIs<AppResult.Failed>(
                f.repository(store).merge(f.access, f.conversation, f.room, listOf(f.event(2)))
            )
            assertEquals(1, calls)
            f.reopen()
            val retained = signal(f)
            assertNotEquals(old, retained)
            assertNotNull(f.lookup(f.event(2).eventId))
            f.merge(f.event(2))
            assertEquals(retained, signal(f))
        }

    @Test
    fun cancellationBeforeCommitJoinsWithoutPublishingEitherHalf() = withDatabase { f ->
        coroutineScope {
            f.merge(f.event())
            val before = f.raw()
            val old = signal(f)
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
            val work = async {
                f.repository(store).merge(f.access, f.conversation, f.room, listOf(f.event(2)))
            }
            entered.await()
            work.cancelAndJoin()
            assertTrue(stopped.isCompleted)
            assertEquals(before, f.raw())
            assertEquals(old, signal(f))
        }
    }

    @Test
    fun accountReplacementCannotPublishOrExposeAnotherOwnersSignal() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        val old = signal(f)
        val other = f.access.copy(sessionId = "other-login", accountId = historyId(705))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(store).merge(f.access, f.conversation, f.room, listOf(f.event(2))),
        )
        assertNull(signal(f, other))
        assertNull(signal(f))
        f.activate()
        assertEquals(before, f.raw())
        assertEquals(old, signal(f))
    }

    @Test
    fun malformedSignalFailsInsteadOfOverwritingUnreviewedMetadata() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        val scope = spaceHistoryScope(f.access)
        val old = f.database.replicas.checkpoint(scope, CollaborationHistorySignal.PARTITION)
        assertTrue(
            f.database.replicas.replace(
                scope,
                CollaborationHistorySignal.PARTITION,
                old,
                ReplicaSnapshot(historyId(706), "unexpected-version", emptyList()),
            )
        )
        assertIs<AppResult.Failed>(
            StoredCollaborationHistorySignalRepository(f.database.replicas)
                .observe(f.access)
                .first()
        )
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, listOf(f.event(2)))
        )
        assertEquals(before, f.raw())
    }

    @Test
    fun anOpenSignalObserverSeesOnlyCommittedSourceFactsAndStopsWithItsOwner() = withDatabase { f ->
        coroutineScope {
            val values = Channel<AppResult<String?>>(2)
            val stopped = CompletableDeferred<Unit>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        StoredCollaborationHistorySignalRepository(f.database.replicas)
                            .observe(f.access)
                            .collect { values.send(it) }
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            assertEquals(AppResult.Success(null), values.receive())
            f.merge(f.event())
            val first = assertNotNull(assertIs<AppResult.Success<String?>>(values.receive()).value)
            assertNotNull(f.lookup())
            f.merge(f.event())
            f.merge(f.event(2))
            val second = assertNotNull(assertIs<AppResult.Success<String?>>(values.receive()).value)
            assertNotEquals(first, second)
            assertNotNull(f.lookup(f.event(2).eventId))
            collector.cancelAndJoin()
            assertTrue(stopped.isCompleted)
            values.close()
        }
    }

    @Test
    fun cancellationAfterCommitRetainsTheSourceAndItsSignalAfterReopen() = withDatabase { f ->
        f.merge(f.event())
        val old = signal(f)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                    throw CancellationException("Fixture cancellation after write")
                }
            }
        assertFailsWith<CancellationException> {
            f.repository(store).merge(f.access, f.conversation, f.room, listOf(f.event(2)))
        }
        f.reopen()
        assertNotNull(f.lookup(f.event(2).eventId))
        assertNotEquals(old, signal(f))
    }

    private suspend fun signal(
        f: CollaborationSpaceHistoryFixture,
        access: CollaborationSpaceLocalAccess = f.access,
    ) =
        assertIs<AppResult.Success<String?>>(
                StoredCollaborationHistorySignalRepository(f.database.replicas)
                    .observe(access)
                    .first()
            )
            .value

    private fun withDatabase(block: suspend (CollaborationSpaceHistoryFixture) -> Unit) = runTest {
        val f = CollaborationSpaceHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
