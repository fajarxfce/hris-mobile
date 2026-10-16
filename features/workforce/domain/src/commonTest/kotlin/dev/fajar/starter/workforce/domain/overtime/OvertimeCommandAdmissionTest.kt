@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class OvertimeCommandAdmissionTest {
    private val denied = overtimeFailure("mfa_required", FailureKind.AccessDenied)

    @Test
    fun restrictionClosesCommandsAndPreventsPreparationDeliveryAndDiscard() = runTest {
        val f = OvertimeCommandFixture()
        assertIs<AppResult.Success<*>>(f.queue())
        val retained = f.records.value
        val values = mutableListOf<AppResult<OvertimeCommandRecord?>>()
        val owner =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, retained.single().command.slot()).toList(values)
            }
        assertEquals(AppResult.Success(retained.single()), values.last())
        f.overtime.replicas.failAdmissionWrite = true
        f.overtime.replicas.restrict(f.access, denied.failure)
        runCurrent()
        assertEquals(denied, values.last())
        assertEquals(0, f.observers)
        assertEquals(denied, f.queue())
        assertEquals(denied, f.queueAction())
        assertEquals(denied, f.send())
        assertEquals(denied, f.clear())
        assertEquals(retained, f.records.value)
        assertTrue(f.transmitted.isEmpty())
        assertEquals(1, f.inserts)
        assertEquals(AppResult.Success(Unit), f.overtime.sync.recoverAccess(f.workspace.id))
        runCurrent()
        assertEquals(AppResult.Success(retained.single()), values.last())
        owner.cancelAndJoin()
        assertEquals(0, f.observers)
        assertEquals(0, f.overtime.replicas.admissionObservers)
    }

    @Test
    fun denialDuringACommandReadPreventsNewPlanAndActionInsertion() = runTest {
        for (action in listOf(false, true)) {
            val f = OvertimeCommandFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeObserve = {
                entered.complete(Unit)
                release.await()
            }
            val pending = async { if (action) f.queueAction() else f.queue() }
            entered.await()
            f.overtime.replicas.restrict(f.access, denied.failure)
            release.complete(Unit)
            assertEquals(denied, pending.await())
            assertEquals(0, f.inserts)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun aLateSavedIntentStaysRetainedAndCannotBeDeliveredBeforeReadmission() = runTest {
        val f = OvertimeCommandFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeInsert = {
            entered.complete(Unit)
            release.await()
        }
        val pending = async { f.queue() }
        entered.await()
        f.overtime.replicas.restrict(f.access, denied.failure)
        release.complete(Unit)
        assertEquals(denied, pending.await())
        val retained = f.records.value.single()
        assertEquals(denied, f.send())
        assertTrue(f.transmitted.isEmpty())
        assertEquals(AppResult.Success(Unit), f.overtime.sync.recoverAccess(f.workspace.id))
        assertEquals(AppResult.Success(retained), f.queue())
        assertEquals(1, f.inserts)
    }

    @Test
    fun aPolicyRejectionRetainsTheUnsentCommandAndRequiresSharedRecovery() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val retained = f.records.value
        val original = f.base.policyCall
        f.base.policyCall = { denied }
        assertEquals(denied, f.send())
        assertEquals(denied, f.observe(f.workspace.id, retained.single().command.slot()).first())
        assertEquals(retained, f.records.value)
        assertTrue(f.transmitted.isEmpty())
        f.base.policyCall = original
        assertEquals(denied, f.send())
        assertEquals(AppResult.Success(Unit), f.overtime.sync.recoverAccess(f.workspace.id))
        assertIs<AppResult.Success<*>>(f.send())
        assertEquals(listOf(retained.single().command), f.transmitted)
    }

    @Test
    fun aSubmissionRejectionPreservesPriorUncertaintyAndOriginalOperation() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val command = f.records.value.single().command
        f.transmit = { overtimeFailure("offline", FailureKind.Network) }
        assertIs<AppResult.Success<*>>(f.send())
        assertIs<OvertimeCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        f.transmit = { denied }
        assertEquals(denied, f.send())
        val retained = f.records.value.single()
        assertIs<OvertimeCommandOutcome.Unconfirmed>(retained.outcome)
        assertEquals(2, retained.attempts)
        assertEquals(command, retained.command)
        assertEquals(denied, f.clear())
        assertEquals(denied, f.observe(f.workspace.id, command.slot()).first())
        assertEquals(listOf(command, command), f.transmitted)
        assertEquals(AppResult.Success(Unit), f.overtime.sync.recoverAccess(f.workspace.id))
        f.transmit = {
            AppResult.Success(OvertimeCommandReceipt(it.requestId, it.expectedReceiptVersion()))
        }
        assertIs<AppResult.Success<*>>(f.send())
        assertIs<OvertimeCommandOutcome.Confirmed>(f.records.value.single().outcome)
        assertEquals(listOf(command, command, command), f.transmitted)
    }

    @Test
    fun aScheduleRejectionIsSharedAndAFreshReviewCannotReopenIt() = runTest {
        val f = OvertimePlanningFixture()
        f.overtime.replicas.failAdmissionWrite = true
        f.reviewCall = { denied }
        assertEquals(denied, f.load())
        assertEquals(denied, f.overtime.observe(f.overtime.workspace.id).first())
        f.reviewCall = { AppResult.Success(f.review) }
        assertEquals(denied, f.load())
        assertEquals(1, f.calls)
        assertEquals(
            AppResult.Success(Unit),
            f.overtime.sync.recoverAccess(f.overtime.workspace.id),
        )
        assertIs<AppResult.Success<*>>(f.load())
        assertEquals(2, f.calls)
    }

    @Test
    fun aPendingScheduleReviewCannotPublishAfterAnotherReadIsDenied() = runTest {
        val f = OvertimePlanningFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.reviewCall = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.review)
        }
        val pending = async { f.load() }
        entered.await()
        f.overtime.replicas.restrict(f.overtime.access, denied.failure)
        release.complete(Unit)
        assertEquals(denied, pending.await())
        assertEquals(denied, f.overtime.observe(f.overtime.workspace.id).first())
    }

    @Test
    fun aLateScheduleDenialCannotRestrictAReplacedWorkspace() = runTest {
        val f = OvertimePlanningFixture()
        f.reviewCall = {
            f.base.workspaceState.value =
                AppResult.Success(f.overtime.workspace.copy(id = "replacement"))
            denied
        }
        assertIs<AppResult.Failed>(f.load())
        assertNull(f.overtime.replicas.readAdmission.value.denial)
    }

    @Test
    fun aLateSubmissionDenialRetainsEvidenceWithoutRestrictingANewerSession() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val command = f.records.value.single().command
        f.transmit = {
            f.base.sessionState.value = AppResult.Success(f.base.session.copy(id = "replacement"))
            denied
        }
        assertIs<AppResult.Failed>(f.send())
        assertEquals(command, f.records.value.single().command)
        assertEquals(1, f.records.value.single().attempts)
        assertNull(f.overtime.replicas.readAdmission.value.denial)
    }
}
