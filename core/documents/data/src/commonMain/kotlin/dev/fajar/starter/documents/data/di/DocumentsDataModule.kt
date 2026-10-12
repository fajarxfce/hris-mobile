package dev.fajar.starter.documents.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.documents.domain.usecases.LoadDocumentEvidence
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.documents.data")
class DocumentsDataModule {
    @Factory
    fun loadEvidence(
        remote: DocumentRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadDocumentEvidence(remote, sessions, workspaces, Clock.System, build)
}
