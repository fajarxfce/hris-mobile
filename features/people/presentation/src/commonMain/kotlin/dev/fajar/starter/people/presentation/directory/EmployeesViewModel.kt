package dev.fajar.starter.people.presentation.directory

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.usecases.LoadCompanyDirectory
import dev.fajar.starter.people.domain.usecases.LoadDirectoryDepartments
import dev.fajar.starter.people.presentation.directory.mappers.toDirectoryContent
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
    private val loadDepartments: LoadDirectoryDepartments,
) : MviViewModel<EmployeesState, EmployeesEvent, EmployeesEffect>(EmployeesState()) {
    private var request: Job? = null
    private var observation: Job? = null
    private var departmentRequest: Job? = null

    init {
        on<EmployeesEvent.Entered>(::onEntered)
        on<EmployeesEvent.Stopped>(::onStopped)
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
                    .collect {
                        departmentRequest?.cancel()
                        departmentRequest = null
                        updateState { current -> current.copy(filter = null) }
                        loadPage(state.value.after)
                    }
            }
    }

    private fun onStopped(event: EmployeesEvent.Stopped) {
        observation?.cancel()
        observation = null
        request?.cancel()
        request = null
        departmentRequest?.cancel()
        departmentRequest = null
        updateState { it.copy(loading = false, filter = null) }
    }

    private fun onQueryChanged(event: EmployeesEvent.QueryChanged) {
        val query = event.value.take(120)
        if (query == state.value.query) return
        updateState { it.copy(query = query, after = null, page = null) }
        loadPage(null, 300.milliseconds)
    }

    private fun onFilterRequested(event: EmployeesEvent.FilterRequested) {
        if (state.value.filter != null) return
        updateState { it.copy(filter = DepartmentFilterState()) }
        loadDepartmentOptions()
    }

    private fun onFilterDismissed(event: EmployeesEvent.FilterDismissed) {
        departmentRequest?.cancel()
        departmentRequest = null
        updateState { it.copy(filter = null) }
    }

    private fun onDepartmentQueryChanged(event: EmployeesEvent.DepartmentQueryChanged) {
        val filter = state.value.filter ?: return
        val query = event.value.take(120)
        if (filter.query == query) return
        updateState { it.copy(filter = DepartmentFilterState(query = query)) }
        loadDepartmentOptions(300.milliseconds)
    }

    private fun onDepartmentSelected(event: EmployeesEvent.DepartmentSelected) {
        val filter = state.value.filter ?: return
        val choice = filter.result?.items?.firstOrNull { it.id == event.id }
        if (event.id != null && choice == null) return
        departmentRequest?.cancel()
        departmentRequest = null
        updateState { it.copy(department = choice, filter = null, page = null, after = null) }
        loadPage(null)
        viewModelScope.launch { emitEffect(EmployeesEffect.ScrollToStart) }
    }

    private fun onDepartmentRetryRequested(event: EmployeesEvent.DepartmentRetryRequested) {
        if (departmentRequest?.isActive != true) loadDepartmentOptions()
    }

    private fun loadDepartmentOptions(debounce: Duration = Duration.ZERO) {
        val filter = state.value.filter ?: return
        departmentRequest?.cancel()
        updateState { it.copy(filter = filter.copy(loading = true, failure = null)) }
        departmentRequest =
            viewModelScope.launch {
                delay(debounce)
                val result = loadDepartments(workspaceId, filter.query)
                ensureActive()
                when (result) {
                    is AppResult.Success ->
                        updateState {
                            it.copy(filter = filter.copy(result = result.value, loading = false))
                        }
                    is AppResult.Failed -> {
                        val transient =
                            result.failure.kind in setOf(FailureKind.Network, FailureKind.Timeout)
                        if (!transient) request?.cancel()
                        updateState {
                            it.copy(
                                filter =
                                    filter.copy(
                                        result = null,
                                        loading = false,
                                        failure = result.failure,
                                        needsVerification =
                                            result.failure.code in
                                                setOf(
                                                    "mfa_required",
                                                    "mfa_setup_required",
                                                    "mfa_verification_required",
                                                ),
                                    ),
                                page = if (transient) it.page else null,
                                loading = if (transient) it.loading else false,
                            )
                        }
                    }
                }
            }
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
        if (state.value.needsVerification || state.value.filter?.needsVerification == true)
            viewModelScope.launch { emitEffect(EmployeesEffect.VerifyAccount) }
    }

    /** This screen owns replacement, debounce and cancellation; the use case owns admission. */
    private fun loadPage(after: String?, debounce: Duration = Duration.ZERO) {
        request?.cancel()
        val query = state.value.query
        val departmentId = state.value.department?.id
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
                val result = load(workspaceId, query, after, departmentId)
                ensureActive()
                when (result) {
                    is AppResult.Success ->
                        updateState {
                            it.copy(page = result.value.toDirectoryContent(), loading = false)
                        }
                    is AppResult.Failed -> {
                        val transient =
                            result.failure.kind in setOf(FailureKind.Network, FailureKind.Timeout)
                        if (!transient) departmentRequest?.cancel()
                        updateState {
                            it.copy(
                                filter = if (transient) it.filter else null,
                                page = if (transient) it.page else null,
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
}
