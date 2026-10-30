package dev.fajar.starter.documents.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.documents.domain.metadata.commands.repositories.*
import dev.fajar.starter.documents.domain.metadata.commands.usecases.*
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.metadata.usecases.*
import dev.fajar.starter.documents.domain.metadata.working.repositories.*
import dev.fajar.starter.documents.domain.metadata.working.usecases.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.documents.domain.replica.usecases.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.documents.domain.usecases.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.documents.data")
class DocumentsDataModule {
    @Single @Named("document-metadata-working") fun metadataWorkingMutex() = Mutex()

    @Factory
    fun observeMetadataWorkingCopies(
        copies: DocumentMetadataWorkingRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        ObserveDocumentMetadataWorkingCopies(
            copies,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
        )

    @Factory
    fun openMetadataWorkingCopy(
        copies: DocumentMetadataWorkingRepository,
        cache: DocumentMetadataCacheRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-metadata-working") execution: Mutex,
    ) =
        OpenDocumentMetadataWorkingCopy(
            copies,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
            cache,
        )

    @Factory
    fun saveMetadataWorkingCopy(
        copies: DocumentMetadataWorkingRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-metadata-working") execution: Mutex,
    ) =
        SaveDocumentMetadataWorkingCopy(
            copies,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Factory
    fun discardMetadataWorkingCopy(
        copies: DocumentMetadataWorkingRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-metadata-working") execution: Mutex,
    ) =
        DiscardDocumentMetadataWorkingCopy(
            copies,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Single @Named("document-metadata-command") fun metadataCommandMutex() = Mutex()

    @Factory
    fun observeMetadataCommands(
        commands: DocumentMetadataCommandRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        ObserveDocumentMetadataCommands(
            commands,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
        )

    @Factory
    fun prepareMetadataCommand(
        commands: DocumentMetadataCommandRepository,
        cache: DocumentMetadataCacheRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-metadata-command") execution: Mutex,
    ) =
        PrepareDocumentMetadataCommand(
            commands,
            cache,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Factory
    fun reviewMetadataCommand(
        commands: DocumentMetadataCommandRepository,
        copies: DocumentMetadataWorkingRepository,
        cache: DocumentMetadataCacheRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-metadata-command") execution: Mutex,
    ) =
        ReviewDocumentMetadataCommand(
            commands,
            copies,
            cache,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Factory
    fun replayMetadataCommand(
        commands: DocumentMetadataCommandRepository,
        remote: DocumentMetadataRepository,
        directory: DocumentRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-metadata-command") execution: Mutex,
    ) =
        ReplayDocumentMetadataCommand(
            commands,
            remote,
            directory,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Single @Named("document-metadata-read") fun metadataReadMutex() = Mutex()

    @Factory
    fun observeMetadata(
        cache: DocumentMetadataCacheRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) =
        ObserveDocumentMetadata(
            cache,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
        )

    @Factory
    fun refreshMetadata(
        remote: DocumentMetadataRepository,
        directory: DocumentRepository,
        cache: DocumentMetadataCacheRepository,
        admission: DocumentMetadataAdmissionRepository,
        catalog: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
        @Named("document-metadata-read") execution: Mutex,
    ) =
        RefreshDocumentMetadata(
            remote,
            directory,
            cache,
            admission,
            catalog,
            sessions,
            workspaces,
            Clock.System,
            build,
            execution,
        )

    @Single(binds = [SyncDocumentCatalog::class, SyncTask::class, SyncCompanyData::class])
    fun syncCatalog(
        feed: SyncFeedRepository,
        remote: DocumentRepository,
        cache: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SyncDocumentCatalog(feed, remote, cache, sessions, workspaces, Clock.System, build)

    @Factory(binds = [ObserveDocumentCatalog::class, ObserveCompanyData::class])
    fun observeCatalog(
        cache: DocumentCatalogRepository,
        resources: DocumentMetadataAdmissionRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveDocumentCatalog(cache, resources, sessions, workspaces, Clock.System, build)

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

    @Factory(binds = [ObservePendingActions::class])
    fun observeDocumentPending(
        files: DocumentFileRepository,
        reads: DocumentCatalogRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ObserveDocumentPending(files, reads, sessions, workspaces, Clock.System, build)
}
