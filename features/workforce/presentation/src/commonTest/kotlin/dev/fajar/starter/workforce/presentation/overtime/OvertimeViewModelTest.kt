@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.presentation.overtime.overview.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

class OvertimeViewModelTest {
    @Test
    fun oneOwnerCancelsAcquisitionOnStopAndReleasesEveryCollectorOnClear() = runTest {
        withModel { fixture, model, owner ->
            model.onEvent(OvertimeEvent.Entered)
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertEquals(1, fixture.requestReads)
            assertEquals(1, fixture.observers)
            assertEquals(LocalDate(2026, 10, 1), model.state.value.month?.selected)
            var cancelled = false
            fixture.requestsCall = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            model.onEvent(OvertimeEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, fixture.observers)
            assertNull(model.state.value.content)
            fixture.requestsCall = { _, _ -> AppResult.Success(OvertimePage(emptyList())) }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, fixture.observers)
        }
    }

    @Test
    fun monthReplacementRejectsNonCooperativeLateFailureAndPreservesTheNewFilter() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            var cancelled = false
            fixture.requestsCall = { period, _ ->
                if (period.from.month.number == 10) {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                    overtimeFailure("mfa_required", FailureKind.AccessDenied)
                } else AppResult.Success(OvertimePage(emptyList()))
            }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            model.onEvent(OvertimeEvent.MonthRequested(LocalDate(2026, 9, 1)))
            runCurrent()
            assertTrue(cancelled)
            assertEquals(LocalDate(2026, 9, 1), model.state.value.filters?.period?.from)
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(listOf(10, 10, 9), fixture.periods.map { it.from.month.number })
        }
    }

    @Test
    fun theMonthPickerDoesNotAcquireUntilAVisibleMonthIsSelected() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            model.onEvent(OvertimeEvent.PickerYearRequested(2025))
            model.onEvent(OvertimeEvent.PickerMonthSelected(4))
            model.onEvent(OvertimeEvent.MonthRequested(LocalDate(2026, 7, 1)))
            model.onEvent(OvertimeEvent.MonthRequested(LocalDate(2026, 9, 2)))
            runCurrent()
            assertEquals(1, fixture.requestReads)
            model.onEvent(OvertimeEvent.MonthFilterRequested)
            model.onEvent(OvertimeEvent.PickerYearRequested(2025))
            model.onEvent(OvertimeEvent.PickerYearRequested(1999))
            model.onEvent(OvertimeEvent.PickerMonthSelected(13))
            runCurrent()
            assertEquals(2025, model.state.value.picker?.year)
            assertEquals(1, fixture.requestReads)
            model.onEvent(OvertimeEvent.PickerMonthSelected(2))
            runCurrent()
            assertEquals(LocalDate(2025, 2, 28), model.state.value.filters?.period?.until)
            assertEquals(2, fixture.requestReads)
            assertNull(model.state.value.dialog)
        }
    }

    @Test
    fun onlyVisibleRowsAndFilterOptionsDispatchAcquisitionOrNavigation() = runTest {
        withModel { fixture, model, _ ->
            val effects = mutableListOf<OvertimeEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            model.onEvent(OvertimeEvent.RequestSelected("foreign"))
            model.onEvent(OvertimeEvent.StatusSelected(OvertimeStatus.APPROVED))
            model.onEvent(OvertimeEvent.RequestsPageRequested(fixture.employee.id))
            model.onEvent(OvertimeEvent.EmploymentSelected("foreign"))
            runCurrent()
            assertEquals(1, fixture.requestReads)
            assertTrue(effects.isEmpty())
            model.onEvent(OvertimeEvent.RequestSelected(fixture.request.id))
            runCurrent()
            assertEquals(
                listOf<OvertimeEffect>(OvertimeEffect.OpenRequest(fixture.selection)),
                effects,
            )
            model.onEvent(OvertimeEvent.StatusFilterRequested)
            model.onEvent(OvertimeEvent.StatusSelected(OvertimeStatus.APPROVED))
            runCurrent()
            assertEquals(OvertimeStatus.APPROVED, model.state.value.filters?.status)
            assertEquals(2, fixture.requestReads)
        }
    }

    @Test
    fun anEmptyContinuationPageCanReturnToTheFirstOwnedEmployment() = runTest {
        withModel { fixture, model, _ ->
            fixture.directoryCall = {
                AppResult.Success(fixture.directory.copy(nextCursor = "E01"))
            }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            fixture.directoryCall = {
                AppResult.Success(fixture.directory.copy(items = emptyList()))
            }
            model.onEvent(OvertimeEvent.EmploymentFilterRequested)
            model.onEvent(OvertimeEvent.EmploymentPageRequested("E01"))
            runCurrent()
            assertEquals("E01", model.state.value.filters?.employmentAfter)
            assertNull(model.state.value.filters?.employeeId)
            fixture.directoryCall = { AppResult.Success(fixture.directory) }
            model.onEvent(OvertimeEvent.DialogDismissed)
            model.onEvent(OvertimeEvent.EmploymentFilterRequested)
            model.onEvent(OvertimeEvent.EmploymentPageRequested(null))
            runCurrent()
            assertEquals(fixture.employee.id, model.state.value.filters?.employeeId)
            assertNull(model.state.value.filters?.employmentAfter)
        }
    }

    @Test
    fun aReplacedCacheWindowClearsFormerContentWithoutFollowingItsLongerLease() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            fixture.records.value =
                AppResult.Success(
                    saved.copy(
                        generation = "other-filter",
                        value =
                            saved.value.copy(
                                selection =
                                    saved.value.selection.copy(status = OvertimeStatus.APPROVED)
                            ),
                    )
                )
            runCurrent()
            assertNull(model.state.value.content)
            assertNull(model.state.value.filters?.status)
            assertEquals(1, fixture.requestReads)
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.filters?.status)
        }
    }

    @Test
    fun denialSurvivesFailedCleanupPendingRetryAndLaterTransportFailures() = runTest {
        withModel { fixture, model, _ ->
            val effects = mutableListOf<OvertimeEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            fixture.failCleanup = true
            fixture.directoryCall = { overtimeFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertNotNull((fixture.records.value as AppResult.Success).value)
            assertNull(model.state.value.content)
            assertEquals(0, fixture.observers)
            for (kind in listOf(FailureKind.Network, FailureKind.Storage)) {
                fixture.directoryCall = { overtimeFailure("fixture.retry", kind) }
                model.onEvent(OvertimeEvent.RefreshRequested)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertEquals("mfa_required", model.state.value.failure?.code)
                assertNull(model.state.value.content)
            }
            model.onEvent(OvertimeEvent.RequestSelected(fixture.request.id))
            model.onEvent(OvertimeEvent.VerificationRequested)
            runCurrent()
            assertEquals(listOf<OvertimeEffect>(OvertimeEffect.VerifyAccount), effects)
            val release = CompletableDeferred<Unit>()
            fixture.directoryCall = {
                release.await()
                AppResult.Success(fixture.directory)
            }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertEquals(0, fixture.observers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertEquals(1, fixture.observers)
        }
    }

    @Test
    fun aCompletedStorageSourceIsReopenedOnlyAfterSuccessfulExplicitAcquisition() = runTest {
        withModel { fixture, model, _ ->
            fixture.failNextObservation = true
            val release = CompletableDeferred<Unit>()
            fixture.directoryCall = {
                release.await()
                AppResult.Success(fixture.directory)
            }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, fixture.observers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertEquals(1, fixture.observers)
            assertEquals(1, fixture.requestReads)
        }
    }

    @Test
    fun offlineRestorationKeepsSavedFiltersWhenCacheArrivesAfterNetworkFailure() = runTest {
        withModel { fixture, model, _ ->
            val period = overtimeMonth(LocalDate(2025, 6, 1))
            fixture.refresh(
                fixture.workspace.id,
                OvertimeSelection(period = period, status = OvertimeStatus.REJECTED),
            )
            val release = CompletableDeferred<Unit>()
            fixture.cacheReadGate = release
            fixture.directoryCall = { overtimeFailure("fixture.offline", FailureKind.Network) }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertNull(model.state.value.content)
            release.complete(Unit)
            runCurrent()
            assertEquals(period, model.state.value.filters?.period)
            assertEquals(OvertimeStatus.REJECTED, model.state.value.filters?.status)
            assertNotNull(model.state.value.content)
            fixture.directoryCall = { AppResult.Success(fixture.directory) }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertEquals(period, model.state.value.content?.value?.selection?.period)
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun staleDataStaysVisibleUntilCompanyReplacement() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            fixture.records.value =
                AppResult.Success(saved.copy(refreshAfter = fixture.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertFalse(model.state.value.readRestricted)
            assertEquals(saved.generation, assertNotNull(model.state.value.content).generation)
            assertEquals(1, fixture.observers)
            assertEquals(1, fixture.requestReads)
            fixture.workspaceState.value =
                AppResult.Success(fixture.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
            assertEquals(1, fixture.requestReads)
        }
    }

    @Test
    fun staleContentRemainsVisibleWhileAnExplicitRefreshIsPending() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            val writes = fixture.writes
            val release = CompletableDeferred<Unit>()
            fixture.directoryCall = {
                release.await()
                AppResult.Success(fixture.directory)
            }
            fixture.records.value =
                AppResult.Success(saved.copy(refreshAfter = fixture.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertFalse(model.state.value.refreshing)
            assertNotNull(model.state.value.content)
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.refreshing)
            assertFalse(model.state.value.readRestricted)
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(writes, fixture.writes)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.failure)
            assertEquals(writes + 1, fixture.writes)
        }
    }

    @Test
    fun failedExplicitRefreshKeepsStaleContentAndDoesNotRetryAutomatically() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            var attempts = 0
            fixture.directoryCall = {
                attempts++
                overtimeFailure("client.network", FailureKind.Network)
            }
            fixture.records.value =
                AppResult.Success(saved.copy(refreshAfter = fixture.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(0, attempts)
            assertNotNull(model.state.value.content)
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertEquals(1, attempts)
            assertEquals("client.network", model.state.value.failure?.code)
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, attempts)
            fixture.directoryCall = { AppResult.Success(fixture.directory) }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.failure)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (OvertimePresentationFixture, OvertimeViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = OvertimePresentationFixture {
            Instant.parse("2026-10-12T01:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = OvertimeViewModel(fixture.workspace.id, fixture.observe, fixture.refresh)
        val owner = ViewModelStore().apply { put("overtime", model) }
        try {
            test(fixture, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, fixture.observers)
            Dispatchers.resetMain()
        }
    }
}
