@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.presentation.overtime

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailure
import dev.fajar.starter.workforce.presentation.overtime.detail.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class OvertimeRequestViewModelTest {
    @Test
    fun aDifferentCachedRequestCannotReplaceOrExtendTheVisibleRequest() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            val otherId = "77777777-7777-7777-7777-777777777777"
            fixture.detailRecords.value =
                AppResult.Success(
                    saved.copy(
                        generation = "another-request",
                        value =
                            saved.value.copy(
                                selection = saved.value.selection.copy(requestId = otherId),
                                details =
                                    saved.value.details.copy(
                                        request = fixture.request.copy(id = otherId)
                                    ),
                            ),
                    )
                )
            runCurrent()
            assertNull(model.state.value.content)
            assertEquals(fixture.request.id, model.state.value.selection.requestId)
            assertEquals(1, fixture.detailReads)
            model.onEvent(OvertimeRequestEvent.RefreshRequested)
            runCurrent()
            assertEquals(fixture.request.id, model.state.value.content?.value?.details?.request?.id)
        }
    }

    @Test
    fun stoppingCancelsPendingDetailAndRejectsItsLateFailureBeforeReentry() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeRequestEvent.Entered)
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            assertEquals(1, fixture.detailReads)
            var cancelled = false
            fixture.detailsCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    cancelled = true
                }
                overtimeFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(OvertimeRequestEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            model.onEvent(OvertimeRequestEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertFalse(model.state.value.refreshing)
            assertEquals(0, fixture.observers)
            fixture.detailsCall = { AppResult.Success(fixture.details) }
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertEquals(1, fixture.observers)
        }
    }

    @Test
    fun historyAcceptsOnlyItsAdvertisedCursorAndKeepsTheOwnedRequestScope() = runTest {
        withModel { fixture, model, _ ->
            fixture.detailsCall = { selection ->
                AppResult.Success(
                    fixture.details.copy(
                        history =
                            OvertimePage(
                                fixture.details.history.items,
                                if (selection.historyAfter == null) "0" else null,
                            )
                    )
                )
            }
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            model.onEvent(OvertimeRequestEvent.HistoryPageRequested(900))
            runCurrent()
            assertEquals(1, fixture.detailReads)
            model.onEvent(OvertimeRequestEvent.HistoryPageRequested(0))
            runCurrent()
            assertEquals(0L, model.state.value.selection.historyAfter)
            assertEquals(fixture.request.id, model.state.value.content?.value?.details?.request?.id)
            model.onEvent(OvertimeRequestEvent.HistoryPageRequested(null))
            runCurrent()
            assertEquals(listOf(null, 0L, null), fixture.detailSelections.map { it.historyAfter })
            assertTrue(
                fixture.detailSelections.all {
                    it.employeeId == fixture.employee.id && it.requestId == fixture.request.id
                }
            )
        }
    }

    @Test
    fun anUnavailableHistoryPageCanReturnToLatestWithoutRecreatingTheRoute() = runTest {
        withModel { fixture, model, _ ->
            fixture.detailsCall = { selection ->
                if (selection.historyAfter == null)
                    AppResult.Success(
                        fixture.details.copy(
                            history = OvertimePage(fixture.details.history.items, "0")
                        )
                    )
                else overtimeFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            model.onEvent(OvertimeRequestEvent.HistoryPageRequested(0))
            runCurrent()
            assertNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            model.onEvent(OvertimeRequestEvent.HistoryPageRequested(null))
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertNull(model.state.value.selection.historyAfter)
        }
    }

    @Test
    fun aDeniedDetailRemainsHiddenThroughPendingAndFailedRetriesWithRetainedCiphertext() = runTest {
        withModel { fixture, model, _ ->
            val effects = mutableListOf<OvertimeRequestEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            fixture.failCleanup = true
            fixture.detailsCall = { overtimeFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(OvertimeRequestEvent.RefreshRequested)
            runCurrent()
            assertNotNull((fixture.detailRecords.value as AppResult.Success).value)
            assertNull(model.state.value.content)
            for (kind in listOf(FailureKind.Network, FailureKind.Storage)) {
                fixture.detailsCall = { overtimeFailure("fixture.retry", kind) }
                model.onEvent(OvertimeRequestEvent.RefreshRequested)
                runCurrent()
                assertTrue(model.state.value.readRestricted)
                assertTrue(model.state.value.needsVerification)
                assertEquals(0, fixture.observers)
            }
            model.onEvent(OvertimeRequestEvent.VerificationRequested)
            runCurrent()
            assertEquals(
                listOf<OvertimeRequestEffect>(OvertimeRequestEffect.VerifyAccount),
                effects,
            )
            val release = CompletableDeferred<Unit>()
            fixture.detailsCall = {
                release.await()
                AppResult.Success(fixture.details)
            }
            model.onEvent(OvertimeRequestEvent.RefreshRequested)
            runCurrent()
            assertNull(model.state.value.content)
            assertEquals(0, fixture.observers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
        }
    }

    @Test
    fun detailObservationRecoversAfterItsStorageSourceHasEnded() = runTest {
        withModel { fixture, model, _ ->
            fixture.failNextDetailObservation = true
            val release = CompletableDeferred<Unit>()
            fixture.detailsCall = {
                release.await()
                AppResult.Success(fixture.details)
            }
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            assertEquals(0, fixture.observers)
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertEquals(1, fixture.observers)
            assertNull(model.state.value.failure)
        }
    }

    @Test
    fun expiryRenewsDetailsButAccountReplacementStillRemovesPrivateContent() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            fixture.detailRecords.value =
                AppResult.Success(saved.copy(expiresAt = fixture.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertNotEquals(saved.generation, assertNotNull(model.state.value.content).generation)
            assertFalse(model.state.value.readRestricted)
            assertEquals(2, fixture.detailReads)
            fixture.sessionState.value =
                AppResult.Success(fixture.session.copy(id = "another-session"))
            runCurrent()
            assertNull(model.state.value.content)
            assertEquals(0, fixture.observers)
            assertEquals(2, fixture.detailReads)
        }
    }

    @Test
    fun expiredVisibleReadRenewsWithoutAnErrorWhilePending() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            val writes = fixture.writes
            val release = CompletableDeferred<Unit>()
            fixture.detailsCall = {
                release.await()
                AppResult.Success(fixture.details)
            }
            fixture.detailRecords.value =
                AppResult.Success(saved.copy(expiresAt = fixture.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(model.state.value.refreshing)
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.content)
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
    fun failedAutomaticRenewalRetainsTheReadGateAndStopsRetrying() = runTest {
        withModel { fixture, model, _ ->
            model.onEvent(OvertimeRequestEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            var attempts = 0
            fixture.detailsCall = {
                attempts++
                overtimeFailure("client.network", FailureKind.Network)
            }
            fixture.detailRecords.value =
                AppResult.Success(saved.copy(expiresAt = fixture.clock.now() + 1.seconds))
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
            fixture.detailsCall = { AppResult.Success(fixture.details) }
            model.onEvent(OvertimeRequestEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertFalse(model.state.value.readRestricted)
            assertNull(model.state.value.failure)
        }
    }

    private suspend fun TestScope.withModel(
        test:
            suspend (OvertimePresentationFixture, OvertimeRequestViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = OvertimePresentationFixture {
            Instant.parse("2026-10-12T01:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model =
            OvertimeRequestViewModel(
                OvertimeRequestTarget(fixture.workspace.id, fixture.selection),
                fixture.observeRequest,
                fixture.refreshRequest,
            )
        val owner = ViewModelStore().apply { put("overtime-request", model) }
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
