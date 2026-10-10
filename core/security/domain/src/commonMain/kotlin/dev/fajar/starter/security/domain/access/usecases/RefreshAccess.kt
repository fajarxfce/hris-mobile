package dev.fajar.starter.security.domain.access.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RefreshAccess(private val sessions: SessionRepository, private val access: AccessRepository) {
    private val refresh = Mutex()

    suspend operator fun invoke(): AppResult<Unit> =
        refresh.withLock {
            val session =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                } ?: return@withLock AppResult.Success(Unit)
            val result = access.refresh(session.id)
            if (
                result is AppResult.Failed &&
                    result.failure.kind in setOf(FailureKind.AccessDenied, FailureKind.Unauthorized)
            ) {
                when (val invalidated = access.invalidate(session.id)) {
                    is AppResult.Failed -> return@withLock invalidated
                    is AppResult.Success -> Unit
                }
            }
            result
        }
}
