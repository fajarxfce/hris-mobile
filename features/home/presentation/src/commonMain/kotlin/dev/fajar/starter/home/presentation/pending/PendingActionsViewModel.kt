package dev.fajar.starter.home.presentation.pending

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class PendingActionsViewModel(
    @InjectedParam workspace: Workspace,
    observations: List<ObservePendingActions>,
) :
    MviViewModel<PendingActionsState, PendingActionsEvent, PendingActionsEffect>(
        PendingActionsState(
            workspace.company.name,
            observations.sortedBy { it.group.ordinal }.map { PendingActionsRowState(it.group) },
        )
    ) {
    private val workspaceId = workspace.id
    private val observers = observations.associateBy { it.group }
    private val slots = observers.mapValues { Mutex() }
    private val reads = mutableMapOf<PendingActionGroup, Job>()
    private var visible = false

    init {
        require(observers.size == observations.size) {
            "Each pending action group must have one observer."
        }
        on<PendingActionsEvent.Entered>(::onEntered)
        on<PendingActionsEvent.Stopped>(::onStopped)
        on<PendingActionsEvent.ReloadRequested>(::onReloadRequested)
        on<PendingActionsEvent.OpenRequested>(::onOpenRequested)
        on<PendingActionsEvent.VerificationRequested>(::onVerificationRequested)
        on<PendingActionsEvent.BackRequested>(::onBackRequested)
    }

    private fun onEntered(event: PendingActionsEvent.Entered) {
        if (visible) return
        visible = true
        observers.keys.forEach(::observe)
    }

    private fun onStopped(event: PendingActionsEvent.Stopped) {
        stop()
    }

    private fun onReloadRequested(event: PendingActionsEvent.ReloadRequested) {
        if (!visible) return
        observers.keys.forEach(::observe)
    }

    private fun observe(group: PendingActionGroup) {
        reads[group]?.cancel()
        updateRow(group) { it.copy(summary = null, loading = true) }
        reads[group] =
            viewModelScope.launch {
                // A replacement waits for any cancelled non-cooperative storage read to release its
                // slot.
                slots.getValue(group).withLock {
                    observers.getValue(group).observePending(workspaceId).collect { result ->
                        ensureActive()
                        updateRow(group) { row ->
                            when (result) {
                                is AppResult.Success ->
                                    row.copy(
                                        summary = result.value,
                                        loading = false,
                                        failure = null,
                                    )
                                is AppResult.Failed ->
                                    row.copy(
                                        summary = null,
                                        loading = false,
                                        failure =
                                            row.failure?.takeIf {
                                                it.kind in
                                                    setOf(
                                                        FailureKind.AccessDenied,
                                                        FailureKind.Unauthorized,
                                                    )
                                            } ?: result.failure,
                                    )
                            }
                        }
                    }
                }
            }
    }

    private fun stop() {
        visible = false
        reads.values.toList().forEach { it.cancel() }
        updateState { state ->
            state.copy(rows = state.rows.map { it.copy(summary = null, loading = true) })
        }
    }

    private fun updateRow(
        group: PendingActionGroup,
        reduce: (PendingActionsRowState) -> PendingActionsRowState,
    ) {
        updateState { current ->
            current.copy(rows = current.rows.map { if (it.group == group) reduce(it) else it })
        }
    }

    private fun onOpenRequested(event: PendingActionsEvent.OpenRequested) {
        if (!visible || event.group !in observers) return
        stop()
        viewModelScope.launch { emitEffect(PendingActionsEffect.Open(event.group)) }
    }

    private fun onVerificationRequested(event: PendingActionsEvent.VerificationRequested) {
        if (!visible || !state.value.needsVerification) return
        stop()
        viewModelScope.launch { emitEffect(PendingActionsEffect.VerifyAccount) }
    }

    private fun onBackRequested(event: PendingActionsEvent.BackRequested) {
        if (!visible) return
        stop()
        viewModelScope.launch { emitEffect(PendingActionsEffect.Back) }
    }
}
