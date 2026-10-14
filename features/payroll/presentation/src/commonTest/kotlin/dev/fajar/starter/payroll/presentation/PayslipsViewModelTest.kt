@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.presentation.overview.*
import dev.fajar.starter.sync.domain.entities.SyncBootstrapPage
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
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
            assertEquals(1, f.detailReads)
            assertEquals(1, f.observers)
            assertEquals(2026, model.state.value.filters?.year)
            var cancelled = false
            f.changesCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(PayslipsEvent.RefreshRequested)
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertEquals(2, f.changeReads)
            assertNotNull(model.state.value.content)
            model.onEvent(PayslipsEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.observers)
            assertNull(model.state.value.content)
            assertFalse(model.state.value.refreshing)
            f.changesCall = { AppResult.Success(f.changePage()) }
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun changingYearQueriesLocalFactsWithoutCancellingSyncOrRestoringTheOldFilter() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            var cancelled = false
            val release = CompletableDeferred<Unit>()
            f.changesCall = {
                try {
                    release.await()
                    AppResult.Success(f.changePage())
                } catch (failure: CancellationException) {
                    cancelled = true
                    throw failure
                }
            }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            model.onEvent(PayslipsEvent.PreviousYearRequested)
            runCurrent()
            assertFalse(cancelled)
            assertTrue(model.state.value.refreshing)
            assertEquals(2025, model.state.value.content?.value?.selection?.year)
            assertTrue(model.state.value.content!!.value.page.items.isEmpty())
            assertEquals(1, f.detailReads)
            release.complete(Unit)
            runCurrent()
            assertEquals(2025, model.state.value.filters?.year)
            assertEquals(2025, model.state.value.content?.value?.selection?.year)
            assertFalse(model.state.value.refreshing)
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun onlyVisibleRowsAndIssuedLocalCursorsCanNavigateWithoutAcquisition() = runTest {
        withModel { f, model, _ ->
            f.slips.clear()
            for (index in 1..23) {
                val id = "10000000-0000-0000-0000-" + index.toString().padStart(12, '0')
                f.slips[id] = f.slip.copy(summary = f.summary.copy(id = id))
                f.payments[id] = f.payment.copy(payslipId = id)
            }
            val effects = mutableListOf<PayslipsEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            val first = model.state.value.content!!.value.page
            val id = first.items.first().id
            val cursor = assertNotNull(first.nextCursor)
            model.onEvent(PayslipsEvent.PayslipSelected("foreign"))
            model.onEvent(PayslipsEvent.PageRequested("foreign"))
            model.onEvent(PayslipsEvent.PageRequested(null))
            model.onEvent(PayslipsEvent.PayslipSelected(id))
            runCurrent()
            assertEquals(listOf<PayslipsEffect>(PayslipsEffect.OpenPayslip(id)), effects)
            model.onEvent(PayslipsEvent.PageRequested(cursor))
            runCurrent()
            assertEquals(3, model.state.value.content!!.value.page.items.size)
            assertEquals(cursor, model.state.value.filters?.after)
            model.onEvent(PayslipsEvent.PayslipSelected(id))
            model.onEvent(PayslipsEvent.PageRequested(null))
            runCurrent()
            assertEquals(1, effects.size)
            assertEquals(23, f.detailReads)
            assertEquals(1, f.changeReads)
            assertNull(model.state.value.filters?.after)
        }
    }

    @Test
    fun deniedReadsRemainHiddenAcrossFailedCleanupPendingRetryAndTransportFailure() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            f.replicas.failCleanup = true
            f.changesCall = { payslipFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertNotNull(f.snapshot())
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.content)
            assertEquals(0, f.observers)
            val release = CompletableDeferred<Unit>()
            f.detailCall = {
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
            f.detailCall = { AppResult.Success(f.slip) }
            f.changesCall = { AppResult.Success(f.changePage()) }
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
            f.replicas.failNextObservation = true
            val release = CompletableDeferred<Unit>()
            f.detailCall = {
                release.await()
                AppResult.Success(f.slip)
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
            assertEquals(1, f.detailReads)
        }
    }

    @Test
    fun agingStoredFactsDoesNotRefreshHideOrReplaceThePage() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            advanceTimeBy(73 * 60 * 60_000L)
            runCurrent()
            assertEquals(saved, model.state.value.content)
            assertEquals(1, f.changeReads)
            assertFalse(model.state.value.refreshing)
            val release = CompletableDeferred<Unit>()
            f.changesCall = {
                release.await()
                AppResult.Success(f.changePage())
            }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertEquals(saved, model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
        }
    }

    @Test
    fun failedRefreshKeepsContentAndDoesNotRetryUntilRequested() = runTest {
        withModel { f, model, _ ->
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            f.changesCall = { payslipFailure("client.network", FailureKind.Network) }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertEquals("client.network", model.state.value.failure?.code)
            assertFalse(model.state.value.readRestricted)
            assertNotNull(model.state.value.content)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, f.changeReads)
            f.changesCall = { AppResult.Success(f.changePage()) }
            model.onEvent(PayslipsEvent.RefreshRequested)
            runCurrent()
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun incompleteSyncKeepsItsPendingOutcomeWithoutAutomaticallyRepeating() = runTest {
        withModel { f, model, _ ->
            var page = 0
            f.bootstrapCall = { _, _ ->
                AppResult.Success(
                    SyncBootstrapPage(
                        PayrollFixture.collections,
                        emptyList(),
                        "page/${++page}",
                        null,
                        f.clock.now(),
                    )
                )
            }
            model.onEvent(PayslipsEvent.Entered)
            runCurrent()
            assertEquals(10, f.bootstrapReads)
            assertTrue(model.state.value.syncPending)
            assertFalse(model.state.value.loading)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(10, f.bootstrapReads)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (PayrollFixture, PayslipsViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = PayrollFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = PayslipsViewModel(f.workspace.id, f.observe, f.sync)
        val owner = ViewModelStore().apply { put("payslips", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
            assertEquals(0, f.replicas.admissionObservers)
            Dispatchers.resetMain()
        }
    }
}
