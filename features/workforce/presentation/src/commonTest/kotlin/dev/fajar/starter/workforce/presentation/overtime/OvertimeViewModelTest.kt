@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
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

class OvertimeViewModelTest {
    @Test
    fun oneOwnerCancelsAcquisitionOnStopAndReleasesEveryCollectorOnClear() = runTest {
        withModel { f, model, owner ->
            model.onEvent(OvertimeEvent.Entered)
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertEquals(1, f.canonicalReads)
            assertEquals(2, f.readers)
            assertEquals(LocalDate(2026, 10, 1), model.state.value.month?.selected)
            var cancelled = false
            f.directoryCall = {
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
            assertEquals(0, f.readers)
            assertNull(model.state.value.content)
            f.directoryCall = { AppResult.Success(f.directory) }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, f.readers)
        }
    }

    @Test
    fun changingMonthDuringSyncQueriesLocallyAndPreservesTheSelectedMonthAfterFailure() = runTest {
        withModel { f, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.directoryCall = {
                release.await()
                overtimeFailure("client.network", FailureKind.Network)
            }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            model.onEvent(OvertimeEvent.MonthRequested(LocalDate(2026, 9, 1)))
            runCurrent()
            assertTrue(model.state.value.refreshing)
            assertEquals(LocalDate(2026, 9, 1), model.state.value.filters?.period?.from)
            assertTrue(assertNotNull(model.state.value.content).value.requests.items.isEmpty())
            assertEquals(1, f.canonicalReads)
            release.complete(Unit)
            runCurrent()
            assertEquals(LocalDate(2026, 9, 1), model.state.value.filters?.period?.from)
            assertEquals("client.network", model.state.value.failure?.code)
            assertNotNull(model.state.value.content)
        }
    }

    @Test
    fun theMonthPickerAndStatusFilterOnlyQueryLocalRecords() = runTest {
        withModel { f, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            model.onEvent(OvertimeEvent.PickerYearRequested(2025))
            model.onEvent(OvertimeEvent.PickerMonthSelected(4))
            model.onEvent(OvertimeEvent.MonthRequested(LocalDate(2026, 7, 1)))
            model.onEvent(OvertimeEvent.MonthRequested(LocalDate(2026, 9, 2)))
            runCurrent()
            assertEquals(LocalDate(2026, 10, 1), model.state.value.month?.selected)
            model.onEvent(OvertimeEvent.MonthFilterRequested)
            model.onEvent(OvertimeEvent.PickerYearRequested(2025))
            model.onEvent(OvertimeEvent.PickerYearRequested(1999))
            model.onEvent(OvertimeEvent.PickerMonthSelected(13))
            runCurrent()
            assertEquals(2025, model.state.value.picker?.year)
            model.onEvent(OvertimeEvent.PickerMonthSelected(2))
            runCurrent()
            assertEquals(LocalDate(2025, 2, 28), model.state.value.filters?.period?.until)
            assertNull(model.state.value.dialog)
            model.onEvent(OvertimeEvent.StatusFilterRequested)
            model.onEvent(OvertimeEvent.StatusSelected(OvertimeStatus.APPROVED))
            runCurrent()
            assertEquals(OvertimeStatus.APPROVED, model.state.value.filters?.status)
            assertEquals(1, f.canonicalReads)
            assertEquals(1, f.directoryReads)
        }
    }

