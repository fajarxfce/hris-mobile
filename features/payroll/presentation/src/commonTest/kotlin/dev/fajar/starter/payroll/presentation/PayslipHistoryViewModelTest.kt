@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.PayslipDocumentLanguage
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.presentation.detail.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PayslipHistoryViewModelTest {
    @Test
    fun revisionNavigationUsesOwnedRecordsAndRefreshDoesNotReplaceAnOfflineStatement() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            assertEquals(2L, model.state.value.currentRevision)
            assertFalse(model.state.value.canChooseNext)
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            runCurrent()
            assertEquals(1L, model.state.value.selectedRevision)
            assertEquals(1L, model.state.value.content!!.value.payslip.summary.version)
            assertEquals(1, f.base.revisionReads.size)
            model.onEvent(PayslipEvent.CurrentRevisionRequested)
            runCurrent()
            assertNull(model.state.value.selectedRevision)
            assertEquals(2L, model.state.value.content!!.value.payslip.summary.version)
            f.base.policyCall = { payslipFailure("offline", FailureKind.Network) }
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            runCurrent()
            val saved = model.state.value.content
            assertEquals(1L, saved!!.value.payslip.summary.version)
            assertEquals(1, f.base.revisionReads.size)
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertEquals(saved, model.state.value.content)
            assertEquals(FailureKind.Network, model.state.value.failure?.kind)
            assertFalse(model.state.value.readRestricted)
            model.onEvent(PayslipEvent.NextRevisionRequested)
            runCurrent()
            assertNull(model.state.value.selectedRevision)
            assertEquals(2L, model.state.value.content!!.value.payslip.summary.version)
            assertEquals(1, f.base.revisionReads.size)
        }
    }

    @Test
    fun returningToCurrentCancelsAnOldRequestAndIgnoresItsLateSuccessOrDenial() = runTest {
        for (fails in listOf(false, true)) withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.base.revisionCall = { _, version ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (fails) payslipFailure("mfa_required", FailureKind.AccessDenied)
                else
                    AppResult.Success(
                        f.base.slip.copy(summary = f.base.summary.copy(version = version))
                    )
            }
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            entered.await()
            assertTrue(model.state.value.revisionLoading)
            assertNull(model.state.value.content)
            assertFalse(model.state.value.canSave)
            model.onEvent(PayslipEvent.CurrentRevisionRequested)
            runCurrent()
            assertEquals(2L, model.state.value.content!!.value.payslip.summary.version)
            release.complete(Unit)
            runCurrent()
            assertNull(model.state.value.selectedRevision)
            assertNull(model.state.value.failure)
            assertFalse(model.state.value.readRestricted)
            assertFalse(model.state.value.revisionLoading)
            assertNull(f.base.replicas.readAdmission.value.denial)
        }
    }

    @Test
    fun explicitRevisionStaysSelectedWhenTheHeadAndPaymentProgressAdvance() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            runCurrent()
            val previous = f.base.replicas.current!!
            f.base.replicas.commit(
                previous,
                previous.copy(revision = previous.revision + 1),
                listOf(
                    PayrollReplicaEntry(
                        SyncReference(SyncCollection.PAYSLIPS, f.base.summary.id, 4),
                        PayrollRecord.Slip(
                            f.base.slip.copy(summary = f.base.summary.copy(version = 4))
                        ),
                    ),
                    PayrollReplicaEntry(
                        SyncReference(SyncCollection.PAYROLL_PAYMENTS, f.base.summary.id, 7),
                        PayrollRecord.Payments(f.base.payment.copy(version = 7)),
                    ),
                ),
            )
            runCurrent()
            assertEquals(4L, model.state.value.currentRevision)
            assertEquals(1L, model.state.value.selectedRevision)
            assertEquals(1L, model.state.value.content!!.value.payslip.summary.version)
            assertEquals(7L, model.state.value.content!!.value.payments.version)
            assertEquals(1, f.base.revisionReads.size)
        }
    }

    @Test
    fun aHistoricalPdfPinsItsRevisionAndBlocksRevisionChangesDuringTheNativeSave() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            runCurrent()
            val selection = CompletableDeferred<Boolean>()
            f.selectCall = { AppResult.Success(selection.await()) }
            f.metadata = f.metadata.copy(revision = 1)
            model.onEvent(PayslipEvent.ExportRequested)
            runCurrent()
            assertEquals(1L, model.state.value.exportRevision)
            model.onEvent(PayslipEvent.CurrentRevisionRequested)
            runCurrent()
            assertEquals(1L, model.state.value.selectedRevision)
            model.onEvent(PayslipEvent.ExportLanguageSelected(PayslipDocumentLanguage.ID))
            runCurrent()
            assertTrue(model.state.value.saving)
            model.onEvent(PayslipEvent.NextRevisionRequested)
            runCurrent()
            assertEquals(1L, model.state.value.selectedRevision)
            selection.complete(true)
            runCurrent()
            assertTrue(model.state.value.saved)
            assertEquals(listOf(1L, 1L), f.inspectedRevisions)
            assertEquals(listOf(1L), f.downloadedRevisions)
            assertEquals("payslip-${f.base.summary.id}-v1-id.pdf", f.declaration!!.name)
        }
    }

    @Test
    fun aRevisionDenialRemainsAuthoritativeDuringFailedRefreshAndCleanup() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            f.base.replicas.failCleanup = true
            f.base.revisionCall = { _, _ ->
                payslipFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.content)
            assertNull(model.state.value.currentRevision)
            assertNotNull(f.base.snapshot())
            val reads = f.base.revisionReads.size
            model.onEvent(PayslipEvent.CurrentRevisionRequested)
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            runCurrent()
            assertEquals(reads, f.base.revisionReads.size)
            val release = CompletableDeferred<Unit>()
            f.base.detailCall = {
                release.await()
                payslipFailure("offline", FailureKind.Network)
            }
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            release.complete(Unit)
            runCurrent()
            assertEquals("mfa_required", model.state.value.failure?.code)
            assertEquals(0, f.base.observers)
            f.base.detailCall = { AppResult.Success(f.base.slips.getValue(it)) }
            f.base.revisionCall = { _, version ->
                AppResult.Success(
                    f.base.slip.copy(summary = f.base.summary.copy(version = version))
                )
            }
            model.onEvent(PayslipEvent.RefreshRequested)
            runCurrent()
            assertFalse(model.state.value.readRestricted)
            assertEquals(1L, model.state.value.content!!.value.payslip.summary.version)
        }
    }

    @Test
    fun closingTheRouteCancelsAcquisitionAndAllHistorySubscriptions() = runTest {
        withModel { f, model, owner ->
            model.onEvent(PayslipEvent.Entered)
            runCurrent()
            var cancelled = false
            f.base.revisionCall = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(PayslipEvent.PreviousRevisionRequested)
            runCurrent()
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.base.observers)
            assertEquals(0, f.base.replicas.admissionObservers)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (PayslipExportFixture, PayslipViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val base = PayrollFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        base.slips[base.summary.id] = base.slip.copy(summary = base.summary.copy(version = 2))
        base.remoteRevisions[base.summary.id to 1L] =
            base.slip.copy(summary = base.summary.copy(version = 1))
        val f = PayslipExportFixture(base)
        val model =
            PayslipViewModel(
                PayslipTarget(base.workspace.id, base.summary.id),
                base.observeDetail,
                base.sync,
                base.loadRevision,
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
