package dev.fajar.starter.collaboration.data.di

import dev.fajar.starter.collaboration.domain.repositories.CollaborationSetupRepository
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSetup
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.collaboration.data")
class CollaborationDataModule {
    @Factory
    fun loadSetup(
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        setup: CollaborationSetupRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = LoadCollaborationSetup(sessions, workspaces, setup, server, build, Clock.System)
}
