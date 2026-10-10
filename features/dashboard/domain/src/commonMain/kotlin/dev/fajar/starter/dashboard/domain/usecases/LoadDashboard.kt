package dev.fajar.starter.dashboard.domain.usecases

import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository

class LoadDashboard(private val repository: DashboardRepository) {
    suspend operator fun invoke() = repository.load()
}
