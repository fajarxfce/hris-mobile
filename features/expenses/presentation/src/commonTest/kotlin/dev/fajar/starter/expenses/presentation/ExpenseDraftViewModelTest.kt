@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.presentation.draft.*
import dev.fajar.starter.expenses.presentation.item.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ExpenseDraftViewModelTest {
    @Test
    fun backgroundRetainsUnsavedFieldsAndItemWhileDisposalReleasesObservation() = runTest {
        withModel { f, model, owner ->
            model.onEvent(ExpenseDraftEvent.Entered)
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertEquals(1, f.f.directoryReads)
            assertTrue(model.state.value.editable)
            model.onEvent(ExpenseDraftEvent.TitleChanged("Client visit"))
            model.onEvent(ExpenseDraftEvent.DescriptionChanged("Transport"))
            model.onEvent(ExpenseDraftEvent.ReasonChanged("Business trip"))
            model.onEvent(ExpenseDraftEvent.ItemAdded)
            runCurrent()
            val target = assertNotNull(model.state.value.itemTarget)
            val form = model.state.value.form
            model.onEvent(ExpenseDraftEvent.Stopped)
            runCurrent()
            assertEquals(0, f.observers)
            assertFalse(model.state.value.editable)
            assertEquals(form, model.state.value.form)
            assertSame(target, model.state.value.itemTarget)
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.editable)
            assertEquals(form, model.state.value.form)
            assertSame(target, model.state.value.itemTarget)
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun aCompletedChildCanReturnLocalInputDuringParentReadmissionWithoutBeingLost() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.ItemAdded)
            runCurrent()
            val target = assertNotNull(model.state.value.itemTarget)
            model.onEvent(ExpenseDraftEvent.Stopped)
            runCurrent()
            model.onEvent(
                ExpenseDraftEvent.ItemSaved(
                    target,
                    ExpenseItemResult(
                        f.f.line,
                        ExpenseLabelReference(f.f.line.categoryId, "TRAVEL", "Travel", true),
                        null,
                        emptyList(),
                    ),
                )
            )
            runCurrent()
            assertEquals(listOf(f.f.line), model.state.value.form?.lines)
            assertNull(model.state.value.itemTarget)
            assertEquals(0, f.insertions)
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertEquals(listOf(f.f.line), model.state.value.form?.lines)
        }
    }

    @Test
    fun savingFreezesOneImmutableCommandAndOpensOnlyAfterReadAndReconciliation() = runTest {
        withModel(editing = true) { f, model, _ ->
            val effects = mutableListOf<ExpenseDraftEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.ReasonChanged("Client reimbursement"))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            f.transport = { command ->
                gate.await()
                f.publish(command)
                AppResult.Success(
                    ExpenseCommandReceipt(command.claimId, command.expectedReceiptVersion())
                )
            }
            model.onEvent(ExpenseDraftEvent.SaveRequested)
            model.onEvent(ExpenseDraftEvent.SaveRequested)
            runCurrent()
            assertEquals(1, f.insertions)
            assertEquals(1, f.sent.size)
            assertIs<ExpenseCommandOutcome.Unconfirmed>(f.values.value.single().outcome)
            assertFalse(model.state.value.editable)
            model.onEvent(ExpenseDraftEvent.TitleChanged("Too late"))
            model.onEvent(ExpenseDraftEvent.RetryRequested)
            runCurrent()
            assertEquals(
                f.f.draft.title,
                (f.sent.single().payload as ExpenseCommandPayload.SaveDraft).title,
            )
            assertEquals(1, f.sent.size)
            assertTrue(effects.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertTrue(f.values.value.isEmpty())
            assertEquals(
                listOf<ExpenseDraftEffect>(ExpenseDraftEffect.OpenClaim(f.f.claim.id)),
                effects,
            )
            assertTrue(model.state.value.closing)
        }
    }

    @Test
    fun missingResponseSurvivesRecreationAndConfirmedReceiptSurvivesAReadFailure() = runTest {
        withModel(editing = true) { f, first, owner ->
            first.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            first.onEvent(ExpenseDraftEvent.ReasonChanged("Transport reimbursement"))
            runCurrent()
            f.transport = { expenseFailure("fixture.offline", FailureKind.Network) }
            first.onEvent(ExpenseDraftEvent.SaveRequested)
            runCurrent()
            val retained = f.values.value.single().command
            owner.clear()
            runCurrent()
            val restored = f.draftModel(f.draftTarget(true))
            owner.put("restored", restored)
            val effects = mutableListOf<ExpenseDraftEffect>()
            backgroundScope.launch { restored.effects.collect { effects += it } }
            restored.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertEquals(retained, restored.state.value.command?.command)
            assertEquals(1, f.sent.size)
            restored.onEvent(ExpenseDraftEvent.DiscardCommandRequested)
            restored.onEvent(ExpenseDraftEvent.DiscardCommandConfirmed)
            runCurrent()
            assertEquals(1, f.values.value.size)
            f.transport = { command ->
                f.publish(command)
                f.f.claimCall = { expenseFailure("fixture.read", FailureKind.Network) }
                AppResult.Success(
                    ExpenseCommandReceipt(command.claimId, command.expectedReceiptVersion())
                )
            }
            restored.onEvent(ExpenseDraftEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(retained, retained), f.sent)
            assertIs<ExpenseCommandOutcome.Confirmed>(restored.state.value.command?.outcome)
            assertTrue(effects.isEmpty())
            f.f.claimCall = { AppResult.Success(f.currentClaim) }
            restored.onEvent(ExpenseDraftEvent.OpenClaimRequested)
            runCurrent()
            assertTrue(f.values.value.isEmpty())
            assertEquals(
                listOf<ExpenseDraftEffect>(ExpenseDraftEffect.OpenClaim(retained.claimId)),
                effects,
            )
        }
    }

    @Test
    fun anExternalRevisionCannotSilentlyRebaseUnsavedInput() = runTest {
        withModel(editing = true) { f, model, _ ->
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.TitleChanged("My unsaved title"))
            model.onEvent(ExpenseDraftEvent.ReasonChanged("Correction"))
            runCurrent()
            f.currentClaim =
                f.currentClaim.copy(
                    version = 1,
                    draft = f.currentClaim.draft.copy(revision = 1, title = "Changed elsewhere"),
                )
            model.onEvent(ExpenseDraftEvent.SaveRequested)
            runCurrent()
            assertTrue(model.state.value.remoteChanged)
            assertEquals("My unsaved title", model.state.value.form?.title)
            assertEquals(0, f.insertions)
            model.onEvent(ExpenseDraftEvent.ReloadRequested)
            runCurrent()
            assertEquals(ExpenseDraftDialog.RELOAD, model.state.value.dialog)
            model.onEvent(ExpenseDraftEvent.ReloadConfirmed)
            runCurrent()
            assertEquals("Changed elsewhere", model.state.value.form?.title)
            assertFalse(model.state.value.remoteChanged)
        }
    }

    @Test
    fun aLateCancelledFailureDoesNotReplaceTheResumedFormOrItsAdmission() = runTest {
        withModel { f, model, _ ->
            var cancelled = false
            f.f.directoryCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                expenseFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertNull(model.state.value.restriction)
            f.f.directoryCall = { AppResult.Success(f.f.directory) }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.canSave)
            assertNull(model.state.value.restriction)
        }
    }

    @Test
    fun authoritativeDenialStaysGatedThroughPendingAndFailedReadmission() = runTest {
        withModel(editing = true) { f, model, _ ->
            f.referenceCall = { expenseFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            val gate = CompletableDeferred<Unit>()
            f.f.directoryCall = {
                gate.await()
                expenseFailure("fixture.network", FailureKind.Network)
            }
            model.onEvent(ExpenseDraftEvent.ReloadRequested)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertFalse(model.state.value.editable)
            gate.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertEquals(0, f.insertions)
            f.f.directoryCall = { AppResult.Success(f.f.directory) }
            f.referenceCall = { q ->
                AppResult.Success(
                    ExpenseReferenceValues(
                        q.employeeId,
                        q.categoryIds.map { ExpenseLabelReference(it) },
                        emptyList(),
                        emptyList(),
                    )
                )
            }
            model.onEvent(ExpenseDraftEvent.ReloadRequested)
            runCurrent()
            assertTrue(model.state.value.editable)
        }
    }

    @Test
    fun pendingDiscardClosesWhenBackgroundDeliveryMakesTheOutcomeUncertain() = runTest {
        withModel(editing = true) { f, model, _ ->
            val retained = assertIs<AppResult.Success<ExpenseCommandRecord>>(f.save(true)).value
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.DiscardCommandRequested)
            runCurrent()
            assertEquals(ExpenseDraftDialog.DISCARD_COMMAND, model.state.value.dialog)
            val uncertain =
                retained.copy(
                    revision = retained.revision + 1,
                    attempts = 1,
                    outcome = ExpenseCommandOutcome.Unconfirmed(null),
                )
            f.values.value = listOf(uncertain)
            runCurrent()
            assertNull(model.state.value.dialog)
            model.onEvent(ExpenseDraftEvent.DiscardCommandConfirmed)
            runCurrent()
            assertEquals(listOf(uncertain), f.values.value)
            assertEquals(0, f.removals)
        }
    }

    @Test
    fun unsavedExitRequiresConfirmationAndRepeatedConfirmationEmitsOneBack() = runTest {
        withModel { _, model, _ ->
            val effects = mutableListOf<ExpenseDraftEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseDraftEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseDraftEvent.TitleChanged("Not saved"))
            runCurrent()
            model.onEvent(ExpenseDraftEvent.BackRequested)
            runCurrent()
            assertTrue(effects.isEmpty())
            assertEquals(ExpenseDraftDialog.EXIT, model.state.value.dialog)
            model.onEvent(ExpenseDraftEvent.ExitConfirmed)
            model.onEvent(ExpenseDraftEvent.ExitConfirmed)
            runCurrent()
            assertEquals(listOf<ExpenseDraftEffect>(ExpenseDraftEffect.Back), effects)
        }
    }

    private suspend fun TestScope.withModel(
        editing: Boolean = false,
        block:
            suspend TestScope.(ExpenseEditorFixture, ExpenseDraftViewModel, ViewModelStore) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ExpenseEditorFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = f.draftModel(f.draftTarget(editing))
        val owner = ViewModelStore().apply { put("draft", model) }
        try {
            block(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
