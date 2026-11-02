package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationDeliveryCursorRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.usecases.AdvanceCollaborationDelivery
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationDeliveryWork
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationDeliveryWorkTest {
    @Test
    fun signedOutOrUnselectedCompanyHasNoWorkAndDoesNotReadMessages() = runTest {
        for (logout in listOf(true, false)) {
            val f = Fixture()
            if (logout) f.base.setup.session = null else f.base.setup.workspace = null
            assertEquals(AppResult.Success(null), f.read())
            assertEquals(0, f.observations)
        }
    }

    @Test
    fun emptyOwnedQueueDoesNotAcquireSetupOrNativeCredentials() = runTest {
        val f = Fixture()
        assertEquals(AppResult.Success(null), f.read())
        assertEquals(0, f.base.setup.calls)
        assertEquals(0, f.activeObservers)
    }

    @Test
    fun expiredLiveAuthorityPreservesLocalWorkAndStableConversationOrder() = runTest {
        val f = Fixture()
        val first = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue()).value
        val second =
            first.copy(
                intent =
                    first.intent.copy(
                        id = id(8),
                        conversationId = id(9),
                        createdAt = first.intent.createdAt - 1.seconds,
                    )
            )
        f.base.records.value =
            listOf(first, second, first.copy(intent = first.intent.copy(id = id(10))))
        f.base.setup.now += 30.days
        val result = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read()).value!!
        assertEquals(
            listOf(first.intent.conversationId, second.intent.conversationId),
            result.conversationIds,
        )
        assertEquals(first.intent.owner, result.access.messageOwner())
        assertEquals(CollaborationLocalScope.Selected("selection"), result.scope)
        assertEquals(0, f.base.setup.calls)
        assertEquals(0, f.activeObservers)
        assertFalse(result.toString().contains(first.intent.text))
    }

    @Test
    fun knownDenialPreventsMessageAcquisition() = runTest {
        val f = Fixture()
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.read()).failure)
        assertEquals(0, f.observations)
    }

    @Test
    fun denialOrOwnerReplacementDuringObservationCannotPublishWork() = runTest {
        for (logout in listOf(true, false)) {
            val f = Fixture()
            assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue())
            f.beforeEmission = {
                if (logout) f.base.setup.session = null
                else f.base.gate.value = CollaborationListAdmission("late", f.base.denied)
            }
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun malformedMixedOwnedDuplicateAndOversizedQueuesCannotEscape() = runTest {
        for (mode in 0..4) {
            val f = Fixture()
            val original =
                assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue()).value
            f.base.records.value =
                when (mode) {
                    0 ->
                        listOf(
                            original.copy(
                                intent =
                                    original.intent.copy(
                                        owner = original.intent.owner.copy(accountId = id(99))
                                    )
                            )
                        )
                    1 -> listOf(original, original)
                    2 ->
                        listOf(
                            original.copy(intent = original.intent.copy(conversationId = "invalid"))
                        )
                    3 -> (1..129).map { original.copy(intent = original.intent.copy(id = id(it))) }
                    else ->
                        listOf(
                            original.copy(
                                state =
                                    CollaborationMessageState.Staged(
                                        CollaborationMessageDevice(
                                            "invalid",
                                            id(4),
                                            id(5),
                                            "!room",
                                        ),
                                        "tx",
                                    )
                            )
                        )
                }
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun cancellingAnUncooperativeObservationCannotPublishItsLateSnapshot() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeEmission = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val job = async { f.read() }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun advancingBeforeWorkRotatesTheNextPassAndRejectsAnOldGeneration() = runTest {
        val f = Fixture()
        val record = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue()).value
        val other = record.copy(intent = record.intent.copy(id = id(5), conversationId = id(6)))
        f.base.records.value = listOf(record, other)
        val initial = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read()).value!!
        val cursor =
            assertIs<AppResult.Success<CollaborationDeliveryCursor>>(
                    f.advance(initial, initial.conversationIds.first())
                )
                .value
        val next = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read()).value!!
        assertEquals(
            listOf(other.intent.conversationId, record.intent.conversationId),
            next.conversationIds,
        )
        assertEquals(cursor, next.cursor)
        assertIs<AppResult.Failed>(f.advance(initial, initial.conversationIds.first()))
        assertIs<AppResult.Success<CollaborationDeliveryCursor>>(
            f.advance(next, other.intent.conversationId)
        )
        assertEquals(
            initial.conversationIds,
            assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read())
                .value!!
                .conversationIds,
        )
    }

    @Test
    fun unknownRoomAndChangedCompanyCannotAdvanceTheDeliveryTurn() = runTest {
        val f = Fixture()
        assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue())
        val work = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read()).value!!
        assertIs<AppResult.Failed>(f.advance(work, id(999)))
        assertNull(f.cursor)
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        assertIs<AppResult.Failed>(f.advance(work, work.conversationIds.first()))
        assertNull(f.cursor)
    }

    @Test
    fun lateCursorCommitIsRetainedWithoutPublishingAfterCancellation() = runTest {
        val f = Fixture()
        assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue())
        val work = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read()).value!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterAdvance = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val job = async { f.advance(work, work.conversationIds.first()) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(work.conversationIds.first(), assertNotNull(f.cursor).afterConversationId)
    }

    @Test
    fun capturedDiscoveryAndFairCursorRemainLocalAfterExpiryAndWithoutSelection() = runTest {
        val f = Fixture()
        val original = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue()).value
        val other = original.copy(intent = original.intent.copy(id = id(8), conversationId = id(9)))
        f.base.records.value = listOf(original, other)
        val scope = f.base.captureRecoveryScope()
        val first = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read(scope)).value!!
        assertEquals(scope, first.scope)
        assertEquals(scope.access, first.access)
        assertIs<AppResult.Success<*>>(f.advance(first, first.conversationIds.first()))
        val next = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read(scope)).value!!
        assertEquals(
            listOf(other.intent.conversationId, original.intent.conversationId),
            next.conversationIds,
        )
        assertIs<AppResult.Failed>(f.advance(first, first.conversationIds.first()))
        assertEquals(0, f.base.setup.calls)
        assertEquals(0, f.activeObservers)
    }

    @Test
    fun anotherCapturedAccountOrRetainedDenialCannotDiscoverOrAdvanceOwnedWork() = runTest {
        val f = Fixture()
        assertIs<AppResult.Success<*>>(f.base.queue())
        val scope = f.base.captureRecoveryScope()
        val invalid = CollaborationLocalScope.Captured(scope.access.copy(accountId = id(99)))
        assertIs<AppResult.Failed>(f.read(invalid))
        assertEquals(0, f.observations)
        val work = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read(scope)).value!!
        assertIs<AppResult.Failed>(
            f.advance(work.copy(scope = invalid), work.conversationIds.single())
        )
        assertNull(f.cursor)
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        assertEquals(AppResult.Failed(f.base.denied), f.read(scope))
        assertEquals(
            AppResult.Failed(f.base.denied),
            f.advance(work, work.conversationIds.single()),
        )
        assertNull(f.cursor)
        assertEquals(1, f.observations)
        assertEquals(1, f.base.records.value.size)
    }

    @Test
    fun capturedCursorCommitSurvivesCancellationWithoutPublishingItsLateResult() = runTest {
        val f = Fixture()
        assertIs<AppResult.Success<*>>(f.base.queue())
        val scope = f.base.captureRecoveryScope()
        val work = assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read(scope)).value!!
        val entered = CompletableDeferred<Unit>()
        f.afterAdvance = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.advance(work, work.conversationIds.single()) }
        entered.await()
        task.cancelAndJoin()
        assertEquals(work.conversationIds.single(), f.cursor?.afterConversationId)
        assertEquals(1, f.base.records.value.size)
        f.afterAdvance = {}
        assertIs<AppResult.Failed>(f.advance(work, work.conversationIds.single()))
        assertEquals(
            scope,
            assertIs<AppResult.Success<CollaborationDeliveryWork?>>(f.read(scope)).value!!.scope,
        )
    }

    private class Fixture {
        val base = MessageIntentFixture()
        var beforeEmission: suspend () -> Unit = {}
        var observations = 0
        var activeObservers = 0
        val messages =
            object : CollaborationMessageRepository by base.messages {
                override fun observe(access: CollaborationLocalAccess, conversationId: String?) =
                    flow {
                        check(conversationId == null)
                        observations++
                        activeObservers++
                        try {
                            val result = base.records.value.toList()
                            beforeEmission()
                            emit(AppResult.Success(result))
                        } finally {
                            activeObservers--
                        }
                    }
            }
        var cursor: CollaborationDeliveryCursor? = null
        private var sequence = 0
        var afterAdvance: suspend () -> Unit = {}
        val cursors =
            object : CollaborationDeliveryCursorRepository {
                override suspend fun current(access: CollaborationLocalAccess) =
                    AppResult.Success(cursor)

                override suspend fun advance(
                    access: CollaborationLocalAccess,
                    expected: CollaborationDeliveryCursor?,
                    conversationId: String,
                ): AppResult<CollaborationDeliveryCursor?> {
                    if (cursor != expected) return AppResult.Success(null)
                    sequence++
                    cursor =
                        CollaborationDeliveryCursor(
                            "dddddddd-0000-0000-0000-" + sequence.toString().padStart(12, '0'),
                            conversationId,
                        )
                    afterAdvance()
                    return AppResult.Success(cursor)
                }
            }
        val advance =
            AdvanceCollaborationDelivery(
                cursors,
                base.lists,
                base.setup.sessions,
                base.setup.workspaces,
                base.server,
                base.build,
                base.clock,
            )
        val read =
            ReadCollaborationDeliveryWork(
                messages,
                base.lists,
                base.setup.sessions,
                base.setup.workspaces,
                base.server,
                base.build,
                base.clock,
                cursors,
            )
    }

    private fun id(value: Int) = "bbbbbbbb-0000-0000-0000-" + value.toString().padStart(12, '0')
}
