@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ExpenseCommandDeliveryTest {
    @Test
    fun deliveryAndCleanupRequireSignInBeforeReadingRetainedCommands() = runTest {
        val p = ExpenseCommandFixture()
        p.f.sessionState.value = AppResult.Success(null)
        var reads = 0
        p.beforeRead = { reads++ }
        for (result in
            listOf(
                p.replay(p.f.workspace.id, p.f.claim.id),
                p.review(p.f.workspace.id, p.f.claim.id),
            )) assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(result).failure.kind,
        )
        assertEquals(0, reads)
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun interruptedDeliveryRetainsTheOperationAndAConfirmedReplayIsNotSentAgain() = runTest {
        val p = ExpenseCommandFixture()
        val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
        p.transport = { expenseFailure("fixture.offline", FailureKind.Network) }
        val first =
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.replay(p.f.workspace.id, stored.command.id)
                )
                .value
        assertIs<ExpenseCommandOutcome.Unconfirmed>(first.outcome)
        p.transport = {
            AppResult.Success(ExpenseCommandReceipt(it.claimId, it.expectedReceiptVersion()))
        }
        val recovered =
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.replay(p.f.workspace.id, stored.command.id)
                )
                .value
        assertEquals(ExpenseCommandOutcome.Confirmed(0), recovered.outcome)
        assertEquals(listOf(stored.command, stored.command), p.sent)
        assertEquals(
            recovered,
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.replay(p.f.workspace.id, stored.command.id)
                )
                .value,
        )
        assertEquals(2, p.sent.size)
    }

    @Test
    fun firstRejectionMayBeReviewedButLaterRejectionCannotEraseAnUncertainOutcome() = runTest {
        for (uncertain in listOf(false, true)) {
            val p = ExpenseCommandFixture()
            val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
            if (uncertain) {
                p.transport = { expenseFailure("fixture.offline", FailureKind.Network) }
                p.replay(p.f.workspace.id, stored.command.id)
            }
            p.transport = { expenseFailure("stale_version", FailureKind.Conflict) }
            val outcome =
                assertIs<AppResult.Success<ExpenseCommandRecord>>(
                        p.replay(p.f.workspace.id, stored.command.id)
                    )
                    .value
                    .outcome
            if (uncertain) {
                assertIs<ExpenseCommandOutcome.Unconfirmed>(outcome)
                assertIs<AppResult.Failed>(p.review(p.f.workspace.id, stored.command.id))
                assertEquals(1, p.values.value.size)
            } else {
                assertIs<ExpenseCommandOutcome.Rejected>(outcome)
                assertEquals(AppResult.Success(true), p.review(p.f.workspace.id, stored.command.id))
                assertTrue(p.values.value.isEmpty())
            }
        }
    }

    @Test
    fun concurrentManualAndWorkerDeliveryShareOneExecutionOwner() = runTest {
        val p = ExpenseCommandFixture()
        val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        p.transport = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(ExpenseCommandReceipt(it.claimId, 0))
        }
        val manual = async { p.replay(p.f.workspace.id, stored.command.id) }
        entered.await()
        val worker = async { p.replay() }
        runCurrent()
        assertEquals(1, p.sent.size)
        release.complete(Unit)
        assertIs<AppResult.Success<ExpenseCommandRecord>>(manual.await())
        assertEquals(SyncResult.Complete, worker.await())
        assertEquals(1, p.sent.size)
    }

    @Test
    fun cancellationDuringTransportLeavesUnconfirmedInputForRecovery() = runTest {
        val p = ExpenseCommandFixture()
        val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        p.transport = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(ExpenseCommandReceipt(it.claimId, 0))
        }
        val pending = async { p.replay(p.f.workspace.id, stored.command.id) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        val retained = p.values.value.single()
        assertIs<ExpenseCommandOutcome.Unconfirmed>(retained.outcome)
        assertEquals(1, retained.attempts)
        assertEquals(stored.command, retained.command)
    }

    @Test
    fun changedScopeAfterPolicyOrStorageAdmissionCannotBeginTransport() = runTest {
        for (afterStorage in listOf(false, true)) {
            val p = ExpenseCommandFixture()
            val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
            if (afterStorage)
                p.beforeUpdate = { _, _ -> p.f.sessionState.value = AppResult.Success(null) }
            else
                p.f.policyCall = {
                    p.f.sessionState.value = AppResult.Success(null)
                    expenseFailure("fixture.offline", FailureKind.Network)
                }
            assertIs<AppResult.Failed>(p.replay(p.f.workspace.id, stored.command.id))
            assertTrue(p.sent.isEmpty())
            assertEquals(1, p.values.value.size)
        }
    }

    @Test
    fun failedOutcomePersistenceCannotLoseTheOriginalCommand() = runTest {
        val p = ExpenseCommandFixture()
        val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
        p.transport = {
            p.failUpdates = true
            AppResult.Success(ExpenseCommandReceipt(it.claimId, 0))
        }
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(p.replay(p.f.workspace.id, stored.command.id)).failure.kind,
        )
        assertIs<ExpenseCommandOutcome.Unconfirmed>(p.values.value.single().outcome)
        p.failUpdates = false
        p.transport = { AppResult.Success(ExpenseCommandReceipt(it.claimId, 0)) }
        assertIs<AppResult.Success<ExpenseCommandRecord>>(
            p.replay(p.f.workspace.id, stored.command.id)
        )
        assertEquals(listOf(stored.command, stored.command), p.sent)
    }

    @Test
    fun changedCompanyAfterReceiptRetainsEvidenceButDoesNotPublishItToTheNewScope() = runTest {
        val p = ExpenseCommandFixture()
        val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
        p.transport = {
            p.f.workspaceState.value = AppResult.Success(p.f.workspace.copy(id = "replaced"))
            AppResult.Success(ExpenseCommandReceipt(it.claimId, 0))
        }
        assertIs<AppResult.Failed>(p.replay(p.f.workspace.id, stored.command.id))
        assertEquals(ExpenseCommandOutcome.Confirmed(0), p.values.value.single().outcome)
    }

    @Test
    fun mismatchedReceiptsAndBoundedAutomaticAttemptsNeverFabricateSuccess() = runTest {
        val p = ExpenseCommandFixture()
        val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
        p.transport = { AppResult.Success(ExpenseCommandReceipt("wrong", 999)) }
        assertIs<SyncResult.Blocked>(p.replay())
        assertIs<ExpenseCommandOutcome.Unconfirmed>(p.values.value.single().outcome)
        p.transport = { expenseFailure("fixture.offline", FailureKind.Network) }
        repeat(2) { assertIs<SyncResult.Retry>(p.replay()) }
        assertEquals(SyncResult.Complete, p.replay())
        assertEquals(3, p.sent.size)
        assertIs<ExpenseCommandOutcome.Unconfirmed>(p.values.value.single().outcome)
        val record = p.values.value.single().copy(attempts = EXPENSE_MAXIMUM_ATTEMPTS)
        p.values.value = listOf(record)
        assertEquals(
            "client.expense_retry_limit",
            assertIs<AppResult.Failed>(p.replay(p.f.workspace.id, stored.command.id)).failure.code,
        )
        assertEquals(3, p.sent.size)
    }

    @Test
    fun confirmedCleanupRequiresAnAdmittedCurrentClaimAndCannotRaceAnOutcomeChange() = runTest {
        val p = ExpenseCommandFixture()
        val stored = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save(edited = true)).value
        p.replay(p.f.workspace.id, stored.command.id)
        assertIs<AppResult.Failed>(p.review(p.f.workspace.id, stored.command.id))
        val snapshot = p.details()
        p.f.seedDetails(
            snapshot.copy(value = snapshot.value.copy(claim = p.f.claim.copy(version = 1)))
        )
        p.beforeRemove = {
            p.values.value = p.values.value.map { it.copy(revision = it.revision + 1) }
        }
        assertEquals(AppResult.Success(false), p.review(p.f.workspace.id, stored.command.id))
        assertEquals(1, p.values.value.size)
        p.beforeRemove = {}
        assertEquals(AppResult.Success(true), p.review(p.f.workspace.id, stored.command.id))
    }

    @Test
    fun observationReleasesItsStorageSubscriptionAfterAccountLossAndDisposal() = runTest {
        val p = ExpenseCommandFixture()
        assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save())
        val seen = mutableListOf<AppResult<ExpenseCommandRecord?>>()
        val job = launch {
            p.observe(p.f.workspace.id, ExpenseCommandSlot.Creation(p.f.employee.id)).toList(seen)
        }
        runCurrent()
        assertEquals(1, p.observers)
        assertNotNull(assertIs<AppResult.Success<ExpenseCommandRecord?>>(seen.last()).value)
        p.f.sessionState.value = AppResult.Success(null)
        runCurrent()
        assertIs<AppResult.Failed>(seen.last())
        assertEquals(0, p.observers)
        job.cancelAndJoin()
        assertEquals(0, p.observers)
    }
}
