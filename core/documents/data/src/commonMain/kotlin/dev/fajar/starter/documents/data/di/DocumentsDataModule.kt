package dev.fajar.starter.documents.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.documents.data")
class DocumentsDataModule {
    @Factory
    fun observeFiles(
        files: DocumentFileRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveDocumentFiles(files, sessions, workspaces, Clock.System, build)

    @Factory
    fun removeFile(
        files: DocumentFileRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = RemoveDocumentFile(files, sessions, workspaces, Clock.System, build)

    @Factory
    fun importFile(
        inputs: DocumentInputRepository,
        files: DocumentFileRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ImportDocumentFile(inputs, files, sessions, workspaces, Clock.System, build)

    @Factory
    fun loadEvidence(
        remote: DocumentRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadDocumentEvidence(remote, sessions, workspaces, Clock.System, build)
}
