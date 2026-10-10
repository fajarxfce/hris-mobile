package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.SessionIdentity
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ObserveSessionIdentity(private val sessions: SessionRepository) {
    operator fun invoke() =
        sessions
            .observe()
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(result.value?.let { SessionIdentity(it.id, it.user) })
                }
            }
            .distinctUntilChanged()
}
