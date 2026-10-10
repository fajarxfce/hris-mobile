package dev.fajar.starter.dashboard.domain.usecases

import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository

class RequestDashboardSync(private val scheduler: SyncScheduleRepository) {
    suspend operator fun invoke() = scheduler.request(SyncDashboard.KEY)
}
