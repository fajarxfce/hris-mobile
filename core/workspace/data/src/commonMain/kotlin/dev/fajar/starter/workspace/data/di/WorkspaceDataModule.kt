package dev.fajar.starter.workspace.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.*
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.workspace.data")
class WorkspaceDataModule {
    @Factory
    fun context(sessions: SessionRepository, workspaces: WorkspaceRepository, build: AppBuild) =
        ObserveWorkspaceContext(sessions, workspaces, build)

    @Single(binds = [RefreshWorkspace::class, SyncTask::class])
    fun refresh(
        sessions: SessionRepository,
        accounts: AccountRepository,
        workspaces: WorkspaceRepository,
    ) = RefreshWorkspace(sessions, accounts, workspaces)

    @Factory
    fun select(
        sessions: SessionRepository,
        accounts: AccountRepository,
        workspaces: WorkspaceRepository,
    ) = SelectWorkspace(sessions, accounts, workspaces)

    @Factory
    fun observe(sessions: SessionRepository, workspaces: WorkspaceRepository) =
        ObserveWorkspace(sessions, workspaces)

    @Factory
    fun leave(sessions: SessionRepository, workspaces: WorkspaceRepository) =
        LeaveWorkspace(sessions, workspaces)
}
