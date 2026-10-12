package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class OvertimeActionViewModelTest {
    @Test
    fun actualInputStartsFromThePlanAndDuplicateSubmissionFreezesItsVersion() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            f.persistence.transmit = {
                release.await()
                f.accept(it)
            }
            val vm = f.create(store)
            val effects = mutableListOf<OvertimeActionEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.toList(effects)
            }
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertEquals(LocalDateTime(2026, 10, 12, 17, 0), vm.state.value.form?.actual?.startsAt)
            vm.onEvent(OvertimeActionEvent.TimePickerRequested(OvertimeActualField.START))
            vm.onEvent(OvertimeActionEvent.MinutesChanged("15"))
            vm.onEvent(OvertimeActionEvent.TimeConfirmed)
            vm.onEvent(OvertimeActionEvent.BreakChanged("15"))
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Inventory completed"))
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Changed"))
            runCurrent()
            val command = f.persistence.transmitted.single()
            val payload = assertIs<OvertimeCommandPayload.Actual>(command.payload)
            assertEquals(0L, payload.expectedVersion)
            assertEquals(Instant.parse("2026-10-12T10:15:00Z"), payload.actual.startsAt)
            assertEquals(90, payload.actual.workedMinutes)
            assertEquals("Inventory completed", command.reason)
            assertFalse(vm.state.value.editable)
            release.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(null), f.persistence.retained.value)
            assertEquals(listOf<OvertimeActionEffect>(OvertimeActionEffect.Back), effects)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aRestoredActualCommandCannotBeReplacedByWithdrawal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var store = ViewModelStore()
        try {
            val f = OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            f.persistence.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            var vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Count completed"))
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            val original = f.persistence.transmitted.single()
            store.clear()
            runCurrent()
            store = ViewModelStore()
            vm = f.create(store, OvertimeActionKind.WITHDRAW)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertEquals(OvertimeActionKind.ACTUAL, vm.state.value.kind)
            assertEquals(original, vm.state.value.command?.command)
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Withdraw instead"))
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            assertEquals(1, f.persistence.transmitted.size)
            f.persistence.transmit = { f.accept(it) }
            vm.onEvent(OvertimeActionEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(original, original), f.persistence.transmitted)
            assertEquals(OvertimeStatus.PENDING, f.latest.request.status)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun withdrawalRequiresAnExplicitReasonAndRecoversItsLostSuccessfulResponse() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var store = ViewModelStore()
        try {
            val f =
                OvertimeActionFixture(
                    StandardTestDispatcher(testScheduler),
                    OvertimeActionKind.WITHDRAW,
                )
            f.persistence.transmit = {
                f.accept(it)
                AppResult.Failed(Failure(FailureKind.Network, "Lost response"))
            }
            var vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertNull(vm.state.value.form?.actual)
            assertTrue(f.persistence.transmitted.isEmpty())
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            assertTrue(f.persistence.transmitted.isEmpty())
            assertEquals("invalid_overtime_request", vm.state.value.failure?.code)
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Work is no longer required"))
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            val original = f.persistence.transmitted.single()
            assertIs<OvertimeCommandPayload.Withdrawal>(original.payload)
            assertEquals(OvertimeStatus.WITHDRAWN, f.latest.request.status)
            store.clear()
            runCurrent()
            store = ViewModelStore()
            vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertFalse(vm.state.value.available)
            assertTrue(vm.state.value.canRetry)
            assertFalse(vm.state.value.canDiscard)
            f.persistence.transmit = { f.accept(it) }
            vm.onEvent(OvertimeActionEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(original, original), f.persistence.transmitted)
            assertEquals(1L, f.latest.request.version)
            assertEquals(AppResult.Success(null), f.persistence.retained.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun backCancelsPendingDeliveryAndRejectsItsLateReceipt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f =
                OvertimeActionFixture(
                    StandardTestDispatcher(testScheduler),
                    OvertimeActionKind.WITHDRAW,
                )
            val release = CompletableDeferred<Unit>()
            f.persistence.transmit = {
                withContext(NonCancellable) { release.await() }
                f.accept(it)
            }
            val vm = f.create(store)
            val effects = mutableListOf<OvertimeActionEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.toList(effects)
            }
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Work rescheduled"))
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            vm.onEvent(OvertimeActionEvent.BackRequested)
            vm.onEvent(OvertimeActionEvent.BackRequested)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf<OvertimeActionEffect>(OvertimeActionEffect.Back), effects)
            assertEquals(0, f.base.observers)
            assertEquals(0, f.persistence.observers)
            assertIs<OvertimeCommandOutcome.Unconfirmed>(
                assertIs<AppResult.Success<OvertimeCommandRecord?>>(f.persistence.retained.value)
                    .value
                    ?.outcome
            )
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aStoppedAcquisitionCannotPublishDenialIntoTheNextEntry() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            f.base.detailsCall = {
                withContext(NonCancellable) { release.await() }
                AppResult.Failed(Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"))
            }
            val vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            vm.onEvent(OvertimeActionEvent.Stopped)
            runCurrent()
            assertEquals(0, f.base.observers)
            assertEquals(0, f.persistence.observers)
            f.base.detailsCall = { AppResult.Success(f.latest) }
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.editable)
            assertNull(vm.state.value.restriction)
            assertNull(vm.state.value.failure)
            assertEquals(1, f.base.observers)
            assertEquals(1, f.persistence.observers)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun deniedReadsRemainHiddenWhileRecoveryIsPendingOrFails() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            f.persistence.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Private draft"))
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            val retained = f.base.detailRecords.value
            f.base.failCleanup = true
            f.base.detailRecords.value =
                AppResult.Failed(Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required"))
            runCurrent()
            f.base.detailRecords.value = retained
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.content)
            assertNull(vm.state.value.command)
            assertNull(vm.state.value.form)
            val release = CompletableDeferred<Unit>()
            f.base.detailsCall = {
                release.await()
                AppResult.Failed(Failure(FailureKind.Network, "Offline"))
            }
            vm.onEvent(OvertimeActionEvent.RefreshRequested)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.command)
            f.base.detailsCall = { AppResult.Success(f.latest) }
            vm.onEvent(OvertimeActionEvent.RefreshRequested)
            runCurrent()
            assertNull(vm.state.value.restriction)
            assertNotNull(vm.state.value.command)
            assertTrue(vm.state.value.canRetry)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun requestChangesCannotSilentlyRebaseAnOpenForm() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f =
                OvertimeActionFixture(
                    StandardTestDispatcher(testScheduler),
                    OvertimeActionKind.WITHDRAW,
                )
            val vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Original draft"))
            val approvalId = "88888888-8888-4888-8888-888888888888"
            f.latest =
                f.latest.copy(
                    request =
                        f.latest.request.copy(
                            version = 1,
                            status = OvertimeStatus.PENDING,
                            actual = f.base.interval,
                            submittedBy = f.base.session.user.id,
                            submittedAt = f.base.clock.now(),
                            approvalId = approvalId,
                        ),
                    approval =
                        OvertimeApproval(approvalId, OvertimeApprovalStatus.PENDING, 0, 1, 0),
                    actions = setOf(OvertimeAction.WITHDRAW),
                )
            vm.onEvent(OvertimeActionEvent.RefreshRequested)
            runCurrent()
            assertTrue(vm.state.value.requestChanged)
            assertEquals("Original draft", vm.state.value.form?.reason)
            assertFalse(vm.state.value.canSubmit)
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            assertTrue(f.persistence.transmitted.isEmpty())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun confirmedDeliveryRemainsReviewableUntilAdmittedDetailsCanBeLoaded() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            val vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            vm.onEvent(OvertimeActionEvent.ReasonChanged("Work completed"))
            f.base.detailsCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            vm.onEvent(OvertimeActionEvent.SubmitRequested)
            runCurrent()
            assertIs<OvertimeCommandOutcome.Confirmed>(vm.state.value.command?.outcome)
            assertFalse(vm.state.value.canRetry)
            assertNotNull(vm.state.value.failure)
            f.base.detailsCall = { AppResult.Success(f.latest) }
            vm.onEvent(OvertimeActionEvent.OpenRequestRequested)
            runCurrent()
            assertEquals(AppResult.Success(null), f.persistence.retained.value)
            assertEquals(1, f.persistence.transmitted.size)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun anExplicitAdmittedRefreshReopensCompletedCommandStorage() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimeActionFixture(StandardTestDispatcher(testScheduler))
            f.persistence.terminalObservationFailure = true
            f.base.detailsCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val vm = f.create(store)
            vm.onEvent(OvertimeActionEvent.Entered)
            runCurrent()
            assertFalse(vm.state.value.commandReady)
            assertNotNull(vm.state.value.failure)
            f.base.detailsCall = { AppResult.Success(f.latest) }
            vm.onEvent(OvertimeActionEvent.RefreshRequested)
            runCurrent()
            assertTrue(vm.state.value.commandReady)
            assertTrue(vm.state.value.editable)
            assertEquals(1, f.persistence.observers)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
