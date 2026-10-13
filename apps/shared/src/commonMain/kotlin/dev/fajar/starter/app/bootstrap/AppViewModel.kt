package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.app.navigation.ResolveAppLink
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.usecases.RestoreSession
import dev.fajar.starter.observability.*
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.LeaveWorkspace
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspaceContext
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AppViewModel(
    private val loadOnboarding: LoadOnboarding,
    private val observeContext: ObserveWorkspaceContext,
    private val restoreSession: RestoreSession,
    private val resolveAppLink: ResolveAppLink,
    private val refreshWorkspace: RefreshWorkspace,
    private val leaveWorkspace: LeaveWorkspace,
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
        on<AppEvent.CompanyChangeRequested>(::onCompanyChangeRequested)
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
        updateState {
            it.copy(
                stage = AppStage.Loading,
                companyPolicy = null,
                refreshingWorkspace = false,
                changingWorkspace = false,
                workspaceRefreshFailure = null,
            )
        }
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
                            observeContext().collect { context ->
                                val admitted = (context as? AppResult.Success)?.value
                                val nextStage =
                                    when {
                                        context is AppResult.Failed ->
                                            AppStage.Failed(context.failure)
                                        admitted == null -> AppStage.SignedOut
                                        else ->
                                            AppStage.SignedIn(
                                                admitted.sessionId,
                                                admitted.workspace,
                                                admitted.expiredWorkspaceId,
                                            )
                                    }
                                val previous = state.value.stage as? AppStage.SignedIn
                                val next = nextStage as? AppStage.SignedIn
                                val changed =
                                    previous?.sessionId != next?.sessionId ||
                                        previous?.selectedWorkspaceId != next?.selectedWorkspaceId
                                val expired =
                                    admitted?.policy?.stale == true &&
                                        state.value.companyPolicy?.stale != true
                                val renewalRequired =
                                    next?.expiredWorkspaceId != null &&
                                        previous?.expiredWorkspaceId == null
                                if (changed) {
                                    accessRefresh?.cancel()
                                    accessRefresh = null
                                }
                                updateState {
                                    it.copy(
                                        stage = nextStage,
                                        accessFailure = if (changed) null else it.accessFailure,
                                        companyPolicy = admitted?.policy,
                                        refreshingWorkspace =
                                            if (changed) false else it.refreshingWorkspace,
                                        changingWorkspace =
                                            if (changed) false else it.changingWorkspace,
                                        workspaceRefreshFailure =
                                            if (changed) null else it.workspaceRefreshFailure,
                                    )
                                }
                                if ((changed || expired || renewalRequired) && foreground)
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
        updateState { it.copy(refreshingWorkspace = false) }
    }

    private fun onWorkspaceRefreshRequested(event: AppEvent.WorkspaceRefreshRequested) {
        if (!foreground || accessRefresh?.isActive == true || state.value.changingWorkspace) return
        val origin = state.value.stage as? AppStage.SignedIn ?: return
        val scope = origin.selectedWorkspaceId ?: return
        updateState { it.copy(refreshingWorkspace = true, workspaceRefreshFailure = null) }
        val refresh =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = refreshWorkspace()
                    ensureActive()
                    val current = state.value.stage as? AppStage.SignedIn
                    if (
                        current?.sessionId != origin.sessionId ||
                            current.selectedWorkspaceId != scope
                    )
                        return@launch
                    when (result) {
                        SyncResult.Complete ->
                            updateState {
                                it.copy(accessFailure = null, workspaceRefreshFailure = null)
                            }
                        is SyncResult.Blocked ->
                            updateState {
                                it.copy(
                                    workspaceRefreshFailure = result.failure,
                                    accessFailure =
                                        if (
                                            result.failure.kind in
                                                setOf(FailureKind.Storage, FailureKind.Unexpected)
                                        )
                                            result.failure
                                        else it.accessFailure,
                                )
                            }
                        is SyncResult.Retry ->
                            updateState { it.copy(workspaceRefreshFailure = result.failure) }
                    }
                } finally {
                    if (accessRefresh == coroutineContext[Job])
                        updateState { it.copy(refreshingWorkspace = false) }
                }
            }
        accessRefresh = refresh
        refresh.start()
    }

    private fun onCompanyChangeRequested(event: AppEvent.CompanyChangeRequested) {
        if (state.value.changingWorkspace) return
        val origin = state.value.stage as? AppStage.SignedIn ?: return
        val scope = origin.selectedWorkspaceId ?: return
        accessRefresh?.cancel()
        accessRefresh = null
        updateState { it.copy(changingWorkspace = true, refreshingWorkspace = false) }
        viewModelScope.launch {
            val result = leaveWorkspace(scope)
            ensureActive()
            val current = state.value.stage as? AppStage.SignedIn
            if (current?.sessionId != origin.sessionId || current.selectedWorkspaceId != scope)
                return@launch
            updateState {
                it.copy(
                    changingWorkspace = false,
                    workspaceRefreshFailure = (result as? AppResult.Failed)?.failure,
                )
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
