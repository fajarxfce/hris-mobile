package dev.fajar.starter.transfers.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.transfers.domain.entities.Transfer
import dev.fajar.starter.transfers.domain.repositories.TransferQueueRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveTransfers(
    private val sessions: SessionRepository,
    private val queue: TransferQueueRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<List<Transfer>>> =
        sessions
            .observe()
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> AppResult.Success(result.value?.id)
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success ->
                        result.value?.let(queue::observe) ?: flowOf(AppResult.Success(emptyList()))
                }
            }
            .distinctUntilChanged()
}