    @Test
    fun onlyVisibleRowsAndValidFilterOptionsCanNavigateOrChangeSelection() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<OvertimeEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            model.onEvent(OvertimeEvent.RequestSelected("foreign"))
            model.onEvent(OvertimeEvent.StatusSelected(OvertimeStatus.APPROVED))
            model.onEvent(OvertimeEvent.RequestsPageRequested(f.employee.id))
            model.onEvent(OvertimeEvent.EmploymentSelected("foreign"))
            runCurrent()
            assertNull(model.state.value.filters?.status)
            assertTrue(effects.isEmpty())
            model.onEvent(OvertimeEvent.RequestSelected(f.request.id))
            runCurrent()
            assertEquals(listOf<OvertimeEffect>(OvertimeEffect.OpenRequest(f.selection)), effects)
        }
    }

    @Test
    fun employmentPagesAreAcquiredOnceAndPagedLocally() = runTest {
        withModel { f, model, _ ->
            val firstPage =
                listOf(f.employee) +
                    (2..20).map { n ->
                        f.employee.copy(
                            id = "33333333-3333-3333-3333-" + n.toString().padStart(12, '0'),
                            employeeNumber = "E" + n.toString().padStart(2, '0'),
                        )
                    }
            val last =
                f.employee.copy(id = "44444444-4444-4444-4444-444444444444", employeeNumber = "E21")
            var pages = 0
            f.directoryCall = {
                pages++
                AppResult.Success(
                    f.directory.copy(
                        items = if (pages == 1) firstPage else listOf(last),
                        nextCursor = if (pages == 1) "E20" else null,
                    )
                )
            }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertEquals(2, pages)
            model.onEvent(OvertimeEvent.EmploymentFilterRequested)
            model.onEvent(OvertimeEvent.EmploymentPageRequested("E20"))
            runCurrent()
            assertEquals(last.id, model.state.value.filters?.employeeId)
            assertEquals("E20", model.state.value.filters?.employmentAfter)
            assertTrue(assertNotNull(model.state.value.content).value.requests.items.isEmpty())
            model.onEvent(OvertimeEvent.EmploymentPageRequested(null))
            runCurrent()
            assertEquals(f.employee.id, model.state.value.filters?.employeeId)
            assertEquals(2, pages)
        }
    }

    @Test
    fun denialSurvivesFailedCleanupAndPendingOrFailedRetry() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<OvertimeEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            f.replicas.failCleanup = true
            f.directoryCall = { overtimeFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertNotNull((f.replicas.published.value as AppResult.Success).value.checkpoint)
            assertNull(model.state.value.content)
            assertEquals(0, f.readers)
            for (kind in listOf(FailureKind.Network, FailureKind.Storage)) {
                f.directoryCall = { overtimeFailure("fixture.retry", kind) }
                model.onEvent(OvertimeEvent.RefreshRequested)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertEquals("mfa_required", model.state.value.failure?.code)
            }
            model.onEvent(OvertimeEvent.RequestSelected(f.request.id))
            model.onEvent(OvertimeEvent.VerificationRequested)
            runCurrent()
            assertEquals(listOf<OvertimeEffect>(OvertimeEffect.VerifyAccount), effects)
            val release = CompletableDeferred<Unit>()
            f.directoryCall = {
                release.await()
                AppResult.Success(f.directory)
            }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertEquals(0, f.readers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertEquals(2, f.readers)
        }
    }

    @Test
    fun aPartialSyncCannotReopenAConfirmedReadDenial() = runTest {
        withModel { f, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            f.replicas.failCleanup = true
            f.directoryCall = { overtimeFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            f.directoryCall = { AppResult.Success(f.directory) }
            f.changesCall = { cursor ->
                AppResult.Success(
                    SyncChangePage(emptyList(), cursor + "x", true, false, 5, f.clock.now())
                )
            }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.syncPending)
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.content)
            assertEquals(0, f.readers)
        }
    }

    @Test
    fun aCompletedStorageSourceReopensOnlyAfterAdmittedSync() = runTest {
        withModel { f, model, _ ->
            f.replicas.failNextObservation = true
            val release = CompletableDeferred<Unit>()
            f.directoryCall = {
                release.await()
                AppResult.Success(f.directory)
            }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, f.readers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertEquals(2, f.readers)
            assertEquals(1, f.canonicalReads)
        }
    }

    @Test
    fun legacyFiltersRestoreWhenLocalStorageArrivesAfterAnOfflineFailure() = runTest {
        withModel { f, model, _ ->
            val period = overtimeMonth(LocalDate(2025, 6, 1))
            val selection = OvertimeSelection(f.employee.id, period, OvertimeStatus.REJECTED)
            val access =
                (admitOvertime(f.workspace.id, f.workspace, f.session, f.build, f.clock.now())
                        as AppResult.Success)
                    .value
            f.records.value =
                AppResult.Success(
                    OvertimeSnapshot(
                        access,
                        "legacy",
                        OvertimeOverview(f.directory, selection, OvertimePage(emptyList())),
                        f.clock.now(),
                        f.clock.now() + 1.seconds,
                    )
                )
            val release = CompletableDeferred<Unit>()
            f.cacheReadGate = release
            f.directoryCall = { overtimeFailure("client.network", FailureKind.Network) }
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            assertNull(model.state.value.content)
            release.complete(Unit)
            runCurrent()
            assertEquals(period, model.state.value.filters?.period)
            assertEquals(OvertimeStatus.REJECTED, model.state.value.filters?.status)
            assertNotNull(model.state.value.content)
            f.directoryCall = { AppResult.Success(f.directory) }
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertEquals(selection, model.state.value.content?.value?.selection)
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun staleFactsRemainVisibleDuringRefreshAndDoNotStartAnAutomaticRetryLoop() = runTest {
        withModel { f, model, _ ->
            model.onEvent(OvertimeEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            val writes = f.replicas.writes
            var attempts = 0
            val release = CompletableDeferred<Unit>()
            f.directoryCall = {
                attempts++
                release.await()
                overtimeFailure("client.network", FailureKind.Network)
            }
            advanceTimeBy(3 * 24 * 60 * 60_000L)
            runCurrent()
            assertFalse(model.state.value.readRestricted)
            assertEquals(saved.generation, assertNotNull(model.state.value.content).generation)
            assertEquals(0, attempts)
            model.onEvent(OvertimeEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.refreshing)
            assertNotNull(model.state.value.content)
            assertEquals(writes, f.replicas.writes)
            release.complete(Unit)
            runCurrent()
            assertEquals("client.network", model.state.value.failure?.code)
            assertNotNull(model.state.value.content)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, attempts)
            f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (OvertimePresentationFixture, OvertimeViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = OvertimePresentationFixture {
            Instant.parse("2026-10-12T01:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = OvertimeViewModel(f.workspace.id, f.observe, f.sync)
        val owner = ViewModelStore().apply { put("overtime", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, f.readers)
            Dispatchers.resetMain()
        }
    }
}

private val OvertimePresentationFixture.readers: Int
    get() = observers + replicas.directoryObservers + replicas.pageObservers
