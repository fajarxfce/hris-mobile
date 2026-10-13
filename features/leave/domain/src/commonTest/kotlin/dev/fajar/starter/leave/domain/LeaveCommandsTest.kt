package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LeaveCommandsTest {
    @Test
    fun aCacheLeaseThatExpiresDuringAdmissionCannotCreateAnIntent() = runTest {
        val origin = Instant.parse("2026-10-12T01:00:00Z")
        var now = origin
        val f = LeaveCommandFixture { now }
        f.beforeObserve = { now = origin + 2.hours }
        assertIs<AppResult.Failed>(f.queue())
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun cancelledAdmissionIgnoresItsLateStorageObservation() = runTest {
        val f = LeaveCommandFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeObserve = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val pending = async { f.queue() }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertTrue(f.records.value.isEmpty())
        assertEquals(0, f.observers)
    }

    @Test
    fun concurrentManualAndBackgroundAttemptsTransmitOnlyOnceAfterAReceipt() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.transmit = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(LeaveActionReceipt(it.requestId, it.expectedVersion + 1))
        }
        val manual = async { f.send() }
        entered.await()
        val background = async { f.replay() }
        runCurrent()
        assertEquals(1, f.transmitted.size)
        release.complete(Unit)
        assertIs<AppResult.Success<LeaveCommandRecord>>(manual.await())
        assertEquals(SyncResult.Complete, background.await())
        assertEquals(1, f.transmitted.size)
    }

    @Test
    fun aRequestDeadlineRetainsTheOriginalIntentAndDoesNotStartAnotherAttempt() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        f.transmit = { awaitCancellation() }
        val result = assertIs<AppResult.Failed>(f.send())
        assertEquals(FailureKind.Timeout, result.failure.kind)
        assertEquals(30_000, testScheduler.currentTime)
        assertEquals(1, f.transmitted.size)
        assertIs<LeaveCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
    }

    @Test
    fun aReceiptRemainsInItsOriginalCommandAfterTheVisibleCompanyChanges() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        f.transmit = {
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "other-workspace"))
            AppResult.Success(LeaveActionReceipt(it.requestId, it.expectedVersion + 1))
        }
        assertIs<AppResult.Failed>(f.send())
        assertEquals(LeaveCommandOutcome.Confirmed(1), f.records.value.single().outcome)
        f.base.workspaceState.value = AppResult.Success(f.base.workspace)
        f.send()
        assertEquals(1, f.transmitted.size)
    }

    @Test
    fun admissionRequiresTheVisibleVersionServerActionWritePermissionAndAReason() = runTest {
        val f = LeaveCommandFixture()
        for (result in
            listOf(
                f.queue(version = 1),
                f.queue(reason = "  "),
                f.queue(reason = "x".repeat(1_001)),
                f.queue(action = LeaveRequestAction.REQUEST_CANCELLATION),
            )) assertIs<AppResult.Failed>(result)
        f.publish(f.request.details.copy(availableActions = emptySet()))
        assertIs<AppResult.Failed>(f.queue())
        f.publish()
        f.base.workspaceState.value =
            AppResult.Success(f.base.workspace.copy(permissions = setOf("leave.read")))
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.queue()).failure.code)
        assertTrue(f.records.value.isEmpty())
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun duplicateAdmissionRetainsExactlyOneImmutableIntentAndBlocksDifferentInput() = runTest {
        val f = LeaveCommandFixture()
        val results =
            listOf(async { f.queue(reason = " Plans changed ") }, async { f.queue() }).awaitAll()
        val first = assertIs<AppResult.Success<LeaveCommandRecord>>(results.first()).value
        assertEquals(first, assertIs<AppResult.Success<LeaveCommandRecord>>(results.last()).value)
        assertEquals("Plans changed", first.command.reason)
        assertEquals(1, f.records.value.size)
        assertIs<AppResult.Failed>(f.queue(reason = "Different reason"))
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun withdrawalAndCancellationRequireTheirOwnCurrentLifecycle() = runTest {
        for ((status, action, hint) in
            listOf(
                Triple(LeaveStatus.PENDING, LeaveRequestAction.WITHDRAW, LeaveAction.WITHDRAW),
                Triple(
                    LeaveStatus.APPROVED,
                    LeaveRequestAction.REQUEST_CANCELLATION,
                    LeaveAction.REQUEST_CANCELLATION,
                ),
                Triple(
                    LeaveStatus.CANCELLATION_PENDING,
                    LeaveRequestAction.WITHDRAW_CANCELLATION,
                    LeaveAction.WITHDRAW,
                ),
            )) {
            val f = LeaveCommandFixture()
            f.publish(f.request.details.copy(status = status, availableActions = setOf(hint)))
            assertIs<AppResult.Success<LeaveCommandRecord>>(f.queue(action))
        }
    }

    @Test
    fun anAmbiguousAttemptCannotBecomeRejectedAfterAChangedPermissionOrVersion() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Connection lost")) }
        assertIs<LeaveCommandOutcome.Unconfirmed>(
            assertIs<AppResult.Success<LeaveCommandRecord>>(f.send()).value.outcome
        )
        f.transmit = {
            AppResult.Failed(Failure(FailureKind.Conflict, "Changed", code = "stale_version"))
        }
        assertIs<LeaveCommandOutcome.Unconfirmed>(
            assertIs<AppResult.Success<LeaveCommandRecord>>(f.send()).value.outcome
        )
        assertEquals(f.transmitted.first(), f.transmitted.last())
        assertIs<AppResult.Failed>(
            f.review(f.base.workspace.id, f.records.value.single().command.id)
        )
        assertEquals(1, f.records.value.size)
    }

    @Test
    fun aFirstDefiniteRejectionStopsAutomaticAndManualReplayUntilExplicitReview() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        f.transmit = {
            AppResult.Failed(Failure(FailureKind.Conflict, "Changed", code = "stale_version"))
        }
        assertIs<LeaveCommandOutcome.Rejected>(
            assertIs<AppResult.Success<LeaveCommandRecord>>(f.send()).value.outcome
        )
        f.send()
        assertEquals(SyncResult.Complete, f.replay())
        assertEquals(1, f.transmitted.size)
        assertEquals(
            AppResult.Success(true),
            f.review(f.base.workspace.id, f.records.value.single().command.id),
        )
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun currentCompanyPolicyIsRequiredBeforePersistingAnAttemptOrSendingIt() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        f.base.policyCall = {
            AppResult.Success(
                f.base.policy.copy(
                    enabledModules = f.base.policy.enabledModules - CompanyModule.LEAVE
                )
            )
        }
        assertIs<AppResult.Failed>(f.send())
        assertEquals(LeaveCommandOutcome.Pending, f.records.value.single().outcome)
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun cancellationAfterTransmissionKeepsUnconfirmedEvidenceAndDiscardsTheLateReceipt() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<LeaveActionReceipt>()
        f.transmit = {
            assertIs<LeaveCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
            entered.complete(Unit)
            AppResult.Success(withContext(NonCancellable) { response.await() })
        }
        val pending = async { f.send() }
        entered.await()
        pending.cancel()
        response.complete(LeaveActionReceipt(f.request.details.id, 1))
        assertFailsWith<CancellationException> { pending.await() }
        assertIs<LeaveCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        assertEquals(1, f.records.value.single().attempts)
    }

    @Test
    fun pendingPolicyReplacedByAnotherWorkspaceCannotTransmitOrPublishToItsScreen() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        val policy = CompletableDeferred<Unit>()
        f.base.policyCall = {
            policy.await()
            AppResult.Success(f.base.policy)
        }
        val pending = async { f.send() }
        runCurrent()
        f.base.workspaceState.value = AppResult.Success(f.base.workspace.copy(id = "replacement"))
        policy.complete(Unit)
        assertIs<AppResult.Failed>(pending.await())
        assertTrue(f.transmitted.isEmpty())
        assertEquals(LeaveCommandOutcome.Pending, f.records.value.single().outcome)
    }

    @Test
    fun confirmedEvidenceIsRetainedUntilTheCanonicalRequestAndItsCacheCatchUp() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        val sent = assertIs<AppResult.Success<LeaveCommandRecord>>(f.send()).value
        assertEquals(LeaveCommandOutcome.Confirmed(1), sent.outcome)
        assertIs<AppResult.Failed>(f.review(f.base.workspace.id, sent.command.id))
        val canonical =
            f.request.details.copy(
                version = 1,
                status = LeaveStatus.CANCELLED,
                availableActions = emptySet(),
            )
        f.request.requestCall = { AppResult.Success(canonical) }
        assertIs<AppResult.Failed>(f.review(f.base.workspace.id, sent.command.id))
        f.publish(canonical)
        assertEquals(AppResult.Success(true), f.review(f.base.workspace.id, sent.command.id))
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun automaticRetriesAreBoundedAndAReceiptWithTheWrongVersionStaysUnconfirmed() = runTest {
        val f = LeaveCommandFixture()
        f.queue()
        f.transmit = { AppResult.Success(LeaveActionReceipt(it.requestId, it.expectedVersion)) }
        repeat(3) { assertIs<SyncResult.Blocked>(f.replay()) }
        assertEquals(SyncResult.Complete, f.replay())
        assertEquals(3, f.transmitted.size)
        assertIs<LeaveCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
    }

    @Test
    fun storageFailureBeforeTransportPreventsSendingAndReceiptStorageFailurePreservesUncertainty() =
        runTest {
            val f = LeaveCommandFixture()
            f.queue()
            f.updateFailure = Failure(FailureKind.Storage, "Storage unavailable")
            assertIs<AppResult.Failed>(f.send())
            assertTrue(f.transmitted.isEmpty())
            f.updateFailure = null
            f.beforeUpdate = {
                if (it is LeaveCommandOutcome.Confirmed)
                    f.updateFailure = Failure(FailureKind.Storage, "Storage unavailable")
            }
            assertIs<AppResult.Failed>(f.send())
            assertIs<LeaveCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        }

    @Test
    fun pendingDiscardCannotRemoveEvidenceWhenDeliveryWinsTheRevisionRace() = runTest {
        val f = LeaveCommandFixture()
        val command = assertIs<AppResult.Success<LeaveCommandRecord>>(f.queue()).value
        val remove = CompletableDeferred<Unit>()
        f.beforeRemove = { remove.await() }
        val pending = async { f.review(f.base.workspace.id, command.command.id) }
        runCurrent()
        f.send()
        remove.complete(Unit)
        assertEquals(AppResult.Success(false), pending.await())
        assertIs<LeaveCommandOutcome.Confirmed>(f.records.value.single().outcome)
    }

    @Test
    fun pendingCommandRemainsVisibleForDaysAndReleasesAllCollectorsOnDisposal() = runTest {
        val origin = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveCommandFixture { origin + testScheduler.currentTime.milliseconds }
        f.queue()
        val values = mutableListOf<AppResult<LeaveCommandRecord?>>()
        val observation =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.base.workspace.id, f.request.details.id).toList(values)
            }
        assertIs<AppResult.Success<LeaveCommandRecord?>>(values.last())
        val initial = values.last()
        advanceTimeBy(72.hours)
        runCurrent()
        assertEquals(initial, values.last())
        assertEquals(1, values.size)
        observation.cancelAndJoin()
        assertEquals(0, f.observers)
    }
}
