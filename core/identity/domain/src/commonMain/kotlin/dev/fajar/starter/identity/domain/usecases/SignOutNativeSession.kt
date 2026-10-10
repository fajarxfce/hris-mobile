package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class SignOutNativeSession(
    private val sessions: SessionRepository,
    private val devices: DeviceSessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(sessionId: String): AppResult<SignOutReceipt> {
        currentCoroutineContext().ensureActive()
        val original =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (original?.id != sessionId) return AppResult.Success(SignOutReceipt(false))
        currentCoroutineContext().ensureActive()
        val cleared: AppResult<Boolean>
        val remote =
            try {
                // The authenticated client may rotate tokens; never hold the credential mutex here.
                if (original.binding.audience == server.audience)
                    devices.revoke(sessionId, original.binding.serverId)
                else
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unauthorized,
                            "Sign in again.",
                            code = "client.session_changed",
                        )
                    )
            } finally {
                // An admitted logout removes only its own local identity, even if its route is
                // disposed.
                cleared = withContext(NonCancellable) { sessions.clear(sessionId) }
            }
        currentCoroutineContext().ensureActive()
        return when (cleared) {
            is AppResult.Failed -> cleared
            is AppResult.Success ->
                AppResult.Success(
                    SignOutReceipt(
                        remote is AppResult.Success,
                        (remote as? AppResult.Failed)?.failure,
                    )
                )
        }
    }
}
