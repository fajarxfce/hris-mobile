@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.DocumentFileRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Freezes the employee's confirmation before any remote mutation. */
class PrepareDocumentUpload(
    private val files: DocumentFileRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val schedule: SyncScheduleRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        prepared: DocumentUploadPreparation,
        input: DocumentUploadInput,
    ): AppResult<LocalDocumentFile> =
        withTimeoutOrNull(10.seconds) {
            val normalized =
                when (val result = validateDocumentUploadInput(input)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            if (!sameDocumentScope(prepared.access, access))
                return@withTimeoutOrNull documentFailure(
                    "client.document_scope_changed",
                    FailureKind.AccessDenied,
                )
            if (prepared.expiresAt <= clock.now() || prepared.fetchedAt > clock.now())
                return@withTimeoutOrNull documentFailure(
                    "client.document_preparation_expired",
                    FailureKind.Conflict,
                )
            if (prepared.employments.items.none { it.id == normalized.employmentId })
                return@withTimeoutOrNull documentFailure("client.document_employment_required")
            val file =
                when (val result = files.find(access, prepared.file.draft.id)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success ->
                        result.value
                            ?: return@withTimeoutOrNull documentFailure(
                                "client.document_file_changed",
                                FailureKind.Conflict,
                            )
                }
            currentCoroutineContext().ensureActive()
            val ownership = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            if (
                file.draft != prepared.file.draft ||
                    file.draft.download != null ||
                    file.sha256 == null ||
                    file.sha256 != prepared.file.sha256 ||
                    file.storedBytes != file.draft.declaration.size
            )
                return@withTimeoutOrNull documentFailure(
                    "client.document_file_changed",
                    FailureKind.Conflict,
                )
            file.upload?.let {
                return@withTimeoutOrNull if (it.intent.input == normalized) AppResult.Success(file)
                else documentFailure("client.document_upload_active", FailureKind.Conflict)
            }
            if (file.revision != prepared.file.revision)
                return@withTimeoutOrNull documentFailure(
                    "client.document_file_changed",
                    FailureKind.Conflict,
                )
            if (prepared.expiresAt <= clock.now())
                return@withTimeoutOrNull documentFailure(
                    "client.document_preparation_expired",
                    FailureKind.Conflict,
                )
            val upload =
                DocumentUpload(
                    DocumentUploadIntent(
                        Uuid.random().toString(),
                        Uuid.random().toString(),
                        normalized,
                        clock.now(),
                    ),
                    DocumentUploadStep.Start(Uuid.random().toString()),
                )
            val saved = files.updateUpload(access, file, upload)
            currentCoroutineContext().ensureActive()
            val publication = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            when (saved) {
                is AppResult.Failed -> saved
                is AppResult.Success ->
                    if (!saved.value)
                        documentFailure("client.document_file_changed", FailureKind.Conflict)
                    else
                        when (val queued = schedule.request(DOCUMENT_UPLOAD_TASK)) {
                            is AppResult.Failed -> queued
                            is AppResult.Success -> {
                                currentCoroutineContext().ensureActive()
                                val latest =
                                    validateDocumentContinuation(access, currentAccess(workspaceId))
                                if (latest is AppResult.Failed) latest
                                else
                                    AppResult.Success(
                                        file.copy(revision = file.revision + 1, upload = upload)
                                    )
                            }
                        }
            }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun currentAccess(workspaceId: String): AppResult<DocumentAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            if (session == null) null
            else
                when (val result = workspaces.current(session.id)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
        currentCoroutineContext().ensureActive()
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitDocumentImport(
            admitDocumentCatalog(workspaceId, workspace, latest, build, clock.now())
        )
    }
}
