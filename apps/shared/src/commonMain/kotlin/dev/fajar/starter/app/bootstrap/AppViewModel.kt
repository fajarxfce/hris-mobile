package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.app.navigation.ResolveAppLink
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.identity.domain.usecases.RestoreSession
import dev.fajar.starter.observability.*
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspace
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AppViewModel(
    private val loadOnboarding: LoadOnboarding,
    private val observeIdentity: ObserveSessionIdentity,
    private val restoreSession: RestoreSession,
    private val resolveAppLink: ResolveAppLink,
    private val observeWorkspace: ObserveWorkspace,
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
                            combine(observeIdentity(), observeWorkspace()) { identity, workspace ->
                                    identity to workspace
                                }
                                .collect { (identity, workspace) ->
                                    updateState {
                                        it.copy(
                                            stage =
                                                when (identity) {
                                                    is AppResult.Failed ->
                                                        AppStage.Failed(identity.failure)
                                                    is AppResult.Success ->
                                                        identity.value?.let {
                                                            when (workspace) {
                                                                is AppResult.Failed ->
                                                                    AppStage.Failed(
                                                                        workspace.failure
                                                                    )
                                                                is AppResult.Success ->
                                                                    AppStage.SignedIn(
                                                                        it.sessionId,
                                                                        workspace.value?.takeIf {
                                                                            selection ->
                                                                            selection.sessionId ==
                                                                                it.sessionId
                                                                        },
                                                                    )
                                                            }
                                                        } ?: AppStage.SignedOut
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
