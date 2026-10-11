package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.app.navigation.ResolveAppLink
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.identity.domain.usecases.RestoreSession
import dev.fajar.starter.observability.*
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspace
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    private val refreshWorkspace: RefreshWorkspace,
) : MviViewModel<AppState, AppEvent, Nothing>(AppState()) {
    private var bootstrapJob: Job? = null
    private var accessRefresh: Job? = null
    private var foreground = false

    init {
        on<AppEvent.LinkReceived>(::onLinkReceived)
        on<AppEvent.LinkHandled>(::onLinkHandled)
        on<AppEvent.BootstrapRequested>(::onBootstrapRequested)
        on<AppEvent.ForegroundEntered>(::onForegroundEntered)
        on<AppEvent.ForegroundLeft>(::onForegroundLeft)
        on<AppEvent.WorkspaceRefreshRequested>(::onWorkspaceRefreshRequested)
        onEvent(AppEvent.BootstrapRequested)
    }

    private fun onBootstrapRequested(event: AppEvent.BootstrapRequested) {
        if (state.value.accessFailure != null) {
            onEvent(AppEvent.WorkspaceRefreshRequested)
            return
        }
        bootstrapJob?.cancel()
        accessRefresh?.cancel()
        accessRefresh = null
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
                                    val currentIdentity = (identity as? AppResult.Success)?.value
                                    val nextStage =
                                        when {
                                            identity is AppResult.Failed ->
                                                AppStage.Failed(identity.failure)
                                            currentIdentity == null -> AppStage.SignedOut
                                            workspace is AppResult.Failed ->
                                                AppStage.Failed(workspace.failure)
                                            else ->
                                                AppStage.SignedIn(
                                                    currentIdentity.sessionId,
                                                    (workspace as AppResult.Success).value?.takeIf {
                                                        it.sessionId == currentIdentity.sessionId
                                                    },
                                                )
                                        }
                                    val previous = state.value.stage as? AppStage.SignedIn
                                    val next = nextStage as? AppStage.SignedIn
                                    val changed =
                                        previous?.sessionId != next?.sessionId ||
                                            previous?.workspace?.id != next?.workspace?.id
                                    if (changed) {
                                        accessRefresh?.cancel()
                                        accessRefresh = null
                                    }
                                    updateState {
                                        it.copy(
                                            stage = nextStage,
                                            accessFailure = if (changed) null else it.accessFailure,
                                        )
                                    }
                                    if (changed && foreground)
                                        onEvent(AppEvent.WorkspaceRefreshRequested)
                                }
                        }
                    }
                }
            }
    }

    private fun onForegroundEntered(event: AppEvent.ForegroundEntered) {
        foreground = true
        onEvent(AppEvent.WorkspaceRefreshRequested)
    }

    private fun onForegroundLeft(event: AppEvent.ForegroundLeft) {
        foreground = false
        accessRefresh?.cancel()
        accessRefresh = null
    }

    private fun onWorkspaceRefreshRequested(event: AppEvent.WorkspaceRefreshRequested) {
        if (!foreground || accessRefresh?.isActive == true) return
        val origin = state.value.stage as? AppStage.SignedIn ?: return
        val scope = origin.workspace?.id ?: return
        val refresh =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val result = refreshWorkspace()
                ensureActive()
                val current = state.value.stage as? AppStage.SignedIn
                if (current?.sessionId != origin.sessionId || current.workspace?.id != scope)
                    return@launch
                when (result) {
                    SyncResult.Complete -> updateState { it.copy(accessFailure = null) }
                    is SyncResult.Blocked ->
                        if (
                            result.failure.kind in
                                setOf(FailureKind.Storage, FailureKind.Unexpected)
                        )
                            updateState { it.copy(accessFailure = result.failure) }
                    is SyncResult.Retry -> Unit
                }
            }
        accessRefresh = refresh
        refresh.start()
    }

    private fun onLinkReceived(event: AppEvent.LinkReceived) {
        val link = resolveAppLink(event.uri) ?: return
        updateState { it.copy(pendingLink = link) }
    }

    private fun onLinkHandled(event: AppEvent.LinkHandled) {
        updateState { if (it.pendingLink == event.link) it.copy(pendingLink = null) else it }
    }
}
