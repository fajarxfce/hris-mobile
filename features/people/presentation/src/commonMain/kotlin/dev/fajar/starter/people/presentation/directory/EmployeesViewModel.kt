package dev.fajar.starter.people.presentation.directory

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.replica.usecases.ObserveCompanyDirectory
import dev.fajar.starter.people.domain.replica.usecases.SyncCompanyDirectory
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class EmployeesViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveCompanyDirectory,
    private val sync: SyncCompanyDirectory,
) : MviViewModel<EmployeesState, EmployeesEvent, EmployeesEffect>(EmployeesState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var visible = false
    private var refreshRequired = true

    init {
        on<EmployeesEvent.Entered>(::onEntered)
        on<EmployeesEvent.Stopped>(::onStopped)
        on<EmployeesEvent.SearchRequested>(::onSearchRequested)
        on<EmployeesEvent.FilterRequested>(::onFilterRequested)
        on<EmployeesEvent.FilterDismissed>(::onFilterDismissed)
        on<EmployeesEvent.DepartmentQueryChanged>(::onDepartmentQueryChanged)
        on<EmployeesEvent.DepartmentSelected>(::onDepartmentSelected)
        on<EmployeesEvent.DepartmentRetryRequested>(::onDepartmentRetryRequested)
        on<EmployeesEvent.QueryChanged>(::onQueryChanged)
        on<EmployeesEvent.PageRequested>(::onPageRequested)
        on<EmployeesEvent.RefreshRequested>(::onRefreshRequested)
        on<EmployeesEvent.BackRequested>(::onBackRequested)
        on<EmployeesEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: EmployeesEvent.Entered) {
        if (visible) return
        visible = true
        if (observation?.isActive != true) observeSelection()
        if (refreshRequired) acquire(force = state.value.page != null)
    }

    @OptIn(FlowPreview::class)
    private fun observeSelection() {
        observation?.cancel()
        // The retained tab owns this observer. Scope/denial changes also clear a hidden tab.
        observation =
            viewModelScope.launch {
                observe(
                        workspaceId,
                        state
                            .map { it.directorySelection() }
                            .distinctUntilChanged()
                            .debounce(200.milliseconds),
                    )
                    .collect { result ->
                        ensureActive()
                        updateState { it.withDirectory(result) }
                    }
            }
    }

    private fun onStopped(event: EmployeesEvent.Stopped) {
        visible = false
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(loading = false, syncing = false, filter = null) }
    }

    private fun onQueryChanged(event: EmployeesEvent.QueryChanged) {
        if (!visible || state.value.readRestricted) return
        val query = event.value.take(120)
        if (query == state.value.query) return
        updateState { it.copy(query = query, after = null, page = null, loading = true) }
    }

    private fun onSearchRequested(event: EmployeesEvent.SearchRequested) {
        if (!visible || state.value.filter != null) return
        onStopped(EmployeesEvent.Stopped)
        viewModelScope.launch { emitEffect(EmployeesEffect.OpenSearch) }
    }

    private fun onFilterRequested(event: EmployeesEvent.FilterRequested) {
        if (!visible || state.value.filter != null || state.value.readRestricted) return
        updateState {
            it.copy(
                filter =
                    DepartmentFilterState(
                        result = it.departments,
                        loading = it.departments == null && it.syncing,
                    )
            )
        }
    }

    private fun onFilterDismissed(event: EmployeesEvent.FilterDismissed) {
        updateState { it.copy(filter = null) }
    }

    private fun onDepartmentQueryChanged(event: EmployeesEvent.DepartmentQueryChanged) {
        val filter = state.value.filter ?: return
        if (!visible || state.value.readRestricted) return
        val query = event.value.take(120)
        if (filter.query == query) return
        updateState { it.copy(filter = DepartmentFilterState(query = query)) }
    }

    private fun onDepartmentSelected(event: EmployeesEvent.DepartmentSelected) {
        val filter = state.value.filter ?: return
        if (!visible || state.value.readRestricted) return
        val choice = filter.result?.items?.firstOrNull { it.id == event.id }
        if (event.id != null && choice == null) return
        updateState {
            it.copy(department = choice, filter = null, page = null, after = null, loading = true)
        }
        viewModelScope.launch { emitEffect(EmployeesEffect.ScrollToStart) }
    }

    private fun onDepartmentRetryRequested(event: EmployeesEvent.DepartmentRetryRequested) {
        if (visible) onEvent(EmployeesEvent.RefreshRequested)
    }

    private fun onPageRequested(event: EmployeesEvent.PageRequested) {
        if (
            !visible ||
                state.value.readRestricted ||
                state.value.loading ||
                (event.after != null && event.after != state.value.page?.nextCursor)
        )
            return
        updateState { it.copy(after = event.after, page = null, loading = true) }
        viewModelScope.launch { emitEffect(EmployeesEffect.ScrollToStart) }
    }

    private fun onRefreshRequested(event: EmployeesEvent.RefreshRequested) {
        acquire(force = true)
    }

    private fun acquire(force: Boolean) {
        if (!visible || acquisition?.isActive == true) return
        refreshRequired = true
        updateState { it.copy(syncing = true, loading = it.page == null) }
        acquisition =
            viewModelScope.launch {
                val result = sync(workspaceId, force)
                ensureActive()
                val caughtUp = result is AppResult.Success && result.value.caughtUp
                refreshRequired = result is AppResult.Success && !caughtUp
                updateState { it.withDirectorySync(result) }
                // Also reopens a storage source that terminated after a technical failure.
                if (caughtUp) observeSelection()
            }
    }

    private fun onBackRequested(event: EmployeesEvent.BackRequested) {
        viewModelScope.launch { emitEffect(EmployeesEffect.Back) }
    }

    private fun onVerificationRequested(event: EmployeesEvent.VerificationRequested) {
        if (
            visible &&
                (state.value.needsVerification || state.value.filter?.needsVerification == true)
        )
            viewModelScope.launch { emitEffect(EmployeesEffect.VerifyAccount) }
    }
}
