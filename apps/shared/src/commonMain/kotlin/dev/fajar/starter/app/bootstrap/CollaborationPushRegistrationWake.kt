package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.work.recoveryParent
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A registration change requests existing durable work; it grants no SDK or message access. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun wakeCollaborationOnPushRegistration(
    states: Flow<ApplicationCollaborationState>,
    observe: (String) -> Flow<AppResult<NativePushStatus>>,
    request: suspend () -> AppResult<Unit>,
) {
    states
        .map { it.recoveryParent() }
        .distinctUntilChanged()
        .flatMapLatest { parent ->
            if (parent == null) flowOf(null)
            else
                observe(parent.sessionId)
                    .map { result ->
                        val status = (result as? AppResult.Success)?.value
                        if (
                            status != null &&
                                status.supported &&
                                status.enabled &&
                                status.registered &&
                                !status.pending &&
                                status.access == NotificationAccess.Granted
                        )
                            parent to status
                        else null
                    }
                    .onStart { emit(null) }
        }
        .distinctUntilChanged()
        .buffer(0)
        .collect { ready ->
            currentCoroutineContext().ensureActive()
            if (ready != null) withTimeoutOrNull(1_500) { request() }
        }
}
