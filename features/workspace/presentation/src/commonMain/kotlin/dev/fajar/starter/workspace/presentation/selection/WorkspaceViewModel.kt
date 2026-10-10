package dev.fajar.starter.workspace.presentation.selection

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.usecases.LoadAccount
import dev.fajar.starter.identity.domain.usecases.SignOut
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.usecases.SelectWorkspace
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class WorkspaceViewModel(
    @InjectedParam private val sessionId: String,
    private val loadAccount: LoadAccount,
    private val selectWorkspace: SelectWorkspace,
    private val signOut: SignOut,
) : MviViewModel<WorkspaceState, WorkspaceEvent, WorkspaceEffect>(WorkspaceState()) {
    init {
        on<WorkspaceEvent.CompaniesRequested>(::onCompaniesRequested)
        on<WorkspaceEvent.CompanySelected>(::onCompanySelected)
        on<WorkspaceEvent.SignOutRequested>(::onSignOutRequested)
        onEvent(WorkspaceEvent.CompaniesRequested)
    }

    private fun onCompaniesRequested(event: WorkspaceEvent.CompaniesRequested) {
        if (state.value.loading || state.value.selectingId != null) return
        updateState { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            when (val result = loadAccount(sessionId)) {
                is AppResult.Failed ->
                    updateState { it.copy(loading = false, failure = result.failure) }
                is AppResult.Success -> {
                    val assurance = result.value.assurance
                    if (assurance.required && !assurance.verified) {
                        updateState { it.copy(loading = false, companies = emptyList()) }
                        emitEffect(WorkspaceEffect.VerificationRequired)
                    } else
                        updateState { it.copy(loading = false, companies = result.value.companies) }
                }
            }
        }
    }

    private fun onCompanySelected(event: WorkspaceEvent.CompanySelected) {
        if (state.value.loading || state.value.selectingId != null) return
        updateState { it.copy(selectingId = event.companyId, failure = null) }
        viewModelScope.launch {
            when (val result = selectWorkspace(sessionId, event.companyId)) {
                is AppResult.Success -> updateState { it.copy(selectingId = null) }
                is AppResult.Failed -> {
                    updateState { it.copy(selectingId = null, failure = result.failure) }
                    if (
                        result.failure.code in
                            setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
                    )
                        emitEffect(WorkspaceEffect.VerificationRequired)
                }
            }
        }
    }

    private fun onSignOutRequested(event: WorkspaceEvent.SignOutRequested) {
        if (state.value.loading || state.value.selectingId != null) return
        updateState { it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = signOut()) {
                is AppResult.Failed ->
                    updateState { it.copy(loading = false, failure = result.failure) }
                is AppResult.Success -> updateState { WorkspaceState() }
            }
        }
    }
}
