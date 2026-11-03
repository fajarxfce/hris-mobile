package dev.fajar.starter.collaboration.presentation.setup

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ChatSetupViewModel(
    @InjectedParam private val workspaceId: String,
    private val observeAccess: ObserveCollaborationAccess,
    private val readControl: ReadCollaborationControl,
    private val loadSetup: LoadCollaborationSetup,
    private val prepare: PrepareCollaborationSetup,
    private val replay: ReplayCollaborationSetup,
    private val reconcile: ReconcileCollaborationSetupReceipt,
    private val install: InstallCollaborationRuntime,
    private val ensureRuntime: EnsureCollaborationRuntime,
    private val build: AppBuild,
    private val requestRecovery: RequestCollaborationRecovery,
) : MviViewModel<ChatSetupState, ChatSetupEvent, ChatSetupEffect>(ChatSetupState()) {
    private var observation: Job? = null
    private var work: Job? = null
    private var visible = false
    private var accessObserved = false

    init {
        on<ChatSetupEvent.Entered>(::onEntered)
        on<ChatSetupEvent.Stopped>(::onStopped)
        on<ChatSetupEvent.BackRequested>(::onBackRequested)
        on<ChatSetupEvent.RefreshRequested>(::onRefreshRequested)
        on<ChatSetupEvent.ContinueRequested>(::onContinueRequested)
        on<ChatSetupEvent.OpenChatRequested>(::onOpenChatRequested)
    }

    private fun onEntered(event: ChatSetupEvent.Entered) {
        if (visible) return
        visible = true
        if (observation?.isActive != true) observe(acquireOnFirst = true)
        else if (accessObserved) refresh()
    }

    private fun observe(acquireOnFirst: Boolean) {
        observation?.cancel()
        accessObserved = false
        observation =
            viewModelScope.launch {
                var first = true
                observeAccess(workspaceId).collect { result ->
                    ensureActive()
                    accessObserved = true
                    when (result) {
                        is AppResult.Failed -> {
                            work?.cancel()
                            work = null
                            updateState {
                                ChatSetupState(
                                    phase = ChatSetupPhase.UNAVAILABLE,
                                    loading = false,
                                    localFailure = result.failure,
                                )
                            }
                        }
                        is AppResult.Success -> updateState { it.copy(localFailure = null) }
                    }
                    if (first) {
                        first = false
                        if (visible && acquireOnFirst) refresh()
                    }
                }
            }
    }

    private fun onStopped(event: ChatSetupEvent.Stopped) {
        visible = false
        work?.cancel()
        work = null
        updateState { it.copy(loading = false, working = false) }
    }

    private fun onBackRequested(event: ChatSetupEvent.BackRequested) {
        if (!visible) return
        onStopped(ChatSetupEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatSetupEffect.Back) }
    }

    private fun onRefreshRequested(event: ChatSetupEvent.RefreshRequested) {
        if (!visible || work?.isActive == true) return
        refresh()
    }

    private fun refresh() {
        if (!visible) return
        work?.cancel()
        updateState {
            it.copy(loading = true, working = false, failure = null, readyDeviceId = null)
        }
        work =
            viewModelScope.launch {
                val local = readControl.setup(workspaceId)
                ensureActive()
                if (local is AppResult.Success) showRetained(local.value.value)
                acquire()
            }
    }

    private suspend fun acquire() {
        val loaded = loadSetup(workspaceId)
        currentCoroutineContext().ensureActive()
        if (loaded is AppResult.Failed) return failed(loaded.failure)
        val local = readControl.setup(workspaceId)
        currentCoroutineContext().ensureActive()
        if (local is AppResult.Failed) return failed(local.failure)
        val device = (loaded as AppResult.Success).value.device
        val record = (local as AppResult.Success).value.value
        updateState {
            it.copy(device = device, localFailure = null, failure = null, readyDeviceId = null)
        }
        if (record != null) {
            showRetained(record)
            updateState { it.copy(loading = false, working = false) }
        } else {
            val phase =
                when (device?.state) {
                    null -> ChatSetupPhase.REGISTER
                    CollaborationDeviceState.REVOKED -> ChatSetupPhase.REVOKED
                    CollaborationDeviceState.PROVISIONING ->
                        if (
                            device.provisioning?.state in
                                setOf(
                                    CollaborationProvisioningState.FAILED,
                                    CollaborationProvisioningState.CANCELLED,
                                )
                        )
                            ChatSetupPhase.RETRY_PROVISIONING
                        else ChatSetupPhase.WAITING
                    CollaborationDeviceState.ACTIVE -> ChatSetupPhase.BOOTSTRAP
                }
            updateState { it.copy(phase = phase, retainedOperation = null) }
            if (phase == ChatSetupPhase.BOOTSTRAP) {
                val restored = ensureRuntime(workspaceId, requireNotNull(device).id)
                currentCoroutineContext().ensureActive()
                if (restored is AppResult.Failed) return failed(restored.failure)
                if ((restored as AppResult.Success).value.value) ready(device.id)
            }
            updateState { it.copy(loading = false, working = false) }
        }
        observe(acquireOnFirst = false)
    }

    private fun showRetained(record: CollaborationSetupCommandRecord?) {
        if (record == null) return
        val operation =
            when (record.command.action) {
                is CollaborationSetupAction.Register -> ChatSetupOperation.REGISTER
                is CollaborationSetupAction.Retry -> ChatSetupOperation.RETRY_PROVISIONING
                is CollaborationSetupAction.Bootstrap -> ChatSetupOperation.BOOTSTRAP
            }
        updateState {
            it.copy(
                phase = ChatSetupPhase.RESUME,
                retainedOperation = operation,
                readyDeviceId = null,
            )
        }
    }

    private fun onContinueRequested(event: ChatSetupEvent.ContinueRequested) {
        val current = state.value
        if (
            !visible ||
                !accessObserved ||
                current.localFailure != null ||
                current.loading ||
                current.working ||
                work?.isActive == true
        )
            return
        if (
            current.phase !in
                setOf(
                    ChatSetupPhase.REGISTER,
                    ChatSetupPhase.RETRY_PROVISIONING,
                    ChatSetupPhase.BOOTSTRAP,
                    ChatSetupPhase.RESUME,
                )
        )
            return
        updateState { it.copy(working = true, failure = null) }
        work =
            viewModelScope.launch {
                val local = readControl.setup(workspaceId)
                ensureActive()
                if (local is AppResult.Failed) return@launch failed(local.failure)
                var record = (local as AppResult.Success).value.value
                if (record == null) {
                    // A stale RESUME never guesses which new action should replace a retired
                    // command.
                    if (current.phase == ChatSetupPhase.RESUME) return@launch acquire()
                    val device = current.device
                    val action =
                        when (current.phase) {
                            ChatSetupPhase.REGISTER ->
                                CollaborationSetupAction.Register("HRIS ${build.platform.name}")
                            ChatSetupPhase.RETRY_PROVISIONING -> {
                                val provisioning =
                                    device?.provisioning ?: return@launch failed(changed())
                                CollaborationSetupAction.Retry(
                                    device.id,
                                    device.version,
                                    provisioning.version,
                                )
                            }
                            ChatSetupPhase.BOOTSTRAP -> {
                                if (device == null) return@launch failed(changed())
                                CollaborationSetupAction.Bootstrap(
                                    device.id,
                                    device.version,
                                    device.gateway?.version,
                                )
                            }
                            else -> return@launch failed(changed())
                        }
                    val prepared = prepare(workspaceId, action)
                    ensureActive()
                    if (prepared is AppResult.Failed) return@launch failed(prepared.failure)
                    record = (prepared as AppResult.Success).value
                }
                showRetained(record)
                if (
                    record.outcome == CollaborationSetupOutcome.Pending ||
                        record.outcome is CollaborationSetupOutcome.Unconfirmed
                ) {
                    val result = replay(workspaceId, record.command.id)
                    ensureActive()
                    if (result is AppResult.Failed) return@launch failed(result.failure)
                    record = (result as AppResult.Success).value
                }
                when (record.outcome) {
                    is CollaborationSetupOutcome.Bootstrapped -> {
                        val activated = install.activate(workspaceId, record.command.id)
                        ensureActive()
                        if (activated is AppResult.Failed) return@launch failed(activated.failure)
                        ready((activated as AppResult.Success).value.value)
                        observe(acquireOnFirst = false)
                    }
                    is CollaborationSetupOutcome.Registered,
                    is CollaborationSetupOutcome.Retried -> {
                        val completed = reconcile(workspaceId, record.command.id)
                        ensureActive()
                        if (completed is AppResult.Failed) return@launch failed(completed.failure)
                        // False leaves the receipt in storage; a read shows its recoverable phase.
                        acquire()
                    }
                    else -> failed(changed())
                }
            }
    }

    private suspend fun ready(deviceId: String) {
        updateState {
            it.copy(
                phase = ChatSetupPhase.READY,
                readyDeviceId = deviceId,
                retainedOperation = null,
                loading = false,
                working = false,
                failure = null,
            )
        }
        // Persist a wake after installation/restoration; provider activation runs in the app
        // worker.
        withTimeoutOrNull(1_500) { requestRecovery() }
    }

    private fun onOpenChatRequested(event: ChatSetupEvent.OpenChatRequested) {
        val current = state.value
        if (
            !visible ||
                current.localFailure != null ||
                current.loading ||
                current.working ||
                current.phase != ChatSetupPhase.READY
        )
            return
        val id = current.readyDeviceId ?: return
        onStopped(ChatSetupEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatSetupEffect.Ready(id)) }
    }

    private fun failed(failure: Failure) {
        updateState {
            it.copy(
                phase =
                    if (it.phase == ChatSetupPhase.READY) ChatSetupPhase.UNAVAILABLE else it.phase,
                loading = false,
                working = false,
                readyDeviceId = null,
                failure = failure,
            )
        }
    }

    private fun changed() =
        Failure(
            FailureKind.Conflict,
            "Refresh chat setup and try again.",
            code = "client.collaboration_setup_changed",
        )
}
