package dev.fajar.starter.security.domain.lock.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.common.time.ElapsedClock
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.lock.entities.*
import dev.fajar.starter.security.domain.lock.repositories.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Both enabling and disabling the local lock require OS authentication. */
class SetAppLockEnabled(
    private val sessions: SessionRepository,
    private val locks: AppLockRepository,
    private val device: DeviceAuthenticationRepository,
    private val clock: ElapsedClock = ElapsedClock.System,
) {
    suspend operator fun invoke(enabled: Boolean): AppResult<Unit> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return AppResult.Failed(
                            Failure(FailureKind.Unauthorized, "Sign in to continue.")
                        )
            }
        when (val result = device.authenticate()) {
            is AppResult.Failed -> return result
            is AppResult.Success -> Unit
        }
        currentCoroutineContext().ensureActive()
        val current =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (current?.id != session.id)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "The session changed. Sign in again.")
            )
        if (enabled) {
            when (
                val result =
                    locks.authorize(
                        DeviceAuthorization(
                            session.id,
                            clock.milliseconds() + APP_LOCK_TIMEOUT_MILLIS,
                        )
                    )
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> Unit
            }
        }
        return locks.setEnabled(enabled)
    }
}
