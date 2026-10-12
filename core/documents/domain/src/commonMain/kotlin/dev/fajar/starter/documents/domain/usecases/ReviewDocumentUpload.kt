@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Re-reads remote facts before persisting an explicitly requested recovery or cancellation. */
class ReviewDocumentUpload(
    private val files: DocumentFileRepository,
    private val remote: DocumentUploadRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val schedule: SyncScheduleRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex,
) {
    suspend operator fun invoke(
        workspaceId: String,
        fileId: String,
        action: DocumentUploadAction,
    ): AppResult<LocalDocumentFile> =
        withTimeoutOrNull(30.seconds) {
            execution.withLock {
                val access =
                    when (val result = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val file =
                    when (val result = files.find(access, fileId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success ->
                            result.value
                                ?: return@withLock documentFailure(
                                    "client.document_file_changed",
                                    FailureKind.Conflict,
                                )
                    }
                currentCoroutineContext().ensureActive()
                val upload =
                    file.upload
                        ?: return@withLock documentFailure(
                            "client.document_upload_missing",
                            FailureKind.Conflict,
                        )
                if (upload.nextAttemptAt?.let { it > clock.now() } == true)
                    return@withLock documentFailure(
                        "client.document_upload_waiting",
                        FailureKind.Conflict,
                    )
                val requestedAt = clock.now()
                val response = workspaces.clientPolicy(access.sessionId, access.companyId)
                currentCoroutineContext().ensureActive()
                val ownership = validateDocumentContinuation(access, currentAccess(workspaceId))
                if (ownership is AppResult.Failed) return@withLock ownership
                val policy =
                    when (response) {
                        is AppResult.Failed -> return@withLock response
                        is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
                    }
                val available =
                    admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
                if (available is AppResult.Failed) return@withLock available
                val progress = remote.revision(access, file, upload.intent)
                currentCoroutineContext().ensureActive()
                val continued = validateDocumentContinuation(access, currentAccess(workspaceId))
                if (continued is AppResult.Failed) return@withLock continued
                val stillAvailable =
                    admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
                if (stillAvailable is AppResult.Failed) return@withLock stillAvailable
                val reviewed =
                    when (progress) {
                        is AppResult.Failed -> return@withLock progress
                        is AppResult.Success ->
                            when (
                                val result =
                                    resolveDocumentUploadReview(
                                        upload,
                                        progress.value,
                                        file.draft.declaration.size,
                                        action,
                                        Uuid.random().toString(),
                                    )
                            ) {
                                is AppResult.Failed -> return@withLock result
                                is AppResult.Success -> result.value
                            }
                    }
                val saved = files.updateUpload(access, file, reviewed)
                currentCoroutineContext().ensureActive()
                val publication = validateDocumentContinuation(access, currentAccess(workspaceId))
                if (publication is AppResult.Failed) return@withLock publication
                when (saved) {
                    is AppResult.Failed -> return@withLock saved
                    is AppResult.Success ->
                        if (!saved.value)
                            return@withLock documentFailure(
                                "client.document_file_changed",
                                FailureKind.Conflict,
                            )
                }
                if (reviewed.step !is DocumentUploadStep.Finished) {
                    val queued = schedule.request(DOCUMENT_UPLOAD_TASK)
                    currentCoroutineContext().ensureActive()
                    if (queued is AppResult.Failed) return@withLock queued
                }
                val latest = validateDocumentContinuation(access, currentAccess(workspaceId))
                if (latest is AppResult.Failed) latest
                else AppResult.Success(file.copy(revision = file.revision + 1, upload = reviewed))
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
