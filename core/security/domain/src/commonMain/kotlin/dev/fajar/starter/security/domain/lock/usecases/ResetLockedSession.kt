package dev.fajar.starter.security.domain.lock.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.lock.repositories.AppLockRepository

/** Account sign-in is the recovery path. Clear the session before removing local lock opt-in. */
class ResetLockedSession(
    private val sessions: SessionRepository,
    private val locks: AppLockRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        when (val result = sessions.compareAndSet(session, null)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return AppResult.Failed(
                        Failure(FailureKind.Unauthorized, "The session changed. Try again.")
                    )
        }
        when (val result = locks.revoke()) {
            is AppResult.Failed -> return result
            is AppResult.Success -> Unit
        }
        return locks.setEnabled(false)
    }
}
