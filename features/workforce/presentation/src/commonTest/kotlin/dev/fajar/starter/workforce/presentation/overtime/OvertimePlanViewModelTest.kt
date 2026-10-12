package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

@OptIn(ExperimentalCoroutinesApi::class)
class OvertimePlanViewModelTest {
    @Test
    fun leavingDuringReviewRejectsItsLateFailureAfterAFreshEntry() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            val accepted = f.read
            val release = CompletableDeferred<Unit>()
            f.read = {
                withContext(NonCancellable) { release.await() }
                AppResult.Failed(Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"))
            }
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertEquals(OvertimePlanWork.REVIEW, vm.state.value.work)
            vm.onEvent(OvertimePlanEvent.Stopped)
            runCurrent()
            assertEquals(0, f.observers)
            assertNull(vm.state.value.prepared)
            f.read = accepted
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            val current = assertNotNull(vm.state.value.prepared)
            release.complete(Unit)
            runCurrent()
            assertEquals(current, vm.state.value.prepared)
            assertNull(vm.state.value.failure)
            assertNull(vm.state.value.restriction)
            assertTrue(vm.state.value.editable)
            assertEquals(1, f.observers)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun theFormKeepsControlledTimesAndStopsItsResourcesOnDisposal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertEquals(1, f.reads)
            assertNotNull(vm.state.value.prepared)
            assertTrue(vm.state.value.editable)
            f.fill(vm)
            assertEquals(LocalTime(18, 30), vm.state.value.form?.startsAt)
            assertEquals(LocalTime(20, 30), vm.state.value.form?.endsAt)
            vm.onEvent(OvertimePlanEvent.DatePickerRequested(OvertimePlanDateField.WORK))
            vm.onEvent(OvertimePlanEvent.DateSelected(12))
            assertEquals(LocalTime(18, 30), vm.state.value.form?.startsAt)
            vm.onEvent(OvertimePlanEvent.TimePickerRequested(OvertimeTimeField.START))
            vm.onEvent(OvertimePlanEvent.HoursChanged("99"))
            assertNull((vm.state.value.dialog as OvertimePlanDialog.Time).value)
            vm.onEvent(OvertimePlanEvent.TimeConfirmed)
            assertNotNull(vm.state.value.dialog)
            vm.onEvent(OvertimePlanEvent.HoursChanged("99x"))
            assertEquals("99", (vm.state.value.dialog as OvertimePlanDialog.Time).hours)
            store.clear()
            runCurrent()
            assertEquals(0, f.observers)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun duplicateSubmissionsFreezeEditsAndRetainTheFirstInput() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            val vm = f.create(store)
            val release = CompletableDeferred<Unit>()
            f.transmit = {
                release.await()
                AppResult.Failed(Failure(FailureKind.Network, "Offline"))
            }
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            f.fill(vm)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            vm.onEvent(OvertimePlanEvent.ReasonChanged("Changed"))
            runCurrent()
            assertEquals(1, f.transmitted.size)
            assertFalse(vm.state.value.editable)
            assertEquals("Inventory count", f.transmitted.single().reason)
            assertEquals(
                Instant.parse("2026-10-12T11:30:00Z"),
                (f.transmitted.single().payload as OvertimeCommandPayload.Plan).requested.startsAt,
            )
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.canRetry)
            assertFalse(vm.state.value.canDiscard)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun anOfflineCommandRestoresWithoutReReviewAndRetriesTheOriginalInput() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            val complete = f.transmit
            f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            var vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            f.fill(vm)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            runCurrent()
            val original = f.transmitted.single()
            val reviews = f.reads
            store.clear()
            runCurrent()
            store = ViewModelStore()
            vm = f.create(store)
            val effects = mutableListOf<OvertimePlanEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.toList(effects)
            }
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertEquals(reviews, f.reads)
            assertEquals(original, vm.state.value.command?.command)
            assertTrue(vm.state.value.canRetry)
            f.transmit = complete
            vm.onEvent(OvertimePlanEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(original, original), f.transmitted)
            assertEquals(AppResult.Success(null), f.retained.value)
            assertEquals(
                original.requestId,
                assertIs<OvertimePlanEffect.OpenRequest>(effects.single()).selection.requestId,
            )
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun backCancelsDeliveryAndCannotNavigateAgainAfterALateReceipt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            f.transmit = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(OvertimeCommandReceipt(it.requestId, 0))
            }
            val vm = f.create(store)
            val effects = mutableListOf<OvertimePlanEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.toList(effects)
            }
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            f.fill(vm)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            runCurrent()
            vm.onEvent(OvertimePlanEvent.BackRequested)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf<OvertimePlanEffect>(OvertimePlanEffect.Back), effects)
            assertEquals(0, f.observers)
            assertIs<OvertimeCommandOutcome.Unconfirmed>(
                assertIs<AppResult.Success<OvertimeCommandRecord?>>(f.retained.value)
                    .value!!
                    .outcome
            )
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun expiredReviewsCannotSubmitAndStopCancelsTheExpirationTimer() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val origin = Instant.parse("2026-10-12T13:00:00Z")
            val f =
                OvertimePlanFixture(StandardTestDispatcher(testScheduler)) {
                    origin + testScheduler.currentTime.milliseconds
                }
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            f.fill(vm)
            advanceTimeBy(60.seconds)
            runCurrent()
            assertTrue(vm.state.value.reviewExpired)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            runCurrent()
            assertTrue(f.transmitted.isEmpty())
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertFalse(vm.state.value.reviewExpired)
            vm.onEvent(OvertimePlanEvent.Stopped)
            runCurrent()
            advanceTimeBy(60.seconds)
            runCurrent()
            assertNull(vm.state.value.prepared)
            assertEquals(0, f.observers)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun denialRemainsGatedThroughPendingAndFailedRecoveryDespiteRetainedInput() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            f.fill(vm)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            runCurrent()
            val stored = f.retained.value
            f.retained.value =
                AppResult.Failed(Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required"))
            runCurrent()
            f.retained.value = stored
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.command)
            assertEquals(0, f.observers)
            val accepted = f.read
            val release = CompletableDeferred<Unit>()
            f.read = {
                release.await()
                AppResult.Failed(Failure(FailureKind.Network, "Offline"))
            }
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertNull(vm.state.value.command)
            f.read = accepted
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertNull(vm.state.value.restriction)
            assertNotNull(vm.state.value.command)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aCompletedStorageFailureReopensAfterExplicitAdmittedReview() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            f.terminalObservationFailure = true
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            assertFalse(vm.state.value.commandReady)
            assertNotNull(vm.state.value.failure)
            vm.onEvent(OvertimePlanEvent.ReloadRequested)
            runCurrent()
            assertTrue(vm.state.value.commandReady)
            assertTrue(vm.state.value.editable)
            assertEquals(1, f.observers)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aConfirmedCommandStaysReviewableWhenRequestRefreshFails() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            f.transmit = { AppResult.Success(OvertimeCommandReceipt(it.requestId, 0)) }
            f.base.detailsCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            f.fill(vm)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            runCurrent()
            assertIs<OvertimeCommandOutcome.Confirmed>(vm.state.value.command?.outcome)
            assertFalse(vm.state.value.canRetry)
            assertNotNull(vm.state.value.failure)
            f.base.detailsCall = { selection ->
                AppResult.Success(
                    f.base.details.copy(request = f.base.request.copy(id = selection.requestId))
                )
            }
            vm.onEvent(OvertimePlanEvent.OpenRequestRequested)
            runCurrent()
            assertEquals(AppResult.Success(null), f.retained.value)
            assertEquals(1, f.transmitted.size)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rejectedInputNeedsExplicitDiscardConfirmationBeforeAReplacementPlan() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = OvertimePlanFixture(StandardTestDispatcher(testScheduler))
            f.transmit = {
                AppResult.Failed(
                    Failure(
                        FailureKind.Conflict,
                        "Schedule changed",
                        code = "overtime_planning_changed",
                    )
                )
            }
            val vm = f.create(store)
            vm.onEvent(OvertimePlanEvent.Entered)
            runCurrent()
            f.fill(vm)
            vm.onEvent(OvertimePlanEvent.SubmitRequested)
            runCurrent()
            assertTrue(vm.state.value.canDiscard)
            vm.onEvent(OvertimePlanEvent.DiscardConfirmed)
            runCurrent()
            assertNotNull(vm.state.value.command)
            vm.onEvent(OvertimePlanEvent.DiscardRequested)
            assertEquals(OvertimePlanDialog.Discard, vm.state.value.dialog)
            vm.onEvent(OvertimePlanEvent.DiscardConfirmed)
            runCurrent()
            assertEquals(AppResult.Success(null), f.retained.value)
            assertTrue(vm.state.value.editable)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
