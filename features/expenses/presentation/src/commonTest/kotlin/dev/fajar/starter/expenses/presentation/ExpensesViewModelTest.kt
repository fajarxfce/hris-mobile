@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailure
import dev.fajar.starter.expenses.presentation.overview.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.YearMonth

class ExpensesViewModelTest {
    @Test
    fun enteringDropsDuplicatesAndStoppingReleasesAcquisitionAndObservation() = runTest {
        withModel { f, model, owner ->
            model.onEvent(ExpensesEvent.Entered)
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            assertEquals(1, f.directoryReads)
            assertEquals(1, f.observers)
            assertEquals(YearMonth(2026, 10), model.state.value.filters?.month)
            var cancelled = false
            f.directoryCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(ExpensesEvent.RefreshRequested)
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertEquals(2, f.directoryReads)
            assertNotNull(model.state.value.content)
            model.onEvent(ExpensesEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.observers)
            assertNull(model.state.value.content)
            assertFalse(model.state.value.refreshing)
            f.directoryCall = { AppResult.Success(f.directory) }
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun localFilterChangesDoNotCancelSyncOrGetOverwrittenByItsLateCompletion() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            var cancelled = false
            f.directoryCall = {
                try {
                    release.await()
                    AppResult.Success(f.directory)
                } finally {
                    cancelled = !currentCoroutineContext().isActive
                }
            }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ExpensesEvent.PreviousMonthRequested)
            runCurrent()
            assertFalse(cancelled)
            assertEquals(YearMonth(2026, 9), model.state.value.content?.value?.selection?.month)
            assertEquals(2, f.directoryReads)
            assertEquals(0, f.listReads)
            release.complete(Unit)
            runCurrent()
            assertEquals(YearMonth(2026, 9), model.state.value.filters?.month)
            assertEquals(YearMonth(2026, 9), model.state.value.content?.value?.selection?.month)
            assertNull(model.state.value.failure)
            assertEquals(2, f.directoryReads)
        }
    }

    @Test
    fun onlyVisibleRowsAndIssuedLocalCursorsCanNavigateWithoutHttp() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<ExpensesEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            val snapshot =
                (f.observeDetail(f.workspace.id, f.claim.id).first() as AppResult.Success).value!!
            repeat(25) { index ->
                f.seedDetails(
                    snapshot.copy(
                        generation = "item-$index",
                        value =
                            snapshot.value.copy(
                                claim =
                                    f.claim.copy(
                                        id =
                                            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                                    )
                            ),
                    )
                )
            }
            runCurrent()
            val content = model.state.value.content!!.value
            val first = content.page.items.first().id
            val cursor = assertNotNull(content.page.nextCursor)
            val calls = f.directoryReads + f.recordReads
            model.onEvent(ExpensesEvent.ClaimSelected("foreign"))
            model.onEvent(ExpensesEvent.PageRequested("foreign"))
            model.onEvent(ExpensesEvent.ClaimSelected(first))
            runCurrent()
            assertEquals(listOf<ExpensesEffect>(ExpensesEffect.OpenClaim(first)), effects)
            model.onEvent(ExpensesEvent.PageRequested(cursor))
            runCurrent()
            assertEquals(cursor, model.state.value.filters?.after)
            assertEquals(6, model.state.value.content?.value?.page?.items?.size)
            model.onEvent(ExpensesEvent.ClaimSelected(first))
            model.onEvent(ExpensesEvent.PageRequested(null))
            runCurrent()
            assertEquals(1, effects.size)
            assertNull(model.state.value.filters?.after)
            assertEquals(calls, f.directoryReads + f.recordReads)
        }
    }

    @Test
    fun deniedReadsRemainHiddenAcrossFailedCleanupPendingRetryAndTransportFailure() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            val retained = f.replicas.published.value
            f.failCleanup = true
            f.directoryCall = { expenseFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertEquals(retained, f.replicas.published.value)
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.content)
            assertEquals(0, f.observers)
            assertEquals(1, f.replicas.admissionObservers)
            val release = CompletableDeferred<Unit>()
            f.directoryCall = {
                release.await()
                expenseFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ExpensesEvent.PreviousMonthRequested)
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.content)
            release.complete(Unit)
            runCurrent()
            assertEquals("mfa_required", model.state.value.failure?.code)
            assertEquals(YearMonth(2026, 10), model.state.value.filters?.month)
            f.directoryCall = { AppResult.Success(f.directory) }
            model.onEvent(ExpensesEvent.RefreshRequested)
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
            f.directoryCall = {
                release.await()
                AppResult.Success(f.directory)
            }
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, f.observers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, f.observers)
            assertEquals(1, f.directoryReads)
        }
    }

    @Test
    fun oldLocalFactsStayReadableWhileAnExplicitRefreshIsPendingUntilCompanyReplacement() =
        runTest {
            withModel { f, model, _ ->
                model.onEvent(ExpensesEvent.Entered)
                runCurrent()
                val saved = assertNotNull(model.state.value.content)
                advanceTimeBy(73 * 60 * 60_000L)
                runCurrent()
                assertEquals(saved, model.state.value.content)
                assertEquals(1, f.directoryReads)
                val release = CompletableDeferred<Unit>()
                f.directoryCall = {
                    release.await()
                    AppResult.Success(f.directory)
                }
                model.onEvent(ExpensesEvent.RefreshRequested)
                runCurrent()
                assertTrue(model.state.value.refreshing)
                assertFalse(model.state.value.readRestricted)
                assertNotNull(model.state.value.content)
                assertEquals(2, f.directoryReads)
                release.complete(Unit)
                runCurrent()
                assertNotNull(model.state.value.content)
                assertNull(model.state.value.failure)
                f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
                runCurrent()
                assertNull(model.state.value.content)
                assertTrue(model.state.value.readRestricted)
            }
        }

    @Test
    fun failedExplicitRefreshShowsItsCauseAndKeepsContentWithoutRepeating() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            f.directoryCall = { expenseFailure("client.network", FailureKind.Network) }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertNull(model.state.value.cacheFailure)
            assertEquals("client.network", model.state.value.failure?.code)
            assertFalse(model.state.value.readRestricted)
            assertNotNull(model.state.value.content)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, f.directoryReads)
            f.directoryCall = { AppResult.Success(f.directory) }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(3, f.directoryReads)
        }
    }

    @Test
    fun leavingDuringRefreshCancelsAndIgnoresItsLateDenial() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            var cancelled = false
            f.directoryCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                expenseFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ExpensesEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertFalse(model.state.value.refreshing)
            assertNull(model.state.value.content)
            assertNull(model.state.value.refreshFailure)
            assertNull(f.replicas.readAdmission.value.denial)
            assertEquals(0, f.observers)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (ExpenseFixture, ExpensesViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ExpenseFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = ExpensesViewModel(f.workspace.id, f.observe, f.sync)
        val owner = ViewModelStore().apply { put("expenses", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(
                0,
                f.observers + f.replicas.admissionObservers + f.replicas.directoryObservers,
            )
            Dispatchers.resetMain()
        }
    }
}
