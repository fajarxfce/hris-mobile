package dev.fajar.starter.workforce.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.repositories.*
import dev.fajar.starter.workforce.domain.usecases.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.workforce.data")
class WorkforceDataModule {
    @Factory
    fun observeCalendar(
        cache: WorkCalendarCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveWorkCalendar(cache, sessions, workspaces, Clock.System, build)

    @Single
    fun refreshCalendar(
        remote: WorkforceRepository,
        cache: WorkCalendarCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshWorkCalendar(remote, cache, sessions, workspaces, Clock.System, build)
}
