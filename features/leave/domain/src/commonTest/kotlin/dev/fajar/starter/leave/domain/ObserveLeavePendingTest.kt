@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.usecases.ObserveLeavePending
import dev.fajar.starter.leave.domain.working.entities.*
import dev.fajar.starter.leave.domain.working.repositories.LeaveWorkingCopyRepository
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveLeavePendingTest {
    @Test
    fun countsRetainedOutcomesAndOnlyDraftsNotAlreadyFrozenInASubmission() = runTest {
        val f = PendingLeaveFixture()
        assertIs<AppResult.Success<*>>(f.submissions.queue())
        val saved = f.submissions.records.value.single()
        f.submissions.records.value =
            listOf(saved.copy(command = saved.command.copy(workingRevision = f.copy.revision)))
        f.pages.value = AppResult.Success(LeavePage(listOf(f.copy)))
        assertEquals(PendingActionSummary(queued = 1), f.summary())
        f.submissions.records.value =
            listOf(
                saved.copy(
                    command = saved.command.copy(workingRevision = f.copy.revision),
                    outcome = LeaveCommandOutcome.Confirmed(1),
                )
            )
        assertEquals(PendingActionSummary(), f.summary())
        f.pages.value =
            AppResult.Success(LeavePage(listOf(f.copy.copy(revision = "newer-edit")), "more"))
        assertEquals(PendingActionSummary(drafts = 1, moreDrafts = true), f.summary())
        f.pages.value = AppResult.Success(LeavePage(listOf(f.copy.copy(hasEdits = false))))
        val failure = Failure(FailureKind.Network, "No response")
        f.submissions.records.value =
            listOf(saved.copy(outcome = LeaveCommandOutcome.Unconfirmed(failure)))
        assertIs<AppResult.Success<*>>(f.actions.queue())
        f.actions.records.value =
            f.actions.records.value.map { it.copy(outcome = LeaveCommandOutcome.Rejected(failure)) }
        assertEquals(PendingActionSummary(unconfirmed = 1, needsReview = 1), f.summary())
        assertTrue(f.submissions.transmitted.isEmpty())
    }

    @Test
    fun ownedDraftsRemainReadableAfterAgeAndCredentialExpiryWithoutHttp() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = PendingLeaveFixture { now }
        f.pages.value = AppResult.Success(LeavePage(listOf(f.copy)))
        now += 30.days
        f.base.policyCall = { error("Local summary cannot acquire policy") }
        assertEquals(PendingActionSummary(drafts = 1), f.summary())
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.base.directoryReads)
    }

    @Test
    fun denialSurvivesFailedPersistenceAndRetryUntilAdmittedAcquisition() = runTest {
        val f = PendingLeaveFixture()
        f.pages.value = AppResult.Success(LeavePage(listOf(f.copy)))
        val values = mutableListOf<AppResult<PendingActionSummary>>()
        val job = backgroundScope.launch { f.observe().collect { values += it } }
        runCurrent()
        assertEquals(
            1,
            assertIs<AppResult.Success<PendingActionSummary>>(values.last()).value.drafts,
        )
        val denial = Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required")
        f.base.replica.failAdmissionWrite = true
        f.base.replica.restrict(f.submissions.access, denial)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(0, f.submissions.observers)
        assertEquals(AppResult.Failed(denial), f.observe().first())
        val admission =
            assertIs<AppResult.Success<LeaveReadAdmission>>(
                    f.base.replica.admission(f.submissions.access)
                )
                .value
        assertIs<AppResult.Failed>(f.base.replica.admit(f.submissions.access, admission.revision))
        assertEquals(AppResult.Failed(denial), f.observe().first())
        f.base.replica.failAdmissionWrite = false
        f.base.replica.admit(f.submissions.access, admission.revision)
        runCurrent()
        assertEquals(
            1,
            assertIs<AppResult.Success<PendingActionSummary>>(values.last()).value.drafts,
        )
        job.cancelAndJoin()
        assertEquals(0, f.submissions.observers)
        assertEquals(0, f.base.replica.admissionObservers)
    }

    @Test
    fun accountReplacementDiscardsPendingOldReadAndRestoresAfterReauthentication() = runTest {
        val f = PendingLeaveFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        f.pages.value = AppResult.Success(LeavePage(listOf(f.copy)))
        val values = mutableListOf<AppResult<PendingActionSummary>>()
        val job = backgroundScope.launch { f.observe().collect { values += it } }
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            f.base.sessionState.value = AppResult.Success(null)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(values.none { it is AppResult.Success })
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(values.last()).failure.kind,
            )
            f.base.sessionState.value = AppResult.Success(f.base.session)
            runCurrent()
            assertEquals(
                1,
                assertIs<AppResult.Success<PendingActionSummary>>(values.last()).value.drafts,
            )
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "other-workspace"))
            runCurrent()
            assertIs<AppResult.Failed>(values.last())
            assertEquals(0, f.submissions.observers)
        } finally {
            release.complete(Unit)
            job.cancelAndJoin()
        }
    }
}

private class PendingLeaveFixture(
    instant: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }
) {
    val submissions = LeaveSubmissionFixture(instant)
    val actions = LeaveCommandFixture(instant)
    val base = submissions.base
    val copy =
        LeaveWorkingCopy(
            base.employee.id,
            LeaveWorkingInput(base.directory.asOf, base.directory.asOf),
            "edited-revision",
            base.clock.now(),
        )
    val pages =
        MutableStateFlow<AppResult<LeavePage<LeaveWorkingCopy>>>(
            AppResult.Success(LeavePage(emptyList()))
        )
    var beforeRead: suspend () -> Unit = {}
    val copies =
        object : LeaveWorkingCopyRepository by submissions.working.copies {
            override fun observePage(access: LeaveAccess, after: String?) = flow {
                beforeRead()
                emitAll(pages)
            }
        }

    fun observe() =
        ObserveLeavePending(
                copies,
                submissions.commands,
                actions.commands,
                base.replica,
                base.sessions,
                base.workspaces,
                base.clock,
                base.build,
            )
            .observePending(base.workspace.id)

    suspend fun summary() =
        assertIs<AppResult.Success<PendingActionSummary>>(observe().first()).value
}
