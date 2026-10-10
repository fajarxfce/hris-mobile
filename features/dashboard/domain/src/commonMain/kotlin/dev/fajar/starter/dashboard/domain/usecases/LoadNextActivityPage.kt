package dev.fajar.starter.dashboard.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository

class LoadNextActivityPage(
    private val dashboard: DashboardRepository,
    private val sessions: SessionRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> =
        when (val result = sessions.current()) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                result.value?.let { dashboard.loadNextPage(it.id) } ?: AppResult.Success(Unit)
        }
}
