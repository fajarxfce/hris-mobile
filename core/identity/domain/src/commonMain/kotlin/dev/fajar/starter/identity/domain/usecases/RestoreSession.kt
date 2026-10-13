package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository

/** Restores local identity without making network availability a startup requirement. */
class RestoreSession(
    private val repository: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(): AppResult<Session?> {
        val session =
            when (val result = repository.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return result
            }
        // Credentials can expire while the remembered identity still owns encrypted local data.
        // AcquireSessionTokens checks remote eligibility before any authenticated request.
        if (session.binding.audience == server.audience) return AppResult.Success(session)
        return when (val cleared = repository.compareAndSet(session, null)) {
            is AppResult.Failed -> cleared
            is AppResult.Success ->
                if (cleared.value) AppResult.Success(null)
                else
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unavailable,
                            "The session changed. Try again.",
                            code = "client.session_changed",
                        )
                    )
        }
    }
}
