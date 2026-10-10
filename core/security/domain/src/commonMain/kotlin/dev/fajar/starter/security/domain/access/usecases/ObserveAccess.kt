package dev.fajar.starter.security.domain.access.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.entities.AccessSnapshot
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveAccess(private val sessions: SessionRepository, private val access: AccessRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<AccessSnapshot?>> =
        sessions.observe().flatMapLatest { result ->
            when (result) {
                is AppResult.Failed -> flowOf(result)
                is AppResult.Success ->
                    result.value?.let { access.observe(it.id) } ?: flowOf(AppResult.Success(null))
            }
        }
}
