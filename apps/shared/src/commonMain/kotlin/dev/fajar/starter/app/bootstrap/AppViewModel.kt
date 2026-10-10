package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.usecases.ObserveUser
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AppViewModel(
    private val loadOnboarding: LoadOnboarding,
    private val observeUser: ObserveUser,
) : MviViewModel<AppStage, AppEvent, Nothing>(AppStage.Loading) {
    private var bootstrapJob: Job? = null

    init {
        on<AppEvent.BootstrapRequested>(::onBootstrapRequested)
        onEvent(AppEvent.BootstrapRequested)
    }

    private fun onBootstrapRequested(event: AppEvent.BootstrapRequested) {
        bootstrapJob?.cancel()
        updateState { AppStage.Loading }
        bootstrapJob =
            viewModelScope.launch {
                when (val result = loadOnboarding()) {
                    is AppResult.Failed -> updateState { AppStage.Failed(result.failure.message) }
                    is AppResult.Success -> {
                        if (!result.value) {
                            updateState { AppStage.Onboarding }
                        } else {
                            observeUser().collect { user ->
                                updateState {
                                    if (user == null) AppStage.SignedOut else AppStage.SignedIn
                                }
                            }
                        }
                    }
                }
            }
    }
}
