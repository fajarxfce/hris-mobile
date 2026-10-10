@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.dashboard.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.dashboard.domain.entities.Dashboard
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.*

class ObserveDashboard(
    private val repository: DashboardRepository,
    private val sessions: SessionRepository,
) {
    operator fun invoke(): Flow<AppResult<Dashboard?>> =
        sessions.observe().flatMapLatest { result ->
            when (result) {
                is AppResult.Failed -> flowOf(result)
                is AppResult.Success ->
                    result.value?.let { repository.observe(it.id) }
                        ?: flowOf(AppResult.Success(null))
            }
        }
}
