@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.presentation.overview.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class PayslipsViewModelTest {
    @Test
    fun enteringDropsDuplicatesAndStoppingReleasesAcquisitionAndObservation() = runTest {
        withModel { f, model, owner ->
            model.onEvent(PayslipsEvent.Entered)
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            assertEquals(1, f.listReads)
            assertEquals(1, f.observers)
            assertEquals(2026, model.state.value.filters?.year)
            var cancelled = false
            f.listCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(PayslipsEvent.RefreshRequested)
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertEquals(2, f.listReads)
            assertNotNull(model.state.value.content)
            model.onEvent(PayslipsEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.observers)
            assertNull(model.state.value.content)
            assertFalse(model.state.value.refreshing)
            f.listCall = { AppResult.Success(PayslipPage(listOf(f.summary))) }
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun changingYearRejectsCancelledLateFailuresAndKeepsTheNewFilter() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            var cancelled = false
            f.listCall = { selection ->
                if (selection.year == 2026) {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                    payslipFailure("mfa_required", FailureKind.AccessDenied)
                } else AppResult.Success(PayslipPage(emptyList()))
            }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            model.onEvent(PayslipsEvent.PreviousYearRequested)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(2025, model.state.value.filters?.year)
            assertEquals(2025, model.state.value.content?.value?.selection?.year)
            assertNull(model.state.value.failure)
            assertEquals(3, f.listReads)
        }
    }

    @Test
    fun onlyVisibleRowsAndIssuedPageCursorsCanNavigateOrAcquire() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<PayslipsEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            val cursor = "2026-09:${f.summary.id}"
            f.listCall = { selection ->
                AppResult.Success(
                    if (selection.after == null) PayslipPage(listOf(f.summary), cursor)
                    else PayslipPage(emptyList())
                )
            }
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            model.onEvent(PayslipsEvent.PayslipSelected("foreign"))
            model.onEvent(PayslipsEvent.PageRequested("foreign"))
            model.onEvent(PayslipsEvent.PageRequested(null))
            model.onEvent(PayslipsEvent.PayslipSelected(f.summary.id))
            runCurrent()
            assertEquals(listOf<PayslipsEffect>(PayslipsEffect.OpenPayslip(f.summary.id)), effects)
            assertEquals(1, f.listReads)
            model.onEvent(PayslipsEvent.PageRequested(cursor))
            runCurrent()
            assertEquals(cursor, model.state.value.filters?.after)
            model.onEvent(PayslipsEvent.PayslipSelected(f.summary.id))
            model.onEvent(PayslipsEvent.PageRequested(null))
            runCurrent()
            assertEquals(1, effects.size)
            assertEquals(3, f.listReads)
            assertNull(model.state.value.filters?.after)
        }
    }

    @Test
    fun deniedReadsRemainHiddenAcrossFailedCleanupPendingRetryAndTransportFailure() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            f.failCleanup = true
            f.listCall = { payslipFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertNotNull(f.snapshot(false))
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.content)
            assertEquals(0, f.observers)
            val release = CompletableDeferred<Unit>()
            f.listCall = {
                release.await()
                payslipFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            model.onEvent(PayslipsEvent.PreviousYearRequested)
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.content)
            release.complete(Unit)
            runCurrent()
            assertEquals("mfa_required", model.state.value.failure?.code)
            assertEquals(2026, model.state.value.filters?.year)
            f.listCall = { AppResult.Success(PayslipPage(listOf(f.summary))) }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, f.observers)
        }
    }

    @Test
    fun successfulExplicitAcquisitionReopensACompletedStorageSource() = runTest {
        withModel { f, model, _ ->
            f.failNextObservation = true
            val release = CompletableDeferred<Unit>()
            f.listCall = {
                release.await()
                AppResult.Success(PayslipPage(listOf(f.summary)))
            }
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, f.observers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, f.observers)
            assertEquals(1, f.listReads)
        }
    }

    @Test
    fun explicitRefreshKeepsStaleContentButCompanyReplacementHidesIt() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            val release = CompletableDeferred<Unit>()
            f.listCall = {
                release.await()
                AppResult.Success(PayslipPage(listOf(f.summary)))
            }
            f.records.value =
                AppResult.Success(saved.copy(refreshAfter = f.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertFalse(model.state.value.refreshing)
            assertEquals(1, f.listReads)
            assertNotNull(model.state.value.content)
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.refreshing)
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.failure)
            assertNotNull(model.state.value.content)
            assertEquals(2, f.listReads)
            model.onEvent(PayslipsEvent.RefreshRequested)
            f.records.value = AppResult.Success(saved)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertEquals(2, f.listReads)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.failure)
            f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
            assertEquals(2, f.listReads)
        }
    }

    @Test
    fun failedExplicitRefreshShowsItsCauseAndKeepsStaleContentWithoutRepeating() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            f.listCall = { payslipFailure("client.network", FailureKind.Network) }
            f.records.value =
                AppResult.Success(saved.copy(refreshAfter = f.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(1, f.listReads)
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertNull(model.state.value.cacheFailure)
            assertEquals("client.network", model.state.value.failure?.code)
            assertFalse(model.state.value.readRestricted)
            assertNotNull(model.state.value.content)
            assertEquals(1, f.observers)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, f.listReads)
            f.listCall = { AppResult.Success(PayslipPage(listOf(f.summary))) }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(3, f.listReads)
        }
    }

    @Test
    fun leavingDuringExplicitRefreshCancelsAndIgnoresItsLateFailure() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            var cancelled = false
            f.listCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                payslipFailure("mfa_required", FailureKind.AccessDenied)
            }
            f.records.value =
                AppResult.Success(saved.copy(refreshAfter = f.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.refreshing)
            model.onEvent(PayslipsEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertFalse(model.state.value.refreshing)
            assertNull(model.state.value.content)
            assertNull(model.state.value.refreshFailure)
            assertEquals(0, f.observers)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (PayrollFixture, PayslipsViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = PayrollFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = PayslipsViewModel(f.workspace.id, f.observe, f.refresh)
        val owner = ViewModelStore().apply { put("payslips", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
            Dispatchers.resetMain()
        }
    }
}
