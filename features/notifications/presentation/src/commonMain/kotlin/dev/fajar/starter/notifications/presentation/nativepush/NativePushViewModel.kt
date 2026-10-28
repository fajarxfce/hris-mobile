package dev.fajar.starter.notifications.presentation.nativepush

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.NativePushAction
import dev.fajar.starter.identity.domain.push.usecases.*
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.notifications.domain.usecases.EnableNotifications
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import org.koin.android.annotation.KoinViewModel

/** The application host owns this coordinator and its Settings projection. */
@KoinViewModel
class NativePushViewModel(
    private val observeIdentity: ObserveSessionIdentity,
    private val observeStatus: ObserveNativePushStatus,
    private val observeTokens: ObserveNativePushTokenChanges,
    private val reconcile: ReconcileNativePush,
    private val enable: EnableNotifications,
) : MviViewModel<NativePushState, NativePushEvent, Unit>(NativePushState()) {
    private var active = false
    private var known = false
    private var sessionId: String? = null
    private var generation = 0L
    private var identityJob: Job? = null
    private var statusJob: Job? = null
    private var tokenJob: Job? = null
    private var mutation: Job? = null

    init {
        on<NativePushEvent.Started>(::onStarted)
        on<NativePushEvent.Stopped>(::onStopped)
        on<NativePushEvent.EnableRequested>(::onEnableRequested)
        on<NativePushEvent.DisableRequested>(::onDisableRequested)
        on<NativePushEvent.RetryRequested>(::onRetryRequested)
    }

    private fun onStarted(event: NativePushEvent.Started) {
        if (active) return
        active = true
        identityJob =
            viewModelScope.launch {
                observeIdentity().collect { result ->
                    currentCoroutineContext().ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            cancelScope()
                            known = false
                            updateState { NativePushState(failure = result.failure) }
                        }
                        is AppResult.Success -> {
                            val id = result.value?.sessionId
                            if (!known || sessionId != id) enterSession(id)
                        }
                    }
                }
            }
    }

    private fun enterSession(id: String?) {
        cancelScope()
        val retained = state.value.status.takeIf { sessionId == id }
        sessionId = id
        known = true
        updateState { NativePushState(sessionId = id, status = retained) }
        mutate(NativePushAction.Refresh)
    }

    private fun watchStatus(id: String?, owner: Long) {
        statusJob?.cancel()
        statusJob =
            viewModelScope.launch {
                observeStatus(id).collect { result ->
                    currentCoroutineContext().ensureActive()
                    if (!owns(owner)) return@collect
                    when (result) {
                        is AppResult.Success -> updateState { it.copy(status = result.value) }
                        is AppResult.Failed ->
                            updateState {
                                it.copy(
                                    failure = result.failure,
                                    status =
                                        it.status.takeUnless {
                                            result.failure.code == "client.session_changed"
                                        },
                                )
                            }
                    }
                }
            }
    }

    private fun onStopped(event: NativePushEvent.Stopped) {
        active = false
        known = false
        identityJob?.cancel()
        identityJob = null
        cancelScope()
        updateState { it.copy(busy = false) }
    }

    private fun onEnableRequested(event: NativePushEvent.EnableRequested) {
        if (canAct()) mutate(NativePushAction.Enable)
    }

    private fun onDisableRequested(event: NativePushEvent.DisableRequested) {
        if (canAct()) mutate(NativePushAction.Disable)
    }

    private fun onRetryRequested(event: NativePushEvent.RetryRequested) {
        if (active && !known) {
            onStopped(NativePushEvent.Stopped)
            onStarted(NativePushEvent.Started)
            return
        }
        if (canAct()) mutate(NativePushAction.Refresh)
    }

    private fun canAct() = active && known && sessionId != null && mutation?.isActive != true

    private fun cancelScope() {
        generation++
        statusJob?.cancel()
        statusJob = null
        tokenJob?.cancel()
        // Keep the cancelled jobs until the next mutation joins their completion.
        mutation?.cancel()
    }

    private fun owns(owner: Long) = active && known && generation == owner

    private fun mutate(action: NativePushAction) {
        val owner = generation
        val id = sessionId
        val previous = mutation
        val previousTokens = tokenJob
        previousTokens?.cancel()
        tokenJob = null
        updateState { it.copy(busy = true, failure = null) }
        watchStatus(id, owner)
        mutation =
            viewModelScope.launch {
                previous?.cancelAndJoin()
                previousTokens?.cancelAndJoin()
                if (!owns(owner)) return@launch
                try {
                    val result =
                        withTimeoutOrNull(45.seconds) {
                            if (action == NativePushAction.Enable) {
                                val permission = enable()
                                currentCoroutineContext().ensureActive()
                                if (permission is AppResult.Failed)
                                    return@withTimeoutOrNull permission
                            }
                            reconcile(id, action)
                        }
                            ?: AppResult.Failed(
                                Failure(
                                    FailureKind.Timeout,
                                    "Please try again.",
                                    code = "client.push_timeout",
                                )
                            )
                    currentCoroutineContext().ensureActive()
                    if (!owns(owner)) return@launch
                    when (result) {
                        is AppResult.Success -> {
                            updateState { it.copy(status = result.value, failure = null) }
                            if (id != null) watchTokens(id, owner)
                        }
                        is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                    }
                } finally {
                    if (owns(owner)) updateState { it.copy(busy = false) }
                }
            }
    }

    private fun watchTokens(id: String, owner: Long) {
        tokenJob =
            viewModelScope.launch {
                val result = observeTokens(id).firstOrNull() ?: return@launch
                currentCoroutineContext().ensureActive()
                if (!owns(owner)) return@launch
                when (result) {
                    is AppResult.Success -> onEvent(NativePushEvent.RetryRequested)
                    is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                }
            }
    }
}
