package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.DeviceSessions
import dev.fajar.starter.identity.domain.repositories.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LoadDeviceSessions(
    private val sessions: SessionRepository,
    private val devices: DeviceSessionRepository,
) {
    suspend operator fun invoke(sessionId: String): AppResult<DeviceSessions> {
        val original =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (original?.id != sessionId)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        val items =
            when (val result = devices.list(sessionId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val current =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (current?.id != sessionId || current.binding != original.binding)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        return AppResult.Success(DeviceSessions(original.binding.serverId, items))
    }
}
