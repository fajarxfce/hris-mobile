@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.PayslipDocumentLanguage
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.presentation.detail.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class PayslipViewModelTest {
    @Test
    fun detailAcquisitionHasOneOwnerAndDisposalCancelsPendingWork() = runTest {
        withModel { f, model, owner ->
            model.onEvent(PayslipEvent.Entered)
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            assertEquals(1, f.base.detailReads)
            assertEquals(1, f.base.paymentReads)
            assertTrue(model.state.value.canSave)
            var cancelled = false
            f.base.paymentCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.canSave)
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.base.observers)
        }
    }

    @Test
    fun duplicateSaveDropsAndSuccessfulCopyKeepsItsOutcomeUntilRefresh() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            val choice = CompletableDeferred<Boolean>()
            f.selectCall = { AppResult.Success(choice.await()) }
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            assertEquals(0, f.inspections)
            model.onEvent(PayslipEvent.ExportRequested)
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            assertTrue(model.state.value.saving)
            assertFalse(model.state.value.exportOptions)
            assertFalse(model.state.value.canSave)
            assertEquals(1, f.selections)
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertEquals(1, f.base.detailReads)
            choice.complete(true)
            runCurrent()
            assertTrue(model.state.value.saved)
            assertFalse(model.state.value.saving)
            assertEquals(1, f.completions)
            assertEquals(1, f.releases)
            assertTrue(f.bytes.all { it == 0.toByte() })
            assertTrue(model.state.value.canSave)
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertFalse(model.state.value.saved)
        }
    }

    @Test
    fun closingWhileTheDestinationIsPendingReleasesItAndRejectsLateCompletion() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            val choice = CompletableDeferred<Boolean>()
            f.selectCall = { AppResult.Success(choice.await()) }
            model.onEvent(PayslipEvent.ExportRequested)
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            assertEquals(1, f.selections)
            model.onEvent(PayslipEvent.Closed)
            runCurrent()
            assertEquals(1, f.releases)
            assertEquals(0, f.base.observers)
            assertNull(model.state.value.content)
            choice.complete(true)
            runCurrent()
            assertEquals(0, f.downloads)
            assertEquals(0, f.writes)
            assertFalse(model.state.value.saved)
        }
    }

    @Test
    fun aCancelledDownloadCannotWriteOrPublishSuccessAndErasesLateBytes() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            var cancelled = false
            f.downloadCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                AppResult.Success(f.bytes)
            }
            model.onEvent(PayslipEvent.ExportRequested)
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            model.onEvent(PayslipEvent.Closed)
            runCurrent()
            assertTrue(cancelled)
            assertTrue(f.bytes.all { it == 0.toByte() })
            assertEquals(1, f.releases)
            assertEquals(0, f.writes)
            assertFalse(model.state.value.saved)
        }
    }

    @Test
    fun authoritativeExportDenialStaysGatedThroughPendingAndFailedRefresh() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<PayslipEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            f.base.failCleanup = true
            f.inspectCall = { payslipFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(PayslipEvent.ExportRequested)
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            assertNotNull(f.base.snapshot(true))
            assertNull(model.state.value.content)
            assertTrue(model.state.value.needsVerification)
            assertEquals(0, f.selections)
            assertEquals(0, f.base.observers)
            val release = CompletableDeferred<Unit>()
            f.base.detailCall = {
                release.await()
                payslipFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            model.onEvent(PayslipEvent.ExportRequested)
            model.onEvent(PayslipEvent.VerificationRequested)
            runCurrent()
            assertFalse(model.state.value.exportOptions)
            assertEquals(listOf<PayslipEffect>(PayslipEffect.VerifyAccount), effects)
            release.complete(Unit)
            runCurrent()
            assertEquals("mfa_required", model.state.value.failure?.code)
            assertFalse(model.state.value.canSave)
            f.base.detailCall = { AppResult.Success(f.base.slip) }
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.canSave)
            assertNull(model.state.value.failure)
            assertEquals(1, f.base.observers)
        }
    }

    @Test
    fun cancellingThePickerIsNeitherAnErrorNorASavedCopy() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            f.selectCall = { AppResult.Success(false) }
            model.onEvent(PayslipEvent.ExportRequested)
            model.onEvent(PayslipEvent.ExportDismissed)
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            assertEquals(0, f.selections)
            model.onEvent(PayslipEvent.ExportRequested)
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            assertFalse(model.state.value.saved)
            assertFalse(model.state.value.saving)
            assertNull(model.state.value.failure)
            assertEquals(0, f.downloads)
            assertEquals(1, f.releases)
            assertTrue(model.state.value.canSave)
        }
    }

    @Test
    fun successfulRefreshReopensACompletedDetailStorageSource() = runTest {
        withModel { f, model, _ ->
            f.base.failNextDetailObservation = true
            val release = CompletableDeferred<Unit>()
            f.base.detailCall = {
                release.await()
                AppResult.Success(f.base.slip)
            }
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, f.base.observers)
            release.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.canSave)
            assertEquals(1, f.base.observers)
        }
    }

    @Test
    fun expiredVisibleReadRenewsWithoutAnErrorWhilePending() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            val writes = f.base.writes
            val release = CompletableDeferred<Unit>()
            f.base.detailCall = {
                release.await()
                AppResult.Success(f.base.slip)
            }
            f.base.detailRecords.value =
                AppResult.Success(saved.copy(expiresAt = f.base.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(model.state.value.refreshing)
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(writes, f.base.writes)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.failure)
            assertEquals(writes + 1, f.base.writes)
        }
    }

    @Test
    fun failedAutomaticRenewalRetainsTheReadGateAndStopsRetrying() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            var attempts = 0
            f.base.detailCall = {
                attempts++
                payslipFailure("client.network", FailureKind.Network)
            }
            f.base.detailRecords.value =
                AppResult.Success(saved.copy(expiresAt = f.base.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(1, attempts)
            assertEquals("client.network", model.state.value.failure?.code)
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, attempts)
            f.base.detailCall = { AppResult.Success(f.base.slip) }
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun mfaDenialAfterExpirySurvivesFailedRetriesAndRetainedCiphertext() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            f.base.failCleanup = true
            f.base.detailCall = { payslipFailure("mfa_required", FailureKind.AccessDenied) }
            f.base.detailRecords.value =
                AppResult.Success(saved.copy(expiresAt = f.base.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertEquals("mfa_required", model.state.value.failure?.code)
            for (kind in listOf(FailureKind.Network, FailureKind.Storage)) {
                f.base.detailCall = { payslipFailure("client.retry_failed", kind) }
                model.onEvent(PayslipEvent.RefreshRequested)
                runCurrent()
                assertTrue(model.state.value.needsVerification)
                assertTrue(model.state.value.readRestricted)
                assertEquals("mfa_required", model.state.value.failure?.code)
                assertNull(model.state.value.content)
                assertNotNull(f.base.snapshot(true))
                assertEquals(0, f.base.observers)
            }
            f.base.detailCall = { AppResult.Success(f.base.slip) }
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.needsVerification)
            assertFalse(model.state.value.readRestricted)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (PayslipExportFixture, PayslipViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val base = PayrollFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val f = PayslipExportFixture(base)
        val model =
            PayslipViewModel(
                PayslipTarget(base.workspace.id, base.summary.id),
                base.observeDetail,
                base.refreshDetail,
                f.export,
            )
        val owner = ViewModelStore().apply { put("payslip", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, base.observers)
            Dispatchers.resetMain()
        }
    }
}
