package dev.fajar.starter.workspace.data.di

import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.*
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.workspace.data")
class WorkspaceDataModule {
    @Factory
    fun select(
        sessions: SessionRepository,
        accounts: AccountRepository,
        workspaces: WorkspaceRepository,
    ) = SelectWorkspace(sessions, accounts, workspaces)

    @Factory
    fun observe(sessions: SessionRepository, workspaces: WorkspaceRepository) =
        ObserveWorkspace(sessions, workspaces)

    @Factory fun leave(workspaces: WorkspaceRepository) = LeaveWorkspace(workspaces)
}
