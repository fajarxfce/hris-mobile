package dev.fajar.starter.leave.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.leave.data")
class LeaveDataModule {
    @Factory
    fun observeLeave(
        cache: LeaveCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveLeave(cache, sessions, workspaces, Clock.System, build)

    @Single
    fun refreshLeave(
        remote: LeaveRepository,
        cache: LeaveCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RefreshLeave(remote, cache, sessions, workspaces, Clock.System, build)
}
