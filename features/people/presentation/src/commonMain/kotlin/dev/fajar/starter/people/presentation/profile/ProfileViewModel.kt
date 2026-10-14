package dev.fajar.starter.people.presentation.profile

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.profile.usecases.ObserveSelfProfiles
import dev.fajar.starter.people.domain.profile.usecases.SyncSelfProfiles
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ProfileViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveSelfProfiles,
    private val sync: SyncSelfProfiles,
) : MviViewModel<ProfileState, ProfileEvent, ProfileEffect>(ProfileState()) {
    private var observation: Job? = null
    private var acquisition: Job? = null
    private var visible = false

    init {
        on<ProfileEvent.Entered>(::onEntered)
        on<ProfileEvent.Stopped>(::onStopped)
        on<ProfileEvent.RefreshRequested>(::onRefreshRequested)
        on<ProfileEvent.EmploymentRequested>(::onEmploymentRequested)
        on<ProfileEvent.EmploymentDismissed>(::onEmploymentDismissed)
        on<ProfileEvent.EmploymentSelected>(::onEmploymentSelected)
        on<ProfileEvent.BackRequested>(::onBackRequested)
        on<ProfileEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: ProfileEvent.Entered) {
        if (visible) return
        visible = true
        if (observation?.isActive != true) observeSelection()
        acquire(force = false)
    }

    private fun observeSelection() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                observe(workspaceId, state.map { it.selectedId }.distinctUntilChanged()).collect {
                    result ->
                    ensureActive()
                    updateState { it.withProfileView(result) }
                }
            }
    }

    private fun onStopped(event: ProfileEvent.Stopped) {
        visible = false
        acquisition?.cancel()
        acquisition = null
        updateState { it.copy(loading = false, syncing = false, choosingEmployment = false) }
    }

    private fun onRefreshRequested(event: ProfileEvent.RefreshRequested) {
        acquire(force = true)
    }

    private fun acquire(force: Boolean) {
        if (!visible || acquisition?.isActive == true) return
        updateState { it.copy(syncing = true, loading = it.view == null) }
        acquisition =
            viewModelScope.launch {
                val result = sync(workspaceId, force)
                ensureActive()
                updateState { it.withProfileSync(result) }
                // A completed storage flow must be reopened after an admitted refresh.
                if (result is AppResult.Success && result.value.caughtUp) observeSelection()
            }
    }

    private fun onEmploymentRequested(event: ProfileEvent.EmploymentRequested) {
        if (
            !visible || state.value.readRestricted || (state.value.view?.employments?.size ?: 0) < 2
        )
            return
        updateState { it.copy(choosingEmployment = true) }
    }

    private fun onEmploymentDismissed(event: ProfileEvent.EmploymentDismissed) {
        updateState { it.copy(choosingEmployment = false) }
    }

    private fun onEmploymentSelected(event: ProfileEvent.EmploymentSelected) {
        if (
            !visible ||
                state.value.readRestricted ||
                state.value.view?.employments?.none { it.id == event.id } != false
        )
            return
        updateState { it.copy(selectedId = event.id, choosingEmployment = false) }
    }

    private fun onBackRequested(event: ProfileEvent.BackRequested) {
        viewModelScope.launch { emitEffect(ProfileEffect.Back) }
    }

    private fun onVerificationRequested(event: ProfileEvent.VerificationRequested) {
        if (visible && state.value.needsVerification)
            viewModelScope.launch { emitEffect(ProfileEffect.VerifyAccount) }
    }
}
