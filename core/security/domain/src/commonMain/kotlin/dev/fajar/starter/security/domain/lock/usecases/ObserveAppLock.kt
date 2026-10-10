package dev.fajar.starter.security.domain.lock.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.time.ElapsedClock
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.lock.entities.AppLockStatus
import dev.fajar.starter.security.domain.lock.repositories.AppLockRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveAppLock(
    private val sessions: SessionRepository,
    private val locks: AppLockRepository,
    private val clock: ElapsedClock = ElapsedClock.System,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<AppLockStatus>> =
        combine(sessions.observe(), locks.observe()) { session, lock -> session to lock }
            .flatMapLatest { (session, lock) ->
                flow<AppResult<AppLockStatus>> {
                    val id =
                        when (session) {
                            is AppResult.Failed -> {
                                emit(session)
                                return@flow
                            }
                            is AppResult.Success -> session.value?.id
                        }
                    val snapshot =
                        when (lock) {
                            is AppResult.Failed -> {
                                emit(lock)
                                return@flow
                            }
                            is AppResult.Success -> lock.value
                        }
                    val grant = snapshot.authorization
                    val remaining =
                        if (grant?.sessionId == id)
                            (grant?.expiresAtMillis ?: 0) - clock.milliseconds()
                        else 0
                    val protected = snapshot.enabled && id != null
                    emit(
                        AppResult.Success(
                            AppLockStatus(snapshot.enabled, id, protected && remaining <= 0)
                        )
                    )
                    if (protected && remaining > 0) {
                        delay(remaining)
                        emit(AppResult.Success(AppLockStatus(snapshot.enabled, id, true)))
                    }
                }
            }
            .distinctUntilChanged()
}
