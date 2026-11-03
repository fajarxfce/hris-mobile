package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.app.navigation.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.usecases.RestoreSession
import dev.fajar.starter.observability.*
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.usecases.LeaveWorkspace
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspaceContext
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AppViewModel(
    private val loadOnboarding: LoadOnboarding,
    private val observeContext: ObserveWorkspaceContext,
    private val restoreSession: RestoreSession,
    private val resolveAppLink: ResolveAppLink,
    private val refreshWorkspace: RefreshWorkspace,
    private val leaveWorkspace: LeaveWorkspace,
    private val nativeNavigation: NativeNotificationNavigation,
) : MviViewModel<AppState, AppEvent, Nothing>(AppState()) {
    private var bootstrapJob: Job? = null
    private var accessRefresh: Job? = null
    private var foreground = false
    private var linkSequence = 0L
    private val linkRequests = MutableStateFlow<AppLinkRequest?>(null)
    private var activeNativeOwner: Pair<Long, WorkspaceSelectionOwner>? = null
    private var nativeLinkJob: Job? = null

    init {
        on<AppEvent.LinkReceived>(::onLinkReceived)
        on<AppEvent.LinkHandled>(::onLinkHandled)
        on<AppEvent.NativeLinkReceived>(::onNativeLinkReceived)
        on<AppEvent.NativeLinkRetry>(::onNativeLinkRetry)
        on<AppEvent.NativeLinkDismiss>(::onNativeLinkDismiss)
        on<AppEvent.BootstrapRequested>(::onBootstrapRequested)
        on<AppEvent.ForegroundEntered>(::onForegroundEntered)
        on<AppEvent.ForegroundLeft>(::onForegroundLeft)
        on<AppEvent.WorkspaceRefreshRequested>(::onWorkspaceRefreshRequested)
        on<AppEvent.CompanyChangeRequested>(::onCompanyChangeRequested)
        viewModelScope.launch {
            linkRequests.collectLatest { request ->
                activeNativeOwner = null
                when (request) {
                    is AppLinkRequest.External -> openExternalLink(request)
                    is AppLinkRequest.Notification -> openNativeLink(request)
                    null -> Unit
                }
            }
        }
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
                            observeContext().collect(::onContext)
                        }
                    }
                }
            }
    }

    private fun onContext(context: AppResult<WorkspaceContext?>) {
        val admitted = (context as? AppResult.Success)?.value
        val nextStage =
            when {
                context is AppResult.Failed -> AppStage.Failed(context.failure)
                admitted == null -> AppStage.SignedOut
                else -> AppStage.SignedIn(admitted.sessionId, admitted.workspace, admitted.owner)
            }
        val previous = state.value.stage as? AppStage.SignedIn
        val next = nextStage as? AppStage.SignedIn
        val changed =
            previous?.sessionId != next?.sessionId || previous?.workspace?.id != next?.workspace?.id
        val expired = admitted?.policy?.stale == true && state.value.companyPolicy?.stale != true
        if (changed) {
            accessRefresh?.cancel()
            accessRefresh = null
        }
        val request = linkRequests.value as? AppLinkRequest.Notification
        val bound =
            request?.owner ?: activeNativeOwner?.takeIf { it.first == request?.sequence }?.second
        val replaced = context is AppResult.Success && bound != null && bound != next?.owner
        if (replaced) linkRequests.value = null
        updateState {
            it.copy(
                stage = nextStage,
                pendingLink =
                    if (context is AppResult.Failed) it.pendingLink
                    else it.pendingLink?.inContext(next?.sessionId, next?.workspace, next?.owner),
                accessFailure = if (changed) null else it.accessFailure,
                companyPolicy = admitted?.policy,
                refreshingWorkspace = if (changed) false else it.refreshingWorkspace,
                changingWorkspace = if (changed) false else it.changingWorkspace,
                workspaceRefreshFailure = if (changed) null else it.workspaceRefreshFailure,
                openingNotification = if (replaced) false else it.openingNotification,
                notificationOpenFailure = if (replaced) null else it.notificationOpenFailure,
            )
        }
        if ((changed || expired) && foreground) onEvent(AppEvent.WorkspaceRefreshRequested)
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
        val scope = origin.workspace?.id ?: return
        updateState { it.copy(refreshingWorkspace = true, workspaceRefreshFailure = null) }
        val refresh =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = refreshWorkspace()
                    ensureActive()
                    val current = state.value.stage as? AppStage.SignedIn
                    if (current?.sessionId != origin.sessionId || current.workspace?.id != scope)
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
        val scope = origin.workspace?.id ?: return
        val cancelledLink = cancelNativeLinkForCompanyChange()
        accessRefresh?.cancel()
        accessRefresh = null
        updateState { it.copy(changingWorkspace = true, refreshingWorkspace = false) }
        viewModelScope.launch {
            cancelledLink?.join()
            ensureActive()
            val admitted = state.value.stage as? AppStage.SignedIn
            if (admitted?.owner != origin.owner || admitted?.workspace?.id != scope) return@launch
            val result = leaveWorkspace(scope)
            ensureActive()
            val current = state.value.stage as? AppStage.SignedIn
            if (current?.sessionId != origin.sessionId || current.workspace?.id != scope)
                return@launch
            updateState {
                it.copy(
                    changingWorkspace = false,
                    workspaceRefreshFailure = (result as? AppResult.Failed)?.failure,
                )
            }
        }
    }

    private fun cancelNativeLinkForCompanyChange(): Job? {
        // Keep a failed input available for explicit retry after the verification flow.
        if (
            linkRequests.value !is AppLinkRequest.Notification ||
                state.value.notificationOpenFailure != null
        )
            return null
        val previous = nativeLinkJob
        linkRequests.value = null
        updateState {
            it.copy(pendingLink = null, openingNotification = false, notificationOpenFailure = null)
        }
        return previous
    }

    private fun onLinkReceived(event: AppEvent.LinkReceived) {
        val target = resolveAppLink(event.uri) ?: return
        requestLink(AppLinkRequest.External(++linkSequence, target))
    }

    private fun onNativeLinkReceived(event: AppEvent.NativeLinkReceived) {
        val owner = (state.value.stage as? AppStage.SignedIn)?.owner
        requestLink(AppLinkRequest.Notification(++linkSequence, event.input, owner))
    }

    private fun onNativeLinkRetry(event: AppEvent.NativeLinkRetry) {
        val request = linkRequests.value as? AppLinkRequest.Notification ?: return
        if (state.value.openingNotification || state.value.changingWorkspace) return
        val owner =
            request.owner ?: activeNativeOwner?.takeIf { it.first == request.sequence }?.second
        requestLink(request.copy(sequence = ++linkSequence, owner = owner))
    }

    private fun onNativeLinkDismiss(event: AppEvent.NativeLinkDismiss) {
        linkRequests.value = null
        updateState { it.copy(openingNotification = false, notificationOpenFailure = null) }
    }

    private fun requestLink(request: AppLinkRequest) {
        updateState {
            it.copy(pendingLink = null, openingNotification = false, notificationOpenFailure = null)
        }
        // collectLatest cancels AND joins the previous request before entering this one.
        linkRequests.value = request
    }

    private fun openExternalLink(request: AppLinkRequest.External) {
        val stage = state.value.stage as? AppStage.SignedIn
        val pending =
            PendingAppLink(request.target)
                .inContext(stage?.sessionId, stage?.workspace, stage?.owner)
        if (linkRequests.value == request) updateState { it.copy(pendingLink = pending) }
    }

    private suspend fun openNativeLink(request: AppLinkRequest.Notification) {
        val currentJob = currentCoroutineContext()[Job]
        nativeLinkJob = currentJob
        try {
            // One bounded input may wait for bootstrap/login without holding a native callback.
            val ready =
                state.first {
                    (it.stage as? AppStage.SignedIn)?.owner != null && it.accessFailure == null
                }
            val stage = ready.stage as AppStage.SignedIn
            val owner = requireNotNull(stage.owner)
            if (request.owner != null && request.owner != owner) return
            activeNativeOwner = request.sequence to owner
            updateState { it.copy(openingNotification = true, notificationOpenFailure = null) }
            val origin = WorkspaceSelectionOrigin(owner, stage.workspace?.id)
            val result =
                withTimeoutOrNull(20_000) {
                    val opened = nativeNavigation(request.input, origin, stage.workspace)
                    currentCoroutineContext().ensureActive()
                    if (opened is AppResult.Failed) return@withTimeoutOrNull opened
                    awaitNativeContext((opened as AppResult.Success).value, origin)
                } ?: AppResult.Failed(nativeOpenFailure(FailureKind.Timeout))
            currentCoroutineContext().ensureActive()
            if (linkRequests.value != request) return
            when (result) {
                is AppResult.Success ->
                    updateState {
                        it.copy(pendingLink = result.value, notificationOpenFailure = null)
                    }
                is AppResult.Failed ->
                    updateState { it.copy(notificationOpenFailure = result.failure) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (linkRequests.value == request)
                updateState {
                    it.copy(notificationOpenFailure = nativeOpenFailure(FailureKind.Unexpected))
                }
        } finally {
            if (nativeLinkJob === currentJob) nativeLinkJob = null
            if (linkRequests.value == request) updateState { it.copy(openingNotification = false) }
        }
    }

    private suspend fun awaitNativeContext(
        link: PendingAppLink,
        origin: WorkspaceSelectionOrigin,
    ): AppResult<PendingAppLink> {
        val observed =
            state.first {
                val stage = it.stage as? AppStage.SignedIn
                it.accessFailure != null ||
                    stage == null ||
                    stage.owner != origin.owner ||
                    stage.workspace?.id == link.workspaceId ||
                    stage.workspace?.id != origin.workspaceId
            }
        val stage = observed.stage as? AppStage.SignedIn
        val current = link.inContext(stage?.sessionId, stage?.workspace, stage?.owner)
        return if (observed.accessFailure == null && current == link) AppResult.Success(link)
        else AppResult.Failed(nativeOpenFailure(FailureKind.Conflict))
    }

    private fun nativeOpenFailure(kind: FailureKind) =
        Failure(kind, "This chat cannot be opened.", code = "client.notification_open_unavailable")

    private fun onLinkHandled(event: AppEvent.LinkHandled) {
        if (state.value.pendingLink != event.link) return
        linkRequests.value = null
        updateState { it.copy(pendingLink = null) }
    }
}
