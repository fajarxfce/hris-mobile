package dev.fajar.starter.notifications.presentation.inbox

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.localization.AppString
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.usecases.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class NotificationViewModel(
    private val observeNotifications: ObserveNotifications,
    private val checkAccess: CheckNotificationAccess,
    private val enableNotifications: EnableNotifications,
    private val sendTest: SendTestNotification,
    private val markRead: MarkNotificationRead,
    private val clearNotifications: ClearNotifications,
    private val getPushToken: GetPushToken,
) : MviViewModel<NotificationState, NotificationEvent, NotificationEffect>(NotificationState()) {
    private var observation: Job? = null
    private var permissionCheck: Job? = null

    init {
        on<NotificationEvent.TokenRequested>(::onTokenRequested)
        on<NotificationEvent.Started>(::onStarted)
        on<NotificationEvent.Resumed>(::onResumed)
        on<NotificationEvent.PermissionRequested>(::onPermissionRequested)
        on<NotificationEvent.TestRequested>(::onTestRequested)
        on<NotificationEvent.ClearRequested>(::onClearRequested)
        on<NotificationEvent.MessageOpened>(::onMessageOpened)
        on<NotificationEvent.BackRequested>(::onBackRequested)
        onEvent(NotificationEvent.Started)
    }

    private fun onTokenRequested(event: NotificationEvent.TokenRequested) {
        if (state.value.busy) return
        updateState { it.copy(busy = true, error = null, status = null) }
        viewModelScope.launch {
            try {
                when (val result = getPushToken()) {
                    is AppResult.Success -> {
                        emitEffect(NotificationEffect.CopyToken(result.value))
                        updateState { it.copy(status = AppString.TokenCopied) }
                    }
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                }
            } finally {
                updateState { it.copy(busy = false) }
            }
        }
    }

    private fun onStarted(event: NotificationEvent.Started) {
        observation?.cancel()
        updateState { it.copy(loading = true, error = null) }
        observation =
            viewModelScope.launch {
                observeNotifications().collect { result ->
                    when (result) {
                        is AppResult.Success ->
                            updateState { it.copy(messages = result.value, loading = false) }
                        is AppResult.Failed ->
                            updateState { it.copy(error = result.failure, loading = false) }
                    }
                }
            }
    }

    private fun onResumed(event: NotificationEvent.Resumed) {
        if (state.value.busy) return
        permissionCheck?.cancel()
        permissionCheck =
            viewModelScope.launch {
                when (val result = checkAccess()) {
                    is AppResult.Success -> updateState { it.copy(access = result.value) }
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                }
            }
    }

    private fun onPermissionRequested(event: NotificationEvent.PermissionRequested) {
        if (state.value.busy) return
        permissionCheck?.cancel()
        updateState { it.copy(busy = true, status = null, error = null) }
        viewModelScope.launch {
            try {
                when (val result = enableNotifications()) {
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                access = result.value,
                                status =
                                    when (result.value) {
                                        NotificationAccess.Granted -> AppString.NotificationsEnabled
                                        NotificationAccess.Denied -> AppString.NotificationsBlocked
                                        NotificationAccess.Unavailable ->
                                            AppString.NotificationsUnavailable
                                    },
                            )
                        }
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                }
            } finally {
                updateState { it.copy(busy = false) }
            }
        }
    }

    private fun onTestRequested(event: NotificationEvent.TestRequested) {
        if (state.value.busy) return
        updateState { it.copy(busy = true, status = null, error = null) }
        viewModelScope.launch {
            try {
                when (val result = sendTest()) {
                    is AppResult.Success ->
                        updateState { it.copy(status = AppString.NotificationSent) }
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                }
            } finally {
                updateState { it.copy(busy = false) }
            }
        }
    }

    private fun onClearRequested(event: NotificationEvent.ClearRequested) {
        if (state.value.busy) return
        updateState { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = clearNotifications()) {
                    is AppResult.Success -> updateState { it.copy(status = null) }
                    is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                }
            } finally {
                updateState { it.copy(busy = false) }
            }
        }
    }

    private fun onMessageOpened(event: NotificationEvent.MessageOpened) {
        viewModelScope.launch {
            when (val result = markRead(event.message.id)) {
                is AppResult.Success ->
                    emitEffect(NotificationEffect.OpenDestination(event.message.destination))
                is AppResult.Failed -> updateState { it.copy(error = result.failure) }
            }
        }
    }

    private fun onBackRequested(event: NotificationEvent.BackRequested) {
        viewModelScope.launch { emitEffect(NotificationEffect.Back) }
    }
}
