@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailure
import dev.fajar.starter.leave.presentation.detail.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class LeaveRequestViewModelTest {
    @Test
    fun routeLifetimeOwnsOneSubscriptionAndCancelsPendingAcquisition() = runTest {
        withRequest { fixture, model, owner ->
            model.onEvent(LeaveRequestEvent.Entered)
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            assertEquals(1, fixture.observers)
            assertEquals(1, fixture.reads)
            assertNotNull(model.state.value.content)
            var cancelled = false
            fixture.requestCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(LeaveRequestEvent.RefreshRequested)
            runCurrent()
            model.onEvent(LeaveRequestEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, fixture.observers)
            assertNull(model.state.value.content)
            fixture.requestCall = { AppResult.Success(fixture.details) }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            assertEquals(1, fixture.observers)
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, fixture.observers)
        }
    }

    @Test
    fun successfulRefreshRestartsAStorageSourceThatCompletedAfterFailureWithoutPolling() = runTest {
        withRequest { fixture, model, _ ->
            val release = CompletableDeferred<Unit>()
            fixture.failNextObservation = true
            fixture.requestCall = {
                release.await()
                AppResult.Success(fixture.details)
            }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.cacheFailure?.kind)
            assertEquals(0, fixture.observers)
            assertEquals(1, fixture.subscriptions)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, fixture.subscriptions)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.observers)
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, fixture.reads)
        }
    }

    @Test
    fun authorityDenialSurvivesCleanupFailurePendingAndFailedRetries() = runTest {
        withRequest { fixture, model, _ ->
            val effects = mutableListOf<LeaveRequestEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            fixture.cleanupFails = true
            fixture.requestCall = { leaveFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(LeaveRequestEvent.RefreshRequested)
            runCurrent()
            assertNotNull((fixture.records.value as AppResult.Success).value)
            assertNull(model.state.value.content)
            assertEquals(0, fixture.observers)
            for (kind in listOf(FailureKind.Network, FailureKind.Storage)) {
                val release = CompletableDeferred<Unit>()
                fixture.requestCall = {
                    release.await()
                    leaveFailure("client.retry_failed", kind)
                }
                model.onEvent(LeaveRequestEvent.RefreshRequested)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertNull(model.state.value.content)
                release.complete(Unit)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertEquals("mfa_required", model.state.value.failure?.code)
                assertTrue(model.state.value.needsVerification)
                assertEquals(0, fixture.observers)
            }
            model.onEvent(LeaveRequestEvent.VerificationRequested)
            runCurrent()
            assertEquals(listOf<LeaveRequestEffect>(LeaveRequestEffect.VerifyAccount), effects)
            fixture.requestCall = { AppResult.Success(fixture.details) }
            model.onEvent(LeaveRequestEvent.RefreshRequested)
            runCurrent()
            assertFalse(model.state.value.readRestricted)
            assertNotNull(model.state.value.content)
            assertEquals(1, fixture.observers)
        }
    }

    @Test
    fun cacheExpiryDuringRefreshHidesContentUntilTheAdmittedPublication() = runTest {
        withRequest { fixture, model, _ ->
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            fixture.requestCall = {
                release.await()
                AppResult.Success(fixture.details)
            }
            model.onEvent(LeaveRequestEvent.RefreshRequested)
            runCurrent()
            val saved = fixture.records.value
            fixture.records.value =
                leaveFailure("client.leave_cache_expired", FailureKind.AccessDenied)
            runCurrent()
            fixture.records.value = saved
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
        }
    }

    @Test
    fun offlineCacheRestoresTheSavedHistorySelectionInEitherArrivalOrder() = runTest {
        for (cacheFirst in listOf(false, true)) withRequest { fixture, model, _ ->
            fixture.load(fixture.selection.copy(historyAfter = 3))
            val release = CompletableDeferred<Unit>()
            if (!cacheFirst) fixture.cacheReadGate = release
            fixture.requestCall = {
                if (cacheFirst) release.await()
                leaveFailure("client.network", FailureKind.Network)
            }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(3L, model.state.value.visibleSelection?.historyAfter)
            assertNotNull(model.state.value.content)
            fixture.requestCall = { AppResult.Success(fixture.details) }
            model.onEvent(LeaveRequestEvent.RefreshRequested)
            runCurrent()
            assertEquals(3L, fixture.selections.last().historyAfter)
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun historyUsesVisibleCursorsAndKeepsCurrentStatusWhileViewingOlderRevisions() = runTest {
        withRequest { fixture, model, _ ->
            val current =
                fixture.details.copy(status = LeaveStatus.CANCELLATION_PENDING, version = 12)
            fixture.requestCall = { selected ->
                AppResult.Success(
                    current.copy(
                        history =
                            if (selected.historyAfter == null)
                                LeavePage(
                                    listOf(
                                        fixture.details.history.items.single().copy(version = 12)
                                    ),
                                    "3",
                                )
                            else fixture.details.history
                    )
                )
            }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            model.onEvent(LeaveRequestEvent.HistoryPageRequested("999"))
            model.onEvent(LeaveRequestEvent.HistoryPageRequested("invalid"))
            runCurrent()
            assertEquals(1, fixture.reads)
            model.onEvent(LeaveRequestEvent.HistoryPageRequested("3"))
            runCurrent()
            assertEquals(3L, model.state.value.content?.selection?.historyAfter)
            assertEquals(
                LeaveStatus.CANCELLATION_PENDING,
                model.state.value.content?.request?.status,
            )
            assertEquals(12L, model.state.value.content?.request?.version)
            assertEquals(0L, model.state.value.content?.request?.history?.items?.single()?.version)
            model.onEvent(LeaveRequestEvent.HistoryPageRequested(null))
            runCurrent()
            assertNull(model.state.value.content?.selection?.historyAfter)
            assertEquals(12L, model.state.value.content?.request?.history?.items?.single()?.version)
        }
    }

    @Test
    fun returningToLatestCancelsAnOlderPageAndRejectsItsLateDenial() = runTest {
        withRequest { fixture, model, _ ->
            var cancelled = false
            val current =
                fixture.details.copy(history = fixture.details.history.copy(nextCursor = "3"))
            fixture.requestCall = { selected ->
                if (selected.historyAfter == null) AppResult.Success(current)
                else {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                    leaveFailure("mfa_required", FailureKind.AccessDenied)
                }
            }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            model.onEvent(LeaveRequestEvent.HistoryPageRequested("3"))
            runCurrent()
            assertTrue(model.state.value.refreshing)
            model.onEvent(LeaveRequestEvent.HistoryPageRequested(null))
            runCurrent()
            assertTrue(cancelled)
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertFalse(model.state.value.needsVerification)
        }
    }

    @Test
    fun latestHistoryRemainsReachableAfterAnEarlierPageFails() = runTest {
        withRequest { fixture, model, _ ->
            val current =
                fixture.details.copy(history = fixture.details.history.copy(nextCursor = "3"))
            fixture.requestCall = { selected ->
                if (selected.historyAfter == null) AppResult.Success(current)
                else leaveFailure("client.network", FailureKind.Network)
            }
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            model.onEvent(LeaveRequestEvent.HistoryPageRequested("3"))
            runCurrent()
            assertNull(model.state.value.content)
            assertEquals(3L, model.state.value.visibleSelection?.historyAfter)
            model.onEvent(LeaveRequestEvent.HistoryPageRequested(null))
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.visibleSelection?.historyAfter)
        }
    }

    @Test
    fun replacedWorkspaceHidesContentAndCannotAcquireThroughTheOldRoute() = runTest {
        withRequest { fixture, model, _ ->
            model.onEvent(LeaveRequestEvent.Entered)
            runCurrent()
            fixture.base.workspaceState.value =
                AppResult.Success(fixture.base.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
            model.onEvent(LeaveRequestEvent.RefreshRequested)
            runCurrent()
            assertEquals(1, fixture.reads)
            assertEquals(0, fixture.observers)
        }
    }

    private suspend fun TestScope.withRequest(
        test:
            suspend (LeaveRequestPresentationFixture, LeaveRequestViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = LeaveRequestPresentationFixture()
        val model =
            LeaveRequestViewModel(
                LeaveRequestTarget(fixture.base.workspace.id, fixture.details.id),
                fixture.observe,
                fixture.refresh,
            )
        val owner = ViewModelStore().apply { put("request", model) }
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
