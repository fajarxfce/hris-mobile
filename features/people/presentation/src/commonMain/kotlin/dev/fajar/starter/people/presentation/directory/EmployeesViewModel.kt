package dev.fajar.starter.people.presentation.directory

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.usecases.LoadCompanyDirectory
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspace
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChangedBy
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class EmployeesViewModel(
    @InjectedParam private val workspaceId: String,
    private val load: LoadCompanyDirectory,
    private val observeWorkspace: ObserveWorkspace,
) : MviViewModel<EmployeesState, EmployeesEvent, EmployeesEffect>(EmployeesState()) {
    private var request: Job? = null
    private var observation: Job? = null

    init {
        on<EmployeesEvent.Entered>(::onEntered)
        on<EmployeesEvent.Stopped>(::onStopped)
        on<EmployeesEvent.QueryChanged>(::onQueryChanged)
        on<EmployeesEvent.PageRequested>(::onPageRequested)
        on<EmployeesEvent.RefreshRequested>(::onRefreshRequested)
        on<EmployeesEvent.BackRequested>(::onBackRequested)
        on<EmployeesEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: EmployeesEvent.Entered) {
        if (observation?.isActive == true) return
        observation =
            viewModelScope.launch {
                observeWorkspace()
                    // Timestamp-only policy refreshes must not restart an employee search.
                    .distinctUntilChangedBy { result ->
                        (result as? AppResult.Success)?.value?.let {
                            Triple(it.id, it.permissions, it.policy?.policy?.enabledModules)
                        }
                    }
                    .collect { loadPage(state.value.after) }
            }
    }

    private fun onStopped(event: EmployeesEvent.Stopped) {
        observation?.cancel()
        observation = null
        request?.cancel()
        request = null
        updateState { it.copy(loading = false) }
    }

    private fun onQueryChanged(event: EmployeesEvent.QueryChanged) {
        val query = event.value.take(120)
        if (query == state.value.query) return
        updateState { EmployeesState(query = query) }
        loadPage(null, 300.milliseconds)
    }

    private fun onPageRequested(event: EmployeesEvent.PageRequested) {
        if (
            state.value.loading ||
                (event.after != null && event.after != state.value.page?.nextCursor)
        )
            return
        loadPage(event.after)
        viewModelScope.launch { emitEffect(EmployeesEffect.ScrollToStart) }
    }

    private fun onRefreshRequested(event: EmployeesEvent.RefreshRequested) {
        if (request?.isActive != true) loadPage(state.value.after)
    }

    private fun onBackRequested(event: EmployeesEvent.BackRequested) {
        viewModelScope.launch { emitEffect(EmployeesEffect.Back) }
    }

    private fun onVerificationRequested(event: EmployeesEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(EmployeesEffect.VerifyAccount) }
    }

    /** This screen owns replacement, debounce and cancellation; the use case owns admission. */
    private fun loadPage(after: String?, debounce: Duration = Duration.ZERO) {
        request?.cancel()
        val query = state.value.query
        updateState {
            it.copy(
                after = after,
                page = if (after == it.after) it.page else null,
                loading = true,
                failure = null,
                needsVerification = false,
            )
        }
        request =
            viewModelScope.launch {
                delay(debounce)
                val result = load(workspaceId, query, after)
                ensureActive()
                when (result) {
                    is AppResult.Success ->
                        updateState { it.copy(page = result.value, loading = false) }
                    is AppResult.Failed ->
                        updateState {
                            it.copy(
                                page =
                                    if (
                                        result.failure.kind in
                                            setOf(FailureKind.Network, FailureKind.Timeout)
                                    )
                                        it.page
                                    else null,
                                loading = false,
                                failure = result.failure,
                                needsVerification =
                                    result.failure.code in
                                        setOf(
                                            "mfa_required",
                                            "mfa_setup_required",
                                            "mfa_verification_required",
                                        ),
                            )
                        }
                }
            }
    }
}
