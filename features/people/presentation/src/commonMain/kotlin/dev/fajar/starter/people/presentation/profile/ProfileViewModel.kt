package dev.fajar.starter.people.presentation.profile

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.usecases.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ProfileViewModel(
    @InjectedParam private val workspaceId: String,
    private val loadDirectory: LoadSelfEmployments,
    private val loadProfile: LoadEmploymentProfile,
) : MviViewModel<ProfileState, ProfileEvent, ProfileEffect>(ProfileState()) {
    private var profileRequest: Job? = null

    init {
        on<ProfileEvent.DirectoryRequested>(::onDirectoryRequested)
        on<ProfileEvent.EmploymentSelected>(::onEmploymentSelected)
        on<ProfileEvent.BackRequested>(::onBackRequested)
        on<ProfileEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onDirectoryRequested(event: ProfileEvent.DirectoryRequested) {
        if (state.value.loadingDirectory) return
        if (event.after != null && event.after != state.value.directory?.nextCursor) return
        val previousSelection = state.value.selectedId
        profileRequest?.cancel()
        updateState { ProfileState(loadingDirectory = true) }
        viewModelScope.launch {
            when (val result = loadDirectory(workspaceId, event.after)) {
                is AppResult.Failed ->
                    updateState {
                        it.copy(
                            loadingDirectory = false,
                            failure = result.failure,
                            needsVerification = result.failure.code in profileVerificationCodes,
                        )
                    }
                is AppResult.Success -> {
                    updateState { it.copy(directory = result.value, loadingDirectory = false) }
                    val selected =
                        result.value.items.firstOrNull { it.id == previousSelection }
                            ?: result.value.items.singleOrNull()
                    selected?.let { onEvent(ProfileEvent.EmploymentSelected(it.id)) }
                }
            }
        }
    }

    private fun onEmploymentSelected(event: ProfileEvent.EmploymentSelected) {
        val directory = state.value.directory ?: return
        if (state.value.loadingDirectory || directory.items.none { it.id == event.id }) return
        profileRequest?.cancel()
        updateState {
            it.copy(
                selectedId = event.id,
                profile = null,
                loadingProfile = true,
                failure = null,
                needsVerification = false,
            )
        }
        profileRequest =
            viewModelScope.launch {
                val result = loadProfile(workspaceId, event.id, directory.asOf)
                ensureActive()
                when (result) {
                    is AppResult.Failed ->
                        updateState {
                            it.copy(
                                profile = null,
                                loadingProfile = false,
                                failure = result.failure,
                                needsVerification = result.failure.code in profileVerificationCodes,
                            )
                        }
                    is AppResult.Success ->
                        updateState { it.copy(profile = result.value, loadingProfile = false) }
                }
            }
    }

    private fun onBackRequested(event: ProfileEvent.BackRequested) {
        viewModelScope.launch { emitEffect(ProfileEffect.Back) }
    }

    private fun onVerificationRequested(event: ProfileEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(ProfileEffect.VerifyAccount) }
    }
}

private val profileVerificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
