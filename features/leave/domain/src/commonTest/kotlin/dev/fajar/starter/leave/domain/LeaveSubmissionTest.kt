package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.usecases.ReplayLeaveSubmission
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LeaveSubmissionTest {
    @Test
    fun duplicateConfirmationRetainsOneImmutableInputAndANewReasonCannotReplaceIt() = runTest {
        val f = LeaveSubmissionFixture()
        val results =
            listOf(async { f.queue(reason = " Personal leave ") }, async { f.queue() }).awaitAll()
        val first = assertIs<AppResult.Success<LeaveSubmissionRecord>>(results.first()).value
        assertEquals(
            first,
            assertIs<AppResult.Success<LeaveSubmissionRecord>>(results.last()).value,
        )
        assertEquals("Personal leave", first.command.reason)
        assertEquals(f.prepared.preview.revision, first.command.previewRevision)
        assertEquals(f.prepared.selection, first.command.selection)
        assertEquals(1, f.inserts)
        assertEquals(
            "client.leave_submission_pending",
            assertIs<AppResult.Failed>(f.queue(reason = "Changed")).failure.code,
        )
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun invalidInputsEvidenceAndReadOnlyAccessNeverCreateACommand() = runTest {
        val f = LeaveSubmissionFixture()
        for (result in
            listOf(
                f.queue(reason = ""),
                f.queue(reason = "x".repeat(1_001)),
                f.queue(attachments = listOf("../other")),
                f.queue(
                    preview = f.prepared.copy(preview = f.prepared.preview.copy(revision = ""))
                ),
                f.queue(
                    preview =
                        f.prepared.copy(
                            preview =
                                f.prepared.preview.copy(
                                    policy =
                                        f.prepared.preview.policy.copy(attachmentRequired = true)
                                )
                        )
                ),
            )) {
            assertIs<AppResult.Failed>(result)
        }
        f.base.workspaceState.value =
            AppResult.Success(
                f.base.workspace.copy(permissions = setOf("leave.read", "leave.manage"))
            )
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.queue()).failure.code)
        assertTrue(f.records.value.isEmpty())
        assertEquals(0, f.inserts)
    }

    @Test
    fun inputIsCopiedBeforeWaitingForStorage() = runTest {
        val f = LeaveSubmissionFixture()
        val days = f.prepared.selection.days.toMutableList()
        val attachments = mutableListOf("20000000-0000-4000-8000-000000000001")
        val release = CompletableDeferred<Unit>()
        f.beforeObserve = { release.await() }
        val pending = async {
            f.queue(
                preview = f.prepared.copy(selection = f.prepared.selection.copy(days = days)),
                attachments = attachments,
            )
        }
        runCurrent()
        days.clear()
        attachments.clear()
        release.complete(Unit)
        val stored = assertIs<AppResult.Success<LeaveSubmissionRecord>>(pending.await()).value
        assertEquals(f.prepared.selection.days, stored.command.selection.days)
        assertEquals(
            listOf("20000000-0000-4000-8000-000000000001"),
            stored.command.attachmentRevisionIds,
        )
    }

    @Test
    fun expirationDuringAdmissionCannotCreateAnIntent() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveSubmissionFixture { now }
        val prepared = f.prepared.copy(expiresAt = now + 1.seconds)
        f.beforeObserve = { now += 2.seconds }
        assertEquals(
            "client.leave_preview_expired",
            assertIs<AppResult.Failed>(f.queue(preview = prepared)).failure.code,
        )
        assertEquals(0, f.inserts)
    }

    @Test
    fun replacedCompanySessionAndWritePermissionAreRecheckedAfterStorageWaits() = runTest {
        for (change in listOf("company", "session", "permission")) {
            val f = LeaveSubmissionFixture()
            val release = CompletableDeferred<Unit>()
            f.beforeObserve = { release.await() }
            val pending = async { f.queue() }
            runCurrent()
            when (change) {
                "company" ->
                    f.base.workspaceState.value =
                        AppResult.Success(f.base.workspace.copy(id = "replacement"))
                "session" ->
                    f.base.sessionState.value =
                        AppResult.Success(f.base.session.copy(id = "replacement"))
                else ->
                    f.base.workspaceState.value =
                        AppResult.Success(f.base.workspace.copy(permissions = setOf("leave.read")))
            }
            release.complete(Unit)
            assertIs<AppResult.Failed>(pending.await())
            assertEquals(0, f.inserts)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun cancellationRejectsLateAdmissionAndReleasesTheStorageSubscription() = runTest {
        val f = LeaveSubmissionFixture()
        val release = CompletableDeferred<Unit>()
        f.beforeObserve = { withContext(NonCancellable) { release.await() } }
        val pending = async { f.queue() }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0, f.inserts)
        assertEquals(0, f.observers)
    }

    @Test
    fun lostLocalSaveResponseRestoresTheAlreadyStoredCommand() = runTest {
        val f = LeaveSubmissionFixture()
        f.insertionResponseLost = true
        assertIs<AppResult.Failed>(f.queue())
        val stored = f.records.value.single()
        f.insertionResponseLost = false
        assertEquals(stored, assertIs<AppResult.Success<LeaveSubmissionRecord>>(f.queue()).value)
        assertEquals(1, f.inserts)
    }

    @Test
    fun concurrentManualAndBackgroundDeliveryUseOneAttemptAfterAReceipt() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.transmit = {
            release.await()
            AppResult.Success(LeaveSubmissionReceipt(it.requestId, 0))
        }
        val manual = async { f.send() }
        runCurrent()
        val background = async { f.replay() }
        runCurrent()
        assertEquals(1, f.transmitted.size)
        release.complete(Unit)
        assertIs<AppResult.Success<LeaveSubmissionRecord>>(manual.await())
        assertEquals(SyncResult.Complete, background.await())
        assertEquals(1, f.transmitted.size)
    }

    @Test
    fun aLostCommitResponseRecoversTheOriginalOperationAfterOwnerRecreation() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        val receipts = mutableSetOf<String>()
        var commits = 0
        f.transmit = {
            if (receipts.add(it.id)) {
                commits++
                AppResult.Failed(Failure(FailureKind.Network, "Response lost"))
            } else AppResult.Success(LeaveSubmissionReceipt(it.requestId, 0))
        }
        assertIs<LeaveCommandOutcome.Unconfirmed>(
            assertIs<AppResult.Success<LeaveSubmissionRecord>>(f.send()).value.outcome
        )
        val command = f.records.value.single().command
        val restored =
            ReplayLeaveSubmission(
                f.commands,
                f.remote,
                f.base.sessions,
                f.base.workspaces,
                f.base.clock,
                f.base.build,
            )
        val result =
            assertIs<AppResult.Success<LeaveSubmissionRecord>>(
                    restored(f.base.workspace.id, command.id)
                )
                .value
        assertEquals(LeaveCommandOutcome.Confirmed(0), result.outcome)
        assertEquals(1, commits)
        assertEquals(listOf(command, command), f.transmitted)
    }

    @Test
    fun changedPreviewIsDefinitiveOnlyBeforeAnEarlierAmbiguousAttempt() = runTest {
        for (ambiguous in listOf(false, true)) {
            val f = LeaveSubmissionFixture()
            f.queue()
            if (ambiguous) {
                f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Connection lost")) }
                f.send()
            }
            f.transmit = {
                AppResult.Failed(
                    Failure(FailureKind.Conflict, "Preview changed", code = "leave_preview_changed")
                )
            }
            val outcome = assertIs<AppResult.Success<LeaveSubmissionRecord>>(f.send()).value.outcome
            if (ambiguous) {
                assertIs<LeaveCommandOutcome.Unconfirmed>(outcome)
                assertIs<AppResult.Failed>(f.clear())
                assertEquals(1, f.records.value.size)
                assertEquals(f.transmitted.first(), f.transmitted.last())
            } else {
                assertIs<LeaveCommandOutcome.Rejected>(outcome)
                assertEquals(AppResult.Success(true), f.clear())
                assertTrue(f.records.value.isEmpty())
                assertEquals(0, f.request.reads)
            }
        }
    }

    @Test
    fun aReceiptAfterCompanyReplacementIsRetainedButCannotReachTheOldOwner() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        f.transmit = {
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "replacement"))
            AppResult.Success(LeaveSubmissionReceipt(it.requestId, 0))
        }
        assertIs<AppResult.Failed>(f.send())
        assertEquals(LeaveCommandOutcome.Confirmed(0), f.records.value.single().outcome)
        f.base.workspaceState.value = AppResult.Success(f.base.workspace)
        f.send()
        assertEquals(1, f.transmitted.size)
    }

    @Test
    fun cancellationAndTimeoutLeaveTheOriginalUnconfirmedInputAvailable() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.transmit = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(LeaveSubmissionReceipt(it.requestId, 0))
        }
        val pending = async { f.send() }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertIs<LeaveCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        f.transmit = { awaitCancellation() }
        assertEquals("client.leave_timeout", assertIs<AppResult.Failed>(f.send()).failure.code)
        assertEquals(2, f.transmitted.size)
        assertEquals(f.transmitted.first(), f.transmitted.last())
    }

    @Test
    fun currentModuleAdmissionStopsDeliveryBeforeAnyAttemptIsSent() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        f.base.policyCall = {
            AppResult.Success(f.base.policy.copy(enabledModules = setOf(CompanyModule.PEOPLE)))
        }
        assertIs<AppResult.Failed>(f.send())
        assertEquals(LeaveCommandOutcome.Pending, f.records.value.single().outcome)
        assertEquals(0, f.records.value.single().attempts)
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun failedReceiptStorageCannotInventConfirmationOrReplaceTheOriginalInput() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        f.beforeUpdate = {
            if (it is LeaveCommandOutcome.Confirmed)
                f.updateFailure = Failure(FailureKind.Storage, "Storage failed")
        }
        assertIs<AppResult.Failed>(f.send())
        assertIs<LeaveCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        f.beforeUpdate = {}
        f.updateFailure = null
        assertEquals(
            LeaveCommandOutcome.Confirmed(0),
            assertIs<AppResult.Success<LeaveSubmissionRecord>>(f.send()).value.outcome,
        )
        assertEquals(f.transmitted.first(), f.transmitted.last())
    }

    @Test
    fun confirmedSubmissionRequiresReconciledDetailsAndRemovalCannotWinOverReplay() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        f.beforeRemove = { f.send() }
        assertEquals(AppResult.Success(false), f.clear())
        f.beforeRemove = {}
        assertIs<LeaveCommandOutcome.Confirmed>(f.records.value.single().outcome)
        assertIs<AppResult.Failed>(f.clear())
        f.publish()
        assertEquals(AppResult.Success(true), f.clear())
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun automaticRetryLimitsAreFiniteAndDoNotReplaceTheSubmission() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
        repeat(5) { f.replay() }
        assertEquals(3, f.transmitted.size)
        val stored = f.records.value.single()
        f.records.value = listOf(stored.copy(attempts = 1_000))
        assertEquals("client.leave_retry_limit", assertIs<AppResult.Failed>(f.send()).failure.code)
        assertEquals(3, f.transmitted.size)
    }

    @Test
    fun aLateStorageFailureCannotBeReportedAsBelongingToTheReplacementCompany() = runTest {
        val f = LeaveSubmissionFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.beforeFind = { release.await() }
        f.findFailure = Failure(FailureKind.Storage, "Previous company read failed")
        val pending = async { f.send() }
        runCurrent()
        f.base.workspaceState.value = AppResult.Success(f.base.workspace.copy(id = "replacement"))
        release.complete(Unit)
        assertEquals(
            "client.workspace_changed",
            assertIs<AppResult.Failed>(pending.await()).failure.code,
        )
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun observationExpiresAndDisposesItsAccountOwnedSubscription() = runTest {
        val origin = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveSubmissionFixture { origin + testScheduler.currentTime.milliseconds }
        f.queue()
        val states = mutableListOf<AppResult<LeaveSubmissionRecord?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.base.workspace.id, f.prepared.selection.employeeId).toList(states)
            }
        assertEquals(1, f.observers)
        assertIs<AppResult.Success<LeaveSubmissionRecord?>>(states.last())
        advanceTimeBy(1.hours)
        runCurrent()
        assertIs<AppResult.Failed>(states.last())
        f.base.sessionState.value = AppResult.Success(null)
        runCurrent()
        assertEquals(0, f.observers)
        job.cancelAndJoin()
    }
}
