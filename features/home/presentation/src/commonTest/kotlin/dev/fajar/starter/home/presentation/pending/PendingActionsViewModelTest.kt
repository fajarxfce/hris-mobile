@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.home.presentation.pending

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.home.presentation.datasync.DataSyncFixture
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class PendingActionsViewModelTest {
    @Test
    fun entryLocalUpdatesStopReturnAndDisposalOwnTheirCollectors() = runTest {
        withModel { probes, model, owner ->
            model.onEvent(PendingActionsEvent.Entered)
            model.onEvent(PendingActionsEvent.Entered)
            runCurrent()
            assertTrue(probes.all { it.scopes == listOf("selected-workspace") && it.active == 1 })
            val untouched = model.state.value.rows.last()
            probes.first().values.value = AppResult.Success(PendingActionSummary(queued = 2))
            runCurrent()
            assertEquals(2, model.state.value.rows.first().summary?.queued)
            assertSame(untouched, model.state.value.rows.last())
            model.onEvent(PendingActionsEvent.Stopped)
            runCurrent()
            assertTrue(probes.all { it.active == 0 })
            assertTrue(model.state.value.rows.all { it.summary == null })
            model.onEvent(PendingActionsEvent.ReloadRequested)
            runCurrent()
            assertTrue(probes.all { it.scopes.size == 1 })
            model.onEvent(PendingActionsEvent.Entered)
            runCurrent()
            assertEquals(2, model.state.value.rows.first().summary?.queued)
            assertTrue(probes.all { it.scopes.size == 2 && it.active == 1 })
            owner.clear()
            runCurrent()
            assertTrue(probes.all { it.active == 0 })
        }
    }

    @Test
    fun localReloadRecoversACompletedReadFailureWithoutResettingOtherRows() = runTest {
        withModel { probes, model, _ ->
            probes.first().read = {
                flowOf(AppResult.Failed(Failure(FailureKind.Storage, "Unavailable")))
            }
            model.onEvent(PendingActionsEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.rows.first().failure?.kind)
            assertTrue(model.state.value.rows.drop(1).all { it.summary != null })
            probes.first().read = { probes.first().values }
            model.onEvent(PendingActionsEvent.ReloadRequested)
            runCurrent()
            assertTrue(model.state.value.rows.all { it.failure == null && !it.loading })
            assertTrue(probes.all { it.active == 1 })
        }
    }

    @Test
    fun repeatedReloadWaitsForCancelledReaderAndNeverPublishesItsLateResult() = runTest {
        val release = CompletableDeferred<Unit>()
        try {
            withModel { probes, model, _ ->
                val probe = probes.first()
                probe.read = {
                    flow {
                        withContext(NonCancellable) { release.await() }
                        emit(AppResult.Success(PendingActionSummary(queued = 99)))
                    }
                }
                model.onEvent(PendingActionsEvent.Entered)
                runCurrent()
                probe.read = { probe.values }
                repeat(3) {
                    model.onEvent(PendingActionsEvent.ReloadRequested)
                    runCurrent()
                }
                assertEquals(1, probe.active)
                assertEquals(1, probe.scopes.size)
                assertNull(model.state.value.rows.first().summary)
                release.complete(Unit)
                runCurrent()
                assertEquals(2, probe.scopes.size)
                assertEquals(1, probe.peak)
                assertEquals(PendingActionSummary(), model.state.value.rows.first().summary)
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun denialSurvivesPendingAndFailedReloadUntilTheSourceAdmitsTheRead() = runTest {
        withModel { probes, model, _ ->
            val denial = Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required")
            val probe = probes.first()
            probe.values.value = AppResult.Failed(denial)
            model.onEvent(PendingActionsEvent.Entered)
            runCurrent()
            model.onEvent(PendingActionsEvent.ReloadRequested)
            assertEquals(denial, model.state.value.rows.first().failure)
            probe.values.value = AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
            runCurrent()
            assertTrue(model.state.value.needsVerification)
            assertEquals(denial, model.state.value.rows.first().failure)
            assertNull(model.state.value.rows.first().summary)
            probe.values.value = AppResult.Success(PendingActionSummary(drafts = 1))
            runCurrent()
            assertFalse(model.state.value.needsVerification)
            assertEquals(1, model.state.value.rows.first().summary?.drafts)
        }
    }

    @Test
    fun navigationStopsObservationAndIgnoresDuplicateInput() = runTest {
        withModel { probes, model, _ ->
            val effects = mutableListOf<PendingActionsEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(PendingActionsEvent.Entered)
            runCurrent()
            model.onEvent(PendingActionsEvent.OpenRequested(PendingActionGroup.Leave))
            model.onEvent(PendingActionsEvent.OpenRequested(PendingActionGroup.Leave))
            model.onEvent(PendingActionsEvent.BackRequested)
            runCurrent()
            assertEquals(
                listOf<PendingActionsEffect>(PendingActionsEffect.Open(PendingActionGroup.Leave)),
                effects,
            )
            assertTrue(probes.all { it.active == 0 })
            model.onEvent(PendingActionsEvent.Entered)
            runCurrent()
            model.onEvent(PendingActionsEvent.BackRequested)
            runCurrent()
            assertEquals(PendingActionsEffect.Back, effects.last())
            assertTrue(probes.all { it.active == 0 })
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(List<PendingProbe>, PendingActionsViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val probes = PendingActionGroup.entries.map(::PendingProbe)
            val model = PendingActionsViewModel(DataSyncFixture().workspace, probes)
            owner.put("pending", model)
            block(probes, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}

private class PendingProbe(override val group: PendingActionGroup) : ObservePendingActions {
    val values =
        MutableStateFlow<AppResult<PendingActionSummary>>(AppResult.Success(PendingActionSummary()))
    var read: () -> Flow<AppResult<PendingActionSummary>> = { values }
    val scopes = mutableListOf<String>()
    var active = 0
    var peak = 0

    override fun observePending(workspaceId: String) = flow {
        scopes += workspaceId
        active++
        peak = maxOf(peak, active)
        try {
            emitAll(read())
        } finally {
            active--
        }
    }
}
