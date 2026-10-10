package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.repositories.SessionRepository

class SignOut(private val repository: SessionRepository) {
    suspend operator fun invoke(): AppResult<Unit> {
        val session =
            when (val result = repository.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return when (val cleared = repository.compareAndSet(session, null)) {
            is AppResult.Failed -> cleared
            is AppResult.Success -> AppResult.Success(Unit)
        }
    }
}
