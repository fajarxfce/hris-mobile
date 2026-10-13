@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.policies.expenseFailure
import dev.fajar.starter.expenses.presentation.action.ExpenseActionMode
import dev.fajar.starter.expenses.presentation.detail.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ExpenseReceiptExportViewModelTest {
    @Test
    fun leavingAnAccessFailureCannotQueueVerificationBehindBack() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<ExpenseClaimEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            f.inspectCall = { expenseFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            model.onEvent(ExpenseClaimEvent.BackRequested)
            model.onEvent(ExpenseClaimEvent.VerificationRequested)
            runCurrent()
            assertEquals(listOf<ExpenseClaimEffect>(ExpenseClaimEffect.Back), effects)
        }
    }

    @Test
    fun onlyVisibleReceiptsCanStartOneExportAndCompetingActionsWait() = runTest {
        withModel { f, model, _ ->
            val selected = CompletableDeferred<Boolean>()
            f.selectCall = { AppResult.Success(selected.await()) }
            val effects = mutableListOf<ExpenseClaimEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseClaimEvent.ExportRequested("unknown"))
            runCurrent()
            assertEquals(0, f.selections)
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            val reads = f.base.claimReads
            assertEquals(1, f.selections)
            assertEquals(f.receipt.id, model.state.value.savingReceiptId)
            assertFalse(model.state.value.canSaveReceipt)
            assertFalse(model.state.value.canOpenAction)
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            model.onEvent(ExpenseClaimEvent.HistoryRequested(1))
            model.onEvent(ExpenseClaimEvent.ActionRequested(ExpenseActionMode.WITHDRAW))
            runCurrent()
            assertEquals(reads, f.base.claimReads)
            assertTrue(effects.isEmpty())
            selected.complete(true)
            runCurrent()
            assertEquals(1, f.completions)
            assertEquals(1, f.releases)
            assertEquals(f.receipt.id, model.state.value.savedReceiptId)
            assertNull(model.state.value.savingReceiptId)
            assertTrue(model.state.value.canSaveReceipt)
            assertTrue(f.acquired.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test
    fun systemDialogMayStopAndResumeTheScreenWithoutCancellingOrDuplicatingExport() = runTest {
        withModel { f, model, _ ->
            val selected = CompletableDeferred<Boolean>()
            f.selectCall = { AppResult.Success(selected.await()) }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            model.onEvent(ExpenseClaimEvent.Stopped)
            runCurrent()
            assertEquals(0, f.base.observers)
            assertNull(model.state.value.content)
            assertEquals(f.receipt.id, model.state.value.savingReceiptId)
            assertEquals(0, f.releases)
            val reads = f.base.claimReads
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            assertEquals(reads, f.base.claimReads)
            assertNotNull(model.state.value.content)
            selected.complete(true)
            runCurrent()
            assertEquals(1, f.selections)
            assertEquals(1, f.completions)
            assertEquals(f.receipt.id, model.state.value.savedReceiptId)
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun cancellingPickerIsQuietAndTheNextExplicitAttemptCanSave() = runTest {
        withModel { f, model, _ ->
            f.selectCall = { AppResult.Success(false) }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertNull(model.state.value.failure)
            assertNull(model.state.value.savedReceiptId)
            assertNull(model.state.value.savingReceiptId)
            assertEquals(0, f.streams)
            assertEquals(1, f.releases)
            f.selectCall = { AppResult.Success(true) }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertEquals(2, f.selections)
            assertEquals(1, f.completions)
            assertEquals(f.receipt.id, model.state.value.savedReceiptId)
        }
    }

    @Test
    fun explicitCancellationWaitsForOutputCleanupBeforeAllowingAnotherPicker() = runTest {
        withModel { f, model, _ ->
            val released = CompletableDeferred<Unit>()
            f.writeCall = { awaitCancellation() }
            f.releaseCall = {
                released.await()
                AppResult.Success(Unit)
            }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            model.onEvent(ExpenseClaimEvent.ExportCancelled)
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertEquals(1, f.selections)
            assertFalse(model.state.value.canSaveReceipt)
            assertEquals(1, f.releases)
            assertEquals(0, f.completions)
            assertTrue(f.acquired.single().all { it == 0.toByte() })
            released.complete(Unit)
            runCurrent()
            assertNull(model.state.value.failure)
            assertNull(model.state.value.savingReceiptId)
            assertTrue(model.state.value.canSaveReceipt)
        }
    }

    @Test
    fun backCancelsPendingExportBeforeDisposalAndEmitsOnlyOneNavigation() = runTest {
        withModel { f, model, _ ->
            var cancelled = false
            f.selectCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val effects = mutableListOf<ExpenseClaimEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            model.onEvent(ExpenseClaimEvent.BackRequested)
            model.onEvent(ExpenseClaimEvent.BackRequested)
            model.onEvent(ExpenseClaimEvent.Entered)
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertTrue(cancelled)
            assertEquals(listOf<ExpenseClaimEffect>(ExpenseClaimEffect.Back), effects)
            assertEquals(0, f.base.observers)
            assertEquals(1, f.selections)
            assertEquals(1, f.releases)
            assertNull(model.state.value.content)
            assertNull(model.state.value.savingReceiptId)
            assertTrue(model.state.value.closing)
        }
    }

    @Test
    fun disposalRejectsLateFailureAndARecreatedCompositionCanEnterTheRetainedOwner() = runTest {
        withModel { f, model, _ ->
            f.selectCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    expenseFailure("mfa_required", FailureKind.AccessDenied)
                }
            }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            model.onEvent(ExpenseClaimEvent.Closed)
            runCurrent()
            assertEquals(1, f.releases)
            assertEquals(0, f.base.observers)
            assertNull(model.state.value.failure)
            assertNull(model.state.value.content)
            assertNull(model.state.value.savingReceiptId)
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
        }
    }

    @Test
    fun exportDenialKeepsRetainedCacheGatedThroughPendingAndFailedRefresh() = runTest {
        withModel { f, model, _ ->
            f.inspectCall = { expenseFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            assertTrue(model.state.value.needsVerification)
            assertEquals(0, f.selections)
            assertEquals(0, f.base.observers)
            assertNotNull(assertIs<AppResult.Success<*>>(f.base.detailRecords.value).value)
            f.base.failCleanup = true
            val response = CompletableDeferred<Unit>()
            f.base.claimCall = {
                response.await()
                expenseFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            assertFalse(model.state.value.canSaveReceipt)
            response.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            assertEquals("mfa_required", model.state.value.failure?.code)
            assertEquals(0, f.base.observers)
            f.publish()
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            runCurrent()
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.exportFailure)
            assertNotNull(model.state.value.content)
            assertTrue(model.state.value.canSaveReceipt)
        }
    }

    @Test
    fun writeFailureRetainsClaimAndCanBeRetriedWithoutRepeatingNavigation() = runTest {
        withModel { f, model, _ ->
            f.writeCall = { expenseFailure("client.file_output_failed", FailureKind.Storage) }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertFalse(model.state.value.readRestricted)
            assertNotNull(model.state.value.content)
            assertEquals("client.file_output_failed", model.state.value.failure?.code)
            assertNull(model.state.value.savedReceiptId)
            assertEquals(0, f.completions)
            assertEquals(1, f.releases)
            f.writeCall = { AppResult.Success(Unit) }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            assertEquals(1, f.completions)
            assertNull(model.state.value.failure)
            assertEquals(f.receipt.id, model.state.value.savedReceiptId)
        }
    }

    @Test
    fun losingSessionDuringWriteCancelsAcquisitionAndErasesPendingContent() = runTest {
        withModel { f, model, _ ->
            f.writeCall = { awaitCancellation() }
            model.onEvent(ExpenseClaimEvent.ExportRequested(f.receipt.id))
            runCurrent()
            f.base.sessionState.value = AppResult.Success(null)
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.content)
            assertNull(model.state.value.savingReceiptId)
            assertEquals(0, f.completions)
            assertEquals(1, f.releases)
            assertTrue(f.acquired.single().all { it == 0.toByte() })
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (ExpenseReceiptExportFixture, ExpenseClaimViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f =
            ExpenseReceiptExportFixture(
                ExpenseFixture {
                    Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
                }
            )
        val commands = ExpenseEditorFixture(f.base)
        f.publish()
        val model =
            ExpenseClaimViewModel(
                ExpenseClaimTarget(f.base.workspace.id, f.claim.id),
                f.base.observeDetail,
                f.base.refreshDetail,
                commands.observe,
                f.export,
            )
        val owner = ViewModelStore().apply { put("expense", model) }
        try {
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertTrue(model.state.value.canSaveReceipt)
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, f.base.observers)
            Dispatchers.resetMain()
        }
    }
}
