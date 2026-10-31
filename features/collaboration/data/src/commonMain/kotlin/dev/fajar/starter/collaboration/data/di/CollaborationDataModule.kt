package dev.fajar.starter.collaboration.data.di

import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.collaboration.data")
class CollaborationDataModule {
    @Single
    fun maintainRuntimeOwnership(
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        MaintainCollaborationRuntimeOwnership(
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun restoreRuntime(
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = RestoreCollaborationRuntime(runtimes, sessions, workspaces, server, build, Clock.System)

    @Single
    fun installRuntime(
        commands: CollaborationSetupCommandRepository,
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        InstallCollaborationRuntime(
            commands,
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun loadSetup(
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        setup: CollaborationSetupRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = LoadCollaborationSetup(sessions, workspaces, setup, server, build, Clock.System)

    @Factory
    fun prepareSetup(
        commands: CollaborationSetupCommandRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationSetup(
            commands,
            setup,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun replaySetup(
        commands: CollaborationSetupCommandRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReplayCollaborationSetup(commands, setup, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun reconcileSetup(
        commands: CollaborationSetupCommandRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReconcileCollaborationSetupReceipt(
            commands,
            setup,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )
}
