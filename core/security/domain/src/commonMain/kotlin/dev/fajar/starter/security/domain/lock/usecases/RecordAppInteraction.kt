package dev.fajar.starter.security.domain.lock.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.common.time.ElapsedClock
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.lock.entities.*
import dev.fajar.starter.security.domain.lock.repositories.AppLockRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Extends an existing grant at most once per 15 seconds. An expired grant cannot be revived. */
class RecordAppInteraction(
    private val sessions: SessionRepository,
    private val locks: AppLockRepository,
    private val clock: ElapsedClock = ElapsedClock.System,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        val snapshot =
            when (val result = locks.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!snapshot.enabled) return AppResult.Success(Unit)
        val grant = snapshot.authorization ?: return AppResult.Success(Unit)
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val now = clock.milliseconds()
        if (
            grant.sessionId != session?.id ||
                grant.expiresAtMillis <= now ||
                grant.expiresAtMillis - now >
                    APP_LOCK_TIMEOUT_MILLIS - APP_LOCK_ACTIVITY_INTERVAL_MILLIS
        )
            return AppResult.Success(Unit)
        currentCoroutineContext().ensureActive()
        return when (val result = locks.renew(grant, now + APP_LOCK_TIMEOUT_MILLIS)) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(Unit)
        }
    }
}
