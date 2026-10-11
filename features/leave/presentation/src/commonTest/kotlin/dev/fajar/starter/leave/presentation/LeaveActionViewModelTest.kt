@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.presentation.detail.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class LeaveActionViewModelTest {
    @Test
    fun confirmationValidatesTheReasonAndPublishesCanonicalStatusBeforeRetiringEvidence() =
        runTest {
            withActions { f, model, _ ->
                model.onEvent(LeaveRequestEvent.Entered)
                runCurrent()
                assertEquals(
                    listOf(LeaveRequestAction.WITHDRAW),
                    model.state.value.availableActions,
                )
                model.onEvent(LeaveRequestEvent.ActionRequested(LeaveRequestAction.WITHDRAW))
                model.onEvent(LeaveRequestEvent.ActionConfirmed)
                runCurrent()
                assertEquals(
                    "client.leave_reason_required",
                    model.state.value.action.form?.failure?.code,
                )
                assertTrue(f.transmitted.isEmpty())
                model.onEvent(LeaveRequestEvent.ReasonChanged("Plans changed"))
                model.onEvent(LeaveRequestEvent.ActionConfirmed)
                model.onEvent(LeaveRequestEvent.ActionConfirmed)
                runCurrent()
                assertEquals(1, f.transmitted.size)
                assertEquals(LeaveStatus.CANCELLED, model.state.value.content?.request?.status)
                assertNull(model.state.value.action.form)
                assertNull(model.state.value.action.command)
                assertEquals(1, f.removals)
                assertFalse(model.state.value.action.working)
                assertEquals(1, f.observers)
            }
        }

    @Test
    fun cancellationAndWithdrawalOfCancellationUseDifferentUserIntents() = runTest {
        withActions { f, model, _ ->
            f.canonical =
                f.canonical.copy(
                    status = LeaveStatus.APPROVED,
                    availableActions = setOf(LeaveAction.REQUEST_CANCELLATION),
                )
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            confirm(model, LeaveRequestAction.REQUEST_CANCELLATION)
            runCurrent()
            assertEquals(
                LeaveStatus.CANCELLATION_PENDING,
                model.state.value.content?.request?.status,
            )
            assertEquals(
                listOf(LeaveRequestAction.WITHDRAW_CANCELLATION),
                model.state.value.availableActions,
            )
            confirm(model, LeaveRequestAction.WITHDRAW_CANCELLATION)
            runCurrent()
            assertEquals(LeaveStatus.APPROVED, model.state.value.content?.request?.status)
            assertEquals(
                listOf(LeaveRequestAction.REQUEST_CANCELLATION),
                model.state.value.availableActions,
            )
            assertEquals(2, f.transmitted.size)
            assertNotEquals(f.transmitted.first().id, f.transmitted.last().id)
        }
    }

    @Test
    fun uncertainDeliveryKeepsTheOriginalCommandAcrossDepartureAndRetry() = runTest {
        withActions { f, model, _ ->
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            val online = f.transmit
            f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Unavailable")) }
            confirm(model)
            runCurrent()
            val command = model.state.value.action.command!!.command
            assertTrue(model.state.value.canRetryAction)
            assertFalse(model.state.value.canDiscardAction)
            assertEquals(LeaveStatus.PENDING, model.state.value.content?.request?.status)
            model.onEvent(LeaveRequestEvent.Stopped)
            runCurrent()
            assertEquals(0, f.observers)
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            assertEquals(command, model.state.value.action.command?.command)
            f.transmit = online
            model.onEvent(LeaveRequestEvent.RetryActionRequested)
            runCurrent()
            assertEquals(listOf(command, command), f.transmitted)
            assertEquals(LeaveStatus.CANCELLED, model.state.value.content?.request?.status)
            assertNull(model.state.value.action.command)
        }
    }

    @Test
    fun stoppedDeliveryDiscardsItsLateReceiptAndReleasesObservers() = runTest {
        withActions { f, model, _ ->
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.transmit = { command ->
                withContext(NonCancellable) { release.await() }
                AppResult.Success(LeaveActionReceipt(command.requestId, 1))
            }
            confirm(model)
            runCurrent()
            model.onEvent(LeaveRequestEvent.Stopped)
            runCurrent()
            assertEquals(0, f.observers)
            val stopped = model.state.value
            release.complete(Unit)
            runCurrent()
            assertEquals(stopped, model.state.value)
            assertIs<LeaveCommandOutcome.Unconfirmed>(
                (f.records.value as AppResult.Success).value?.outcome
            )
        }
    }

    @Test
    fun liveActionDenialStaysGatedThroughStoppedAndFailedRetriesButHistoricalEvidenceDoesNotRegateFreshReads() =
        runTest {
            withActions { f, model, _ ->
                model.onEvent(LeaveRequestEvent.Entered)
                runCurrent()
                f.transmit = {
                    AppResult.Failed(
                        Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
                    )
                }
                confirm(model)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertNull(model.state.value.content)
                model.onEvent(LeaveRequestEvent.Stopped)
                runCurrent()
                f.request.requestCall = {
                    AppResult.Failed(Failure(FailureKind.Network, "Unavailable"))
                }
                model.onEvent(LeaveRequestEvent.Entered)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertEquals("mfa_required", model.state.value.failure?.code)
                assertNull(model.state.value.content)
                f.request.requestCall = { AppResult.Success(f.canonical) }
                model.onEvent(LeaveRequestEvent.RefreshRequested)
                runCurrent()
                assertFalse(model.state.value.readRestricted)
                assertNotNull(model.state.value.content)
                assertIs<LeaveCommandOutcome.Rejected>(model.state.value.action.command?.outcome)
                assertTrue(model.state.value.canReviewAction)
            }
        }

    @Test
    fun unsentCommandsCanBeDiscardedButUncertainCommandsCannot() = runTest {
        withActions { f, model, _ ->
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            val base = f.request.base
            f.prepare(
                base.workspace.id,
                f.canonical.id,
                0,
                LeaveRequestAction.WITHDRAW,
                "Plans changed",
            )
            runCurrent()
            assertTrue(model.state.value.canDiscardAction)
            model.onEvent(LeaveRequestEvent.DiscardActionRequested)
            runCurrent()
            assertNull(model.state.value.action.command)
            f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Unavailable")) }
            confirm(model)
            runCurrent()
            model.onEvent(LeaveRequestEvent.DiscardActionRequested)
            runCurrent()
            assertIs<LeaveCommandOutcome.Unconfirmed>(model.state.value.action.command?.outcome)
            assertEquals(1, f.removals)
        }
    }

    @Test
    fun aChangedCanonicalVersionClosesTheOldConfirmationAndRejectsItsSubmission() = runTest {
        withActions { f, model, _ ->
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            model.onEvent(LeaveRequestEvent.ActionRequested(LeaveRequestAction.WITHDRAW))
            runCurrent()
            f.canonical =
                f.canonical.copy(
                    version = 1,
                    status = LeaveStatus.APPROVED,
                    availableActions = setOf(LeaveAction.REQUEST_CANCELLATION),
                )
            f.request.load()
            runCurrent()
            assertNull(model.state.value.action.form)
            model.onEvent(LeaveRequestEvent.ActionConfirmed)
            runCurrent()
            assertTrue(f.transmitted.isEmpty())
        }
    }

    @Test
    fun closingTheFormDuringAdmissionDoesNotReopenItWhenStorageFailsLater() = runTest {
        withActions { f, model, _ ->
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.beforeInsert = { release.await() }
            f.insertFailure = Failure(FailureKind.Storage, "Unavailable")
            confirm(model)
            runCurrent()
            assertTrue(model.state.value.action.working)
            model.onEvent(LeaveRequestEvent.ActionDismissed)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertNull(model.state.value.action.form)
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertTrue(f.transmitted.isEmpty())
        }
    }

    @Test
    fun completedCommandStorageSourcesRecoverOnlyAfterAnExplicitSuccessfulRefresh() = runTest {
        withActions { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            f.failNextObservation = true
            f.request.requestCall = {
                release.await()
                AppResult.Success(f.canonical)
            }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            assertFalse(model.state.value.action.ready)
            assertEquals(0, f.observers)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, f.subscriptions)
            release.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.action.ready)
            assertEquals(1, f.observers)
            assertNull(model.state.value.failure)
        }
    }

    private fun confirm(
        model: LeaveRequestViewModel,
        action: LeaveRequestAction = LeaveRequestAction.WITHDRAW,
    ) {
        model.onEvent(LeaveRequestEvent.ActionRequested(action))
        model.onEvent(LeaveRequestEvent.ReasonChanged("Plans changed"))
        model.onEvent(LeaveRequestEvent.ActionConfirmed)
    }

    private suspend fun TestScope.withActions(
        test:
            suspend (LeaveActionPresentationFixture, LeaveRequestViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = LeaveActionPresentationFixture(LeaveRequestPresentationFixture())
        val model = fixture.model()
        val owner = ViewModelStore().apply { put("request", model) }
        try {
            test(fixture, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, fixture.observers)
            assertEquals(0, fixture.request.observers)
            Dispatchers.resetMain()
        }
    }
}
