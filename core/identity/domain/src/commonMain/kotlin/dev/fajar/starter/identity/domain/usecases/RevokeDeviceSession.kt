package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class RevokeDeviceSession(
    private val sessions: SessionRepository,
    private val devices: DeviceSessionRepository,
) {
    suspend operator fun invoke(sessionId: String, remoteId: String): AppResult<Unit> {
        val original =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (original?.id != sessionId)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        if (remoteId == original.binding.serverId)
            return AppResult.Failed(
                Failure(
                    FailureKind.Validation,
                    "Use sign out on this device.",
                    code = "client.current_session",
                )
            )
        when (val result = devices.revoke(sessionId, remoteId)) {
            is AppResult.Failed -> return result
            is AppResult.Success -> Unit
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
        return AppResult.Success(Unit)
    }
}
