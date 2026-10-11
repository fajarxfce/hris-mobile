@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailure
import dev.fajar.starter.leave.presentation.overview.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class LeaveViewModelTest {
    @Test
    fun enteringTwiceOwnsOneSubscriptionAndLeavingCancelsPendingRead() = runTest {
        withLeave { fixture, model, owner ->
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            assertEquals(1, fixture.observers)
            assertEquals(2026, model.state.value.content?.selection?.year)
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            assertEquals(1, fixture.directoryReads)
            var cancelled = false
            fixture.balancesCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.refreshing)
            model.onEvent(LeaveEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, fixture.observers)
            assertNull(model.state.value.content)
            fixture.balancesCall = { AppResult.Success(LeavePage(listOf(fixture.balance))) }
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            assertEquals(1, fixture.observers)
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, fixture.observers)
        }
    }

    @Test
    fun changingYearCancelsAnOlderRefreshAndRejectsItsLateFailure() = runTest {
        withLeave { fixture, model, _ ->
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            var cancelled = false
            fixture.balancesCall = { year ->
                if (year == 2026) {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                    leaveFailure("mfa_required", FailureKind.AccessDenied)
                } else AppResult.Success(LeavePage(listOf(fixture.balance.copy(year = year))))
            }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            model.onEvent(LeaveEvent.YearRequested(2025))
            runCurrent()
            assertTrue(cancelled)
            assertEquals(2025, model.state.value.content?.selection?.year)
            assertNull(model.state.value.refreshFailure)
            assertFalse(model.state.value.needsVerification)
            assertEquals(listOf(2026, 2026, 2025), fixture.years)
        }
    }

    @Test
    fun authorityFailureKeepsSavedContentHiddenThroughoutPendingRetry() = runTest {
        withLeave { fixture, model, _ ->
            val effects = mutableListOf<LeaveEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            fixture.rejectWrite = true
            fixture.directoryCall = { leaveFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            assertNull(model.state.value.content)
            assertEquals(0, fixture.observers)
            assertTrue(model.state.value.needsVerification)
            assertNotNull((fixture.records.value as AppResult.Success).value)
            model.onEvent(LeaveEvent.VerificationRequested)
            runCurrent()
            assertEquals(listOf<LeaveEffect>(LeaveEffect.VerifyAccount), effects)
            val release = CompletableDeferred<Unit>()
            fixture.rejectWrite = false
            fixture.directoryCall = {
                release.await()
                AppResult.Success(fixture.directory)
            }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            assertEquals(0, fixture.observers)
            assertNull(model.state.value.content)
            assertTrue(model.state.value.needsVerification)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertEquals(1, fixture.observers)
            assertFalse(model.state.value.needsVerification)
        }
    }

    @Test
    fun cacheExpiryDuringAcquisitionStaysRestrictedUntilFreshPublication() = runTest {
        withLeave { fixture, model, _ ->
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            fixture.directoryCall = {
                release.await()
                AppResult.Success(fixture.directory)
            }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            val saved = fixture.records.value
            fixture.records.value =
                leaveFailure("client.leave_cache_expired", FailureKind.AccessDenied)
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            assertEquals(0, fixture.observers)
            fixture.records.value = saved
            runCurrent()
            assertNull(model.state.value.content)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertEquals(1, fixture.observers)
        }
    }

    @Test
    fun offlineRestorationRetainsSavedFiltersWhenCacheArrivesAfterFailure() = runTest {
        withLeave { fixture, model, _ ->
            fixture.load(LeaveSelection(year = 2025, status = LeaveStatus.APPROVED))
            val release = CompletableDeferred<Unit>()
            fixture.cacheReadGate = release
            fixture.directoryCall = { leaveFailure("client.network", FailureKind.Network) }
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            assertNull(model.state.value.content)
            assertNotNull(model.state.value.refreshFailure)
            release.complete(Unit)
            runCurrent()
            assertEquals(2025, model.state.value.selection?.year)
            assertEquals(LeaveStatus.APPROVED, model.state.value.selection?.status)
            fixture.directoryCall = { AppResult.Success(fixture.directory) }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            assertEquals(2025, model.state.value.content?.selection?.year)
            assertEquals(
                LeaveStatus.APPROVED,
                model.state.value.content?.requests?.items?.single()?.status,
            )
            assertNull(model.state.value.refreshFailure)
        }
    }

    @Test
    fun offlineRestorationRetainsSavedFiltersWhenCacheArrivesBeforeFailure() = runTest {
        withLeave { fixture, model, _ ->
            fixture.load(LeaveSelection(year = 2025, status = LeaveStatus.REJECTED))
            val release = CompletableDeferred<Unit>()
            fixture.directoryCall = {
                release.await()
                leaveFailure("client.network", FailureKind.Network)
            }
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            assertEquals(2025, model.state.value.content?.selection?.year)
            release.complete(Unit)
            runCurrent()
            assertEquals(2025, model.state.value.selection?.year)
            assertEquals(LeaveStatus.REJECTED, model.state.value.selection?.status)
        }
    }

    @Test
    fun filterActionsUseOnlyVisibleChoicesAndScopeReplacementHidesThePage() = runTest {
        withLeave { fixture, model, _ ->
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            model.onEvent(LeaveEvent.YearRequested(1800))
            model.onEvent(LeaveEvent.RequestsPageRequested("33333333-3333-4333-8333-333333333333"))
            model.onEvent(LeaveEvent.EmploymentSelected("other"))
            model.onEvent(LeaveEvent.StatusSelected(LeaveStatus.APPROVED))
            runCurrent()
            assertEquals(1, fixture.directoryReads)
            model.onEvent(LeaveEvent.StatusFilterRequested)
            model.onEvent(LeaveEvent.StatusSelected(LeaveStatus.APPROVED))
            runCurrent()
            assertEquals(LeaveStatus.APPROVED, model.state.value.content?.selection?.status)
            assertNull(model.state.value.dialog)
            fixture.workspaceState.value =
                AppResult.Success(fixture.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
            assertEquals(0, fixture.observers)
        }
    }

    @Test
    fun anUnavailableYearCanBeChangedBackWithoutLeavingThePage() = runTest {
        withLeave { fixture, model, _ ->
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            fixture.directoryCall = { leaveFailure("client.network", FailureKind.Network) }
            model.onEvent(LeaveEvent.YearRequested(2025))
            runCurrent()
            assertNull(model.state.value.content)
            assertEquals(2025, model.state.value.filters?.year)
            fixture.directoryCall = { AppResult.Success(fixture.directory) }
            model.onEvent(LeaveEvent.YearRequested(2026))
            runCurrent()
            assertEquals(2026, model.state.value.content?.selection?.year)
            assertNull(model.state.value.refreshFailure)
        }
    }

    @Test
    fun failedRetriesCannotReplaceAnAuthoritativeDenialWithReadableOldCache() = runTest {
        withLeave { fixture, model, _ ->
            model.onEvent(LeaveEvent.Entered)
            runCurrent()
            fixture.rejectWrite = true
            fixture.directoryCall = { leaveFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            for (kind in listOf(FailureKind.Network, FailureKind.Storage)) {
                fixture.directoryCall = { leaveFailure("client.retry_failed", kind) }
                model.onEvent(LeaveEvent.RefreshRequested)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertNull(model.state.value.content)
                assertTrue(model.state.value.needsVerification)
                assertEquals(0, fixture.observers)
            }
            fixture.rejectWrite = false
            fixture.directoryCall = { AppResult.Success(fixture.directory) }
            model.onEvent(LeaveEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
        }
    }

    private suspend fun TestScope.withLeave(
        test: suspend (LeavePresentationFixture, LeaveViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = LeavePresentationFixture()
        val model = LeaveViewModel(fixture.workspace.id, fixture.observe, fixture.refresh)
        val owner = ViewModelStore().apply { put("leave", model) }
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
