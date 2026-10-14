@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.presentation.action.*
import dev.fajar.starter.expenses.presentation.detail.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ExpenseActionViewModelTest {
    @Test
    fun confirmationQueuesOneRequestAndOpensOnlyAfterFreshFactsAndReconciliation() = runTest {
        for (mode in listOf(ExpenseActionMode.SUBMIT, ExpenseActionMode.CANCEL)) withModel(mode) {
            f,
            model,
            _ ->
            val effects = mutableListOf<ExpenseActionEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.canConfirm)
            model.onEvent(ExpenseActionEvent.ReasonChanged("Employee confirmation"))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            f.editor.transport = { command ->
                gate.await()
                f.publish(command)
                AppResult.Success(
                    ExpenseCommandReceipt(command.claimId, command.expectedReceiptVersion())
                )
            }
            model.onEvent(ExpenseActionEvent.Confirmed)
            model.onEvent(ExpenseActionEvent.Confirmed)
            runCurrent()
            assertEquals(1, f.editor.insertions)
            assertEquals(1, f.editor.sent.size)
            assertIs<ExpenseCommandOutcome.Unconfirmed>(f.editor.values.value.single().outcome)
            model.onEvent(ExpenseActionEvent.ReasonChanged("Late change"))
            model.onEvent(ExpenseActionEvent.RetryRequested)
            runCurrent()
            assertEquals("Employee confirmation", f.editor.sent.single().reason)
            assertTrue(effects.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertTrue(f.editor.values.value.isEmpty())
            assertEquals(
                listOf<ExpenseActionEffect>(ExpenseActionEffect.OpenClaim(f.f.claim.id, null)),
                effects,
            )
            assertTrue(model.state.value.closing)
        }
    }

    @Test
    fun uncertaintySurvivesRecreationAndAConfirmedReadFailureDoesNotLoseTheReceipt() = runTest {
        withModel { f, first, owner ->
            first.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            first.onEvent(ExpenseActionEvent.ReasonChanged("Submit for review"))
            runCurrent()
            f.editor.transport = { expenseFailure("fixture.offline", FailureKind.Network) }
            first.onEvent(ExpenseActionEvent.Confirmed)
            runCurrent()
            val retained = f.editor.values.value.single().command
            owner.clear()
            runCurrent()
            val restored = f.model(ExpenseActionMode.RECOVER)
            owner.put("restored", restored)
            restored.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            assertEquals(retained, restored.state.value.command?.command)
            assertEquals(ExpenseActionMode.SUBMIT, restored.state.value.displayMode)
            restored.onEvent(ExpenseActionEvent.DiscardRequested)
            restored.onEvent(ExpenseActionEvent.DiscardConfirmed)
            runCurrent()
            assertEquals(1, f.editor.values.value.size)
            f.editor.transport = { command ->
                f.publish(command)
                f.f.claimCall = { expenseFailure("fixture.read", FailureKind.Network) }
                AppResult.Success(
                    ExpenseCommandReceipt(command.claimId, command.expectedReceiptVersion())
                )
            }
            restored.onEvent(ExpenseActionEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(retained, retained), f.editor.sent)
            assertIs<ExpenseCommandOutcome.Confirmed>(f.editor.values.value.single().outcome)
            assertFalse(restored.state.value.closing)
            f.f.claimCall = { AppResult.Success(f.editor.currentClaim) }
            restored.onEvent(ExpenseActionEvent.OpenClaimRequested)
            runCurrent()
            assertTrue(restored.state.value.closing)
            assertTrue(f.editor.values.value.isEmpty())
        }
    }

    @Test
    fun reasonSurvivesBackgroundAndTheOwnerCancelsReadsAndDelivery() = runTest {
        withModel { f, model, owner ->
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseActionEvent.ReasonChanged("Retained explanation"))
            runCurrent()
            model.onEvent(ExpenseActionEvent.Stopped)
            runCurrent()
            assertEquals(0, f.editor.observers)
            assertEquals(0, f.f.observers)
            assertEquals("Retained explanation", model.state.value.form?.reason)
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            var cancelled = false
            f.editor.transport = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                expenseFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(ExpenseActionEvent.Confirmed)
            runCurrent()
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.editor.observers)
            assertEquals(0, f.f.observers)
            assertIs<ExpenseCommandOutcome.Unconfirmed>(f.editor.values.value.single().outcome)
            assertNull(model.state.value.restriction)
        }
    }

    @Test
    fun lateCancelledReadFailureCannotReplaceFreshReadmission() = runTest {
        withModel { f, model, _ ->
            f.f.claimCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {}
                expenseFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseActionEvent.Stopped)
            runCurrent()
            f.f.claimCall = { AppResult.Success(f.editor.currentClaim) }
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            assertNull(model.state.value.restriction)
            assertTrue(model.state.value.canConfirm)
        }
    }

    @Test
    fun aChangedClaimNeedsExplicitReviewWithoutDiscardingTheReason() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseActionEvent.ReasonChanged("Retained reason"))
            runCurrent()
            f.editor.currentClaim =
                f.editor.currentClaim.copy(
                    version = 1,
                    draft = f.editor.currentClaim.draft.copy(title = "Changed elsewhere"),
                )
            model.onEvent(ExpenseActionEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.changed)
            assertFalse(model.state.value.canConfirm)
            model.onEvent(ExpenseActionEvent.Confirmed)
            runCurrent()
            assertEquals(0, f.editor.insertions)
            model.onEvent(ExpenseActionEvent.ReviewLatestRequested)
            runCurrent()
            assertFalse(model.state.value.changed)
            assertEquals("Retained reason", model.state.value.form?.reason)
            assertEquals(1L, model.state.value.form?.version)
        }
    }

    @Test
    fun authoritativeFailureRemainsGatedWhileCleanupAndRetryFail() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            f.f.failCleanup = true
            f.f.claimCall = { expenseFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ExpenseActionEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertTrue(
                (f.f.replicas.published.value as AppResult.Success).value.entries.isNotEmpty()
            )
            val pending = CompletableDeferred<Unit>()
            f.f.claimCall = {
                pending.await()
                expenseFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(ExpenseActionEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            pending.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.content)
            f.f.claimCall = { AppResult.Success(f.editor.currentClaim) }
            model.onEvent(ExpenseActionEvent.RefreshRequested)
            runCurrent()
            assertFalse(model.state.value.needsVerification)
            assertTrue(model.state.value.canConfirm)
        }
    }

    @Test
    fun aWorkerStartingDeliveryClosesTheDiscardDialogAndKeepsTheCommand() = runTest {
        withModel(ExpenseActionMode.RECOVER) { f, model, _ ->
            val snapshot = f.editor.details()
            val record =
                assertIs<AppResult.Success<ExpenseCommandRecord>>(
                        f.editor.action(
                            f.f.workspace.id,
                            snapshot,
                            ExpenseRequestedAction.SUBMIT,
                            "Submitted",
                        )
                    )
                    .value
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseActionEvent.DiscardRequested)
            runCurrent()
            assertEquals(ExpenseActionDialog.DISCARD_COMMAND, model.state.value.dialog)
            f.editor.values.value =
                listOf(
                    record.copy(
                        revision = 1,
                        attempts = 1,
                        outcome = ExpenseCommandOutcome.Unconfirmed(),
                    )
                )
            runCurrent()
            assertNull(model.state.value.dialog)
            model.onEvent(ExpenseActionEvent.DiscardConfirmed)
            runCurrent()
            assertEquals(0, f.editor.removals)
        }
    }

    @Test
    fun anUnsentRecoveryCanBeDiscardedAndEditedWithoutLosingItsActionOrReason() = runTest {
        withModel(ExpenseActionMode.RECOVER) { f, model, _ ->
            val snapshot = f.editor.details()
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                f.editor.action(
                    f.f.workspace.id,
                    snapshot,
                    ExpenseRequestedAction.CANCEL,
                    "Cancelled by employee",
                )
            )
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseActionEvent.DiscardRequested)
            runCurrent()
            model.onEvent(ExpenseActionEvent.DiscardConfirmed)
            runCurrent()
            assertEquals(ExpenseActionMode.CANCEL, model.state.value.mode)
            assertEquals("Cancelled by employee", model.state.value.form?.reason)
            assertTrue(model.state.value.canConfirm)
            assertTrue(f.editor.values.value.isEmpty())
        }
    }

    @Test
    fun withdrawalUsesTheCurrentSubmissionAndReturnsToTheSameClaim() = runTest {
        withModel(ExpenseActionMode.WITHDRAW) { f, model, _ ->
            val snapshot = f.editor.details()
            val prepared =
                assertIs<AppResult.Success<ExpenseCommandRecord>>(
                        f.editor.action(
                            f.f.workspace.id,
                            snapshot,
                            ExpenseRequestedAction.SUBMIT,
                            "Submit",
                        )
                    )
                    .value
            val confirmed =
                assertIs<AppResult.Success<ExpenseCommandRecord>>(
                        f.editor.replay(f.f.workspace.id, prepared.command.id)
                    )
                    .value
            assertIs<AppResult.Success<Unit>>(f.f.refreshDetail(f.f.workspace.id, f.f.claim.id))
            assertEquals(
                AppResult.Success(true),
                f.editor.review(f.f.workspace.id, confirmed.command.id),
            )
            val submissionId = f.editor.currentClaim.latestSubmissionId!!
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.canConfirm)
            model.onEvent(ExpenseActionEvent.ReasonChanged("Correct expense details"))
            runCurrent()
            model.onEvent(ExpenseActionEvent.Confirmed)
            runCurrent()
            assertEquals(
                ExpenseCommandPayload.Withdraw(1, submissionId),
                f.editor.sent.last().payload,
            )
            assertEquals(ExpenseStatus.DRAFT, f.editor.currentClaim.status)
            assertEquals(2L, f.editor.currentClaim.version)
            assertTrue(f.submission!!.current)
            assertEquals(ExpenseApprovalStatus.CANCELLED, f.submission!!.approval.status)
            assertTrue(model.state.value.closing)
            assertTrue(f.editor.values.value.isEmpty())
        }
    }

    @Test
    fun detailOffersRecoveryBeforeAnotherActionAndPreservesEmploymentContext() = runTest {
        withModel { f, _, owner ->
            val prepared =
                assertIs<AppResult.Success<ExpenseCommandRecord>>(
                        f.editor.action(
                            f.f.workspace.id,
                            f.editor.details(),
                            ExpenseRequestedAction.SUBMIT,
                            "Submit",
                        )
                    )
                    .value
            val detail =
                ExpenseClaimViewModel(
                    ExpenseClaimTarget(f.f.workspace.id, f.f.claim.id, "employee-page"),
                    f.f.observeDetail,
                    f.f.refreshDetail,
                    f.f.sync,
                    f.editor.observe,
                    ExpenseReceiptExportFixture(f.f).export,
                )
            owner.put("detail", detail)
            val effects = mutableListOf<ExpenseClaimEffect>()
            backgroundScope.launch { detail.effects.collect { effects += it } }
            detail.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            assertFalse(detail.state.value.canEdit)
            assertFalse(detail.state.value.canOpenAction)
            assertTrue(detail.state.value.canRecover)
            detail.onEvent(ExpenseClaimEvent.EditRequested)
            detail.onEvent(ExpenseClaimEvent.ActionRequested(ExpenseActionMode.CANCEL))
            detail.onEvent(ExpenseClaimEvent.RecoveryRequested)
            runCurrent()
            assertEquals(
                listOf<ExpenseClaimEffect>(
                    ExpenseClaimEffect.OpenAction(
                        f.f.claim.id,
                        ExpenseActionMode.RECOVER,
                        "employee-page",
                    )
                ),
                effects,
            )
            assertEquals(
                AppResult.Success(true),
                f.editor.review(f.f.workspace.id, prepared.command.id),
            )
            runCurrent()
            assertTrue(detail.state.value.canOpenAction)
            assertTrue(detail.state.value.canEdit)
        }
    }

    @Test
    fun readOnlyExpenseAccessDoesNotStartCommandObservationOrHideDetails() = runTest {
        withModel { f, _, owner ->
            f.f.workspaceState.value =
                AppResult.Success(f.f.workspace.copy(permissions = setOf("expenses.read")))
            val detail =
                ExpenseClaimViewModel(
                    ExpenseClaimTarget(f.f.workspace.id, f.f.claim.id),
                    f.f.observeDetail,
                    f.f.refreshDetail,
                    f.f.sync,
                    f.editor.observe,
                    ExpenseReceiptExportFixture(f.f).export,
                )
            owner.put("detail", detail)
            detail.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            assertNotNull(detail.state.value.content)
            assertFalse(detail.state.value.readRestricted)
            assertFalse(detail.state.value.canEdit)
            assertTrue(detail.state.value.actionOptions.isEmpty())
            assertEquals(0, f.editor.observers)
        }
    }

    @Test
    fun backCancelsDeliveryBeforeDisposalAndRetainsItsUncertainOutcome() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<ExpenseActionEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseActionEvent.ReasonChanged("Submit reviewed claim"))
            runCurrent()
            var cancelled = false
            f.editor.transport = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                expenseFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(ExpenseActionEvent.Confirmed)
            runCurrent()
            model.onEvent(ExpenseActionEvent.BackRequested)
            model.onEvent(ExpenseActionEvent.BackRequested)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.editor.observers)
            assertEquals(0, f.f.observers)
            assertEquals(listOf<ExpenseActionEffect>(ExpenseActionEffect.Back), effects)
            assertIs<ExpenseCommandOutcome.Unconfirmed>(f.editor.values.value.single().outcome)
            assertNull(model.state.value.restriction)
        }
    }

    @Test
    fun confirmedExitCancelsPendingPreparationWithoutASecondNavigation() = runTest {
        withModel { f, model, owner ->
            val effects = mutableListOf<ExpenseActionEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseActionEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseActionEvent.ReasonChanged("Not stored yet"))
            runCurrent()
            var cancelled = false
            f.editor.beforeInsert = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(ExpenseActionEvent.Confirmed)
            runCurrent()
            model.onEvent(ExpenseActionEvent.BackRequested)
            runCurrent()
            assertEquals(ExpenseActionDialog.EXIT, model.state.value.dialog)
            model.onEvent(ExpenseActionEvent.ExitConfirmed)
            model.onEvent(ExpenseActionEvent.ExitConfirmed)
            runCurrent()
            assertEquals(listOf<ExpenseActionEffect>(ExpenseActionEffect.Back), effects)
            assertTrue(cancelled)
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
            assertTrue(f.editor.values.value.isEmpty())
        }
    }

    private suspend fun TestScope.withModel(
        mode: ExpenseActionMode = ExpenseActionMode.SUBMIT,
        test:
            suspend TestScope.(ExpenseActionFixture, ExpenseActionViewModel, ViewModelStore) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ExpenseActionFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = f.model(mode)
        val owner = ViewModelStore().apply { put("action", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
