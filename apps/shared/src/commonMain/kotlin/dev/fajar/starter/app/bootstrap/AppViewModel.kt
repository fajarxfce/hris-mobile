package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.app.navigation.ResolveAppLink
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.usecases.ObserveUser
import dev.fajar.starter.identity.domain.usecases.RestoreSession
import dev.fajar.starter.observability.*
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AppViewModel(
    private val loadOnboarding: LoadOnboarding,
    private val observeUser: ObserveUser,
    private val restoreSession: RestoreSession,
    private val resolveAppLink: ResolveAppLink,
) : MviViewModel<AppState, AppEvent, Nothing>(AppState()) {
    private var bootstrapJob: Job? = null

    init {
        on<AppEvent.LinkReceived>(::onLinkReceived)
        on<AppEvent.LinkHandled>(::onLinkHandled)
        on<AppEvent.BootstrapRequested>(::onBootstrapRequested)
        onEvent(AppEvent.BootstrapRequested)
    }

    private fun onBootstrapRequested(event: AppEvent.BootstrapRequested) {
        bootstrapJob?.cancel()
        updateState { it.copy(stage = AppStage.Loading) }
        bootstrapJob =
            viewModelScope.launch {
                val initialized =
                    measureOperation(
                        PerformanceOperation.AppBootstrap,
                        classify = {
                            if (it is AppResult.Failed) PerformanceOutcome.Failed
                            else PerformanceOutcome.Succeeded
                        },
                    ) {
                        when (val restored = restoreSession()) {
                            is AppResult.Failed -> restored
                            is AppResult.Success -> loadOnboarding()
                        }
                    }
                when (val result = initialized) {
                    is AppResult.Failed ->
                        updateState { it.copy(stage = AppStage.Failed(result.failure)) }
                    is AppResult.Success -> {
                        if (!result.value) {
                            updateState { it.copy(stage = AppStage.Onboarding) }
                        } else {
                            observeUser().collect { user ->
                                updateState {
                                    it.copy(
                                        stage =
                                            when (user) {
                                                is AppResult.Failed -> AppStage.Failed(user.failure)
                                                is AppResult.Success ->
                                                    if (user.value == null) AppStage.SignedOut
                                                    else AppStage.SignedIn
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun onLinkReceived(event: AppEvent.LinkReceived) {
        val link = resolveAppLink(event.uri) ?: return
        updateState { it.copy(pendingLink = link) }
    }

    private fun onLinkHandled(event: AppEvent.LinkHandled) {
        updateState { if (it.pendingLink == event.link) it.copy(pendingLink = null) else it }
    }
}
