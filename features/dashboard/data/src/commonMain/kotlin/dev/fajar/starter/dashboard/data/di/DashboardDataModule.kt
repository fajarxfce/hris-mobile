package dev.fajar.starter.dashboard.data.di

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.dashboard.domain.usecases.*
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.dashboard.data")
class DashboardDataModule {
    @org.koin.core.annotation.Factory
    fun loadNextActivityPage(
        repository: dev.fajar.starter.dashboard.domain.repositories.DashboardRepository,
        sessions: dev.fajar.starter.identity.domain.repositories.SessionRepository,
    ) = dev.fajar.starter.dashboard.domain.usecases.LoadNextActivityPage(repository, sessions)

    @Factory
    fun observe(repository: DashboardRepository, sessions: SessionRepository) =
        ObserveDashboard(repository, sessions)

    @Factory fun request(scheduler: SyncScheduleRepository) = RequestDashboardSync(scheduler)

    @Factory
    fun save(
        repository: DashboardRepository,
        scheduler: SyncScheduleRepository,
        flags: FeatureFlagRepository,
        access: dev.fajar.starter.security.domain.access.repositories.AccessRepository,
        environment: AppEnvironment,
        sessions: SessionRepository,
    ) = SetActivitySaved(repository, scheduler, flags, environment, sessions, access)

    @Single(binds = [SyncTask::class, SyncDashboard::class])
    fun sync(repository: DashboardRepository, sessions: SessionRepository) =
        SyncDashboard(repository, sessions)
}
