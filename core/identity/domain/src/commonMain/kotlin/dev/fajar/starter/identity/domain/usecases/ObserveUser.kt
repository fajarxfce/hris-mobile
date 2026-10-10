package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ObserveUser(private val repository: SessionRepository) {
    operator fun invoke() =
        repository
            .observe()
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> AppResult.Success(result.value?.user)
                }
            }
            .distinctUntilChanged()
}
