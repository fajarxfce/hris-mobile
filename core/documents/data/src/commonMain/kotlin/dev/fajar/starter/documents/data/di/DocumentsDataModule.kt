package dev.fajar.starter.documents.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.documents.data")
class DocumentsDataModule {
    @Factory
    fun exportFile(
        files: DocumentFileRepository,
        content: DocumentContentRepository,
        outputs: DocumentOutputRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ExportDocumentFile(files, content, outputs, sessions, workspaces, Clock.System, build)

    @Factory
    fun loadEmployments(
        remote: DocumentRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadDocumentEmployments(remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun observeAccess(
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveDocumentAccess(sessions, workspaces, Clock.System, build)

    @Single @Named("document-upload") fun uploadMutex() = Mutex()

    @Single @Named("document-download") fun downloadMutex() = Mutex()

    @Factory
    fun prepareDownload(
        files: DocumentFileRepository,
        content: DocumentContentRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-download") execution: Mutex,
    ) =
        PrepareDocumentDownload(
            files,
            content,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Factory
    fun downloadFile(
        files: DocumentFileRepository,
        content: DocumentContentRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-download") execution: Mutex,
    ) = DownloadDocumentFile(files, content, sessions, workspaces, Clock.System, build, execution)

    @Factory
    fun loadUpload(
        files: DocumentFileRepository,
        remote: DocumentRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = LoadDocumentUploadPreparation(files, remote, sessions, workspaces, Clock.System, build)

    @Factory
    fun prepareUpload(
        files: DocumentFileRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        schedule: SyncScheduleRepository,
        build: AppBuild,
    ) = PrepareDocumentUpload(files, sessions, workspaces, schedule, Clock.System, build)

    @Single(binds = [ReplayDocumentUpload::class, SyncTask::class])
    fun replayUpload(
        files: DocumentFileRepository,
        remote: DocumentUploadRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-upload") execution: Mutex,
        schedule: SyncScheduleRepository,
    ) =
        ReplayDocumentUpload(
            files,
            remote,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
            schedule,
        )

    @Factory
    fun reviewUpload(
        files: DocumentFileRepository,
        remote: DocumentUploadRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        schedule: SyncScheduleRepository,
        build: AppBuild,
        @Named("document-upload") execution: Mutex,
    ) =
        ReviewDocumentUpload(
            files,
            remote,
            sessions,
            workspaces,
            schedule,
            Clock.System,
            build,
            execution,
        )

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
