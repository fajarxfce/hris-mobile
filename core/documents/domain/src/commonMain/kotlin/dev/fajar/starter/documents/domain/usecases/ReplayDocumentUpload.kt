@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner for manual/worker delivery. Every uncertain mutation keeps its original identity. */
class ReplayDocumentUpload(
    private val files: DocumentFileRepository,
    private val remote: DocumentUploadRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex,
    private val schedule: SyncScheduleRepository,
) : SyncTask {
    override val key = DOCUMENT_UPLOAD_TASK

    suspend operator fun invoke(workspaceId: String, fileId: String): AppResult<LocalDocumentFile> =
        withTimeoutOrNull(45.seconds) {
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
                val result = advance(workspaceId, access, file)
                if (result is AppResult.Success) {
                    val upload = result.value.upload
                    if (
                        upload != null &&
                            upload.step !is DocumentUploadStep.Finished &&
                            upload.nextAttemptAt == null &&
                            result.value.revision > file.revision
                    ) {
                        val queued = schedule.request(key)
                        currentCoroutineContext().ensureActive()
                        if (queued is AppResult.Failed) return@withLock queued
                        val continued =
                            validateDocumentContinuation(access, currentAccess(workspaceId))
                        if (continued is AppResult.Failed) return@withLock continued
                    }
                }
                result
            }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    override suspend fun invoke(): SyncResult =
        withTimeoutOrNull(45.seconds) { execution.withLock { deliverBatch() } }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Document delivery timed out.",
                    code = "client.document_timeout",
                )
            )

    private suspend fun deliverBatch(): SyncResult {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        val access =
            when (val result = currentAccess(workspace.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value
            }
        val rows =
            when (val result = files.observe(access).first()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val ownership = validateDocumentContinuation(access, currentAccess(workspace.id))
        if (ownership is AppResult.Failed) return syncFailure(ownership.failure)
        var pending: SyncResult = SyncResult.Complete
        var visited = 0
        // The local store has a hard 20-file limit. Blocked files must not starve other uploads.
        for (file in rows.take(DOCUMENT_LOCAL_FILES)) {
            val upload = file.upload ?: continue
            if (upload.step is DocumentUploadStep.Finished) continue
            if (
                upload.attempts >= DOCUMENT_UPLOAD_AUTOMATIC_ATTEMPTS ||
                    (upload.failure != null && !isDocumentUploadRetryable(upload.failure))
            ) {
                if (pending == SyncResult.Complete)
                    pending = SyncResult.Blocked(upload.failure ?: retryLimit())
                continue
            }
            if (upload.nextAttemptAt?.let { it > clock.now() } == true) {
                pending = SyncResult.Retry(waiting())
                continue
            }
            if (visited == 4) return SyncResult.Retry()
            visited++
            when (val result = advance(workspace.id, access, file, automatic = true)) {
                is AppResult.Failed -> {
                    if (
                        result.failure.kind == FailureKind.AccessDenied ||
                            result.failure.kind == FailureKind.Unauthorized
                    )
                        return syncFailure(result.failure)
                    if (isDocumentUploadRetryable(result.failure))
                        pending = SyncResult.Retry(result.failure)
                    else if (pending == SyncResult.Complete)
                        pending = SyncResult.Blocked(result.failure)
                }
                is AppResult.Success -> {
                    val latest = requireNotNull(result.value.upload)
                    if (latest.step !is DocumentUploadStep.Finished) {
                        if (latest.failure != null && !isDocumentUploadRetryable(latest.failure)) {
                            if (pending == SyncResult.Complete)
                                pending = SyncResult.Blocked(latest.failure)
                        } else
                            pending =
                                SyncResult.Retry(
                                    if (latest.nextAttemptAt != null) waiting() else null
                                )
                    }
                }
            }
        }
        return pending
    }

    private suspend fun advance(
        workspaceId: String,
        access: DocumentAccess,
        initial: LocalDocumentFile,
        automatic: Boolean = false,
    ): AppResult<LocalDocumentFile> {
        val original =
            initial.upload
                ?: return documentFailure("client.document_upload_missing", FailureKind.Conflict)
        val ownership = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        if (original.step is DocumentUploadStep.Finished || isDocumentUploadRejected(original))
            return AppResult.Success(initial)
        if (original.nextAttemptAt?.let { it > clock.now() } == true)
            return AppResult.Success(initial)
        val policy =
            when (val result = currentPolicy(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var file = initial
        repeat(4) {
            val upload = requireNotNull(file.upload)
            val limit =
                if (automatic) DOCUMENT_UPLOAD_AUTOMATIC_ATTEMPTS
                else DOCUMENT_UPLOAD_MAXIMUM_ATTEMPTS
            if (upload.attempts >= limit) return AppResult.Failed(retryLimit())
            val admission = beforeTransport(workspaceId, access, policy)
            if (admission is AppResult.Failed) return admission
            // A pending process owns this attempt for a bounded period; another process must
            // win CAS and wait before replaying the identical key/body after interruption.
            val attempt =
                upload.copy(
                    attempts = upload.attempts + 1,
                    failure = null,
                    nextAttemptAt = clock.now() + 120.seconds,
                )
            val sending =
                when (val result = persist(access, file, attempt)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val ready = beforeTransport(workspaceId, access, policy)
            if (ready is AppResult.Failed) return ready
            val response = performStep(workspaceId, access, sending, policy)
            currentCoroutineContext().ensureActive()
            val next =
                when (response) {
                    is AppResult.Failed ->
                        attempt.copy(
                            failure = response.failure,
                            nextAttemptAt =
                                documentUploadRetryAt(clock.now(), response.failure, policy),
                        )
                    is AppResult.Success -> response.value
                }
            file =
                when (val result = persist(access, sending, next)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val publication = beforeTransport(workspaceId, access, policy)
            if (publication is AppResult.Failed) return publication
            if (
                next.failure != null ||
                    next.nextAttemptAt != null ||
                    next.step is DocumentUploadStep.Finished
            )
                return AppResult.Success(file)
        }
        return AppResult.Success(file)
    }

    private suspend fun performStep(
        workspaceId: String,
        access: DocumentAccess,
        file: LocalDocumentFile,
        policy: CompanyPolicySnapshot,
    ): AppResult<DocumentUpload> {
        val upload = requireNotNull(file.upload)
        val intent = upload.intent
        return when (val step = upload.step) {
            is DocumentUploadStep.Start ->
                confirmed(
                    remote.start(access, file, intent, step.operationId),
                    upload,
                    0,
                    DocumentUploadStep.Chunk(Uuid.random().toString(), 0, 0),
                )
            is DocumentUploadStep.Chunk -> sendChunk(workspaceId, access, file, step, policy)
            is DocumentUploadStep.Validate ->
                confirmed(
                    remote.validate(
                        access,
                        intent.revisionId,
                        step.operationId,
                        step.version,
                        intent.input.reason,
                    ),
                    upload,
                    step.version + 1,
                    DocumentUploadStep.Processing(step.version + 1),
                )
            is DocumentUploadStep.Cancel ->
                confirmed(
                    remote.cancel(
                        access,
                        intent.revisionId,
                        step.operationId,
                        step.version,
                        step.reason,
                    ),
                    upload,
                    step.version + 1,
                    DocumentUploadStep.Processing(step.version + 1),
                )
            is DocumentUploadStep.Processing ->
                when (val result = remote.revision(access, file, intent)) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val progress = result.value
                        if (
                            progress.version < step.version ||
                                progress.status == DocumentRevisionStatus.UPLOADING
                        )
                            documentFailure("client.invalid_response", FailureKind.Unexpected)
                        else if (progress.status == DocumentRevisionStatus.VALIDATING)
                            AppResult.Success(
                                upload.copy(
                                    step = DocumentUploadStep.Processing(progress.version),
                                    nextAttemptAt = clock.now() + 15.seconds,
                                    failure = null,
                                )
                            )
                        else
                            AppResult.Success(
                                upload.copy(
                                    step = DocumentUploadStep.Finished(progress),
                                    attempts = 0,
                                    nextAttemptAt = null,
                                    failure = null,
                                )
                            )
                    }
                }
            is DocumentUploadStep.Finished -> AppResult.Success(upload)
        }
    }

    private suspend fun sendChunk(
        workspaceId: String,
        access: DocumentAccess,
        file: LocalDocumentFile,
        step: DocumentUploadStep.Chunk,
        policy: CompanyPolicySnapshot,
    ): AppResult<DocumentUpload> {
        val upload = requireNotNull(file.upload)
        val intent = upload.intent
        val chunk =
            when (val result = files.chunk(access, file, step.offset)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return try {
            currentCoroutineContext().ensureActive()
            val ready = beforeTransport(workspaceId, access, policy)
            if (ready is AppResult.Failed) return ready
            val nextOffset = step.offset + chunk.bytes.size
            if (
                chunk.bytes.size !=
                    minOf(
                            DOCUMENT_UPLOAD_CHUNK_BYTES.toLong(),
                            file.draft.declaration.size - step.offset,
                        )
                        .toInt()
            )
                return documentFailure("client.document_file_changed", FailureKind.Storage)
            val next =
                if (nextOffset == file.draft.declaration.size)
                    DocumentUploadStep.Validate(Uuid.random().toString(), step.version + 1)
                else
                    DocumentUploadStep.Chunk(Uuid.random().toString(), nextOffset, step.version + 1)
            confirmed(
                remote.chunk(access, intent.revisionId, step.operationId, step.offset, chunk),
                upload,
                step.version + 1,
                next,
            )
        } finally {
            chunk.bytes.fill(0)
        }
    }

    private fun confirmed(
        result: AppResult<DocumentMutationReceipt>,
        upload: DocumentUpload,
        version: Long,
        next: DocumentUploadStep,
    ): AppResult<DocumentUpload> =
        when (result) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value.id != upload.intent.revisionId || result.value.version != version)
                    documentFailure("client.invalid_response", FailureKind.Unexpected)
                else
                    AppResult.Success(
                        upload.copy(step = next, attempts = 0, nextAttemptAt = null, failure = null)
                    )
        }

    private suspend fun persist(
        access: DocumentAccess,
        file: LocalDocumentFile,
        upload: DocumentUpload,
    ): AppResult<LocalDocumentFile> =
        when (val saved = files.updateUpload(access, file, upload)) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                if (saved.value)
                    AppResult.Success(file.copy(revision = file.revision + 1, upload = upload))
                else documentFailure("client.document_file_changed", FailureKind.Conflict)
        }

    private suspend fun currentPolicy(
        workspaceId: String,
        access: DocumentAccess,
    ): AppResult<CompanyPolicySnapshot> {
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val continued = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (continued is AppResult.Failed) return continued
        return when (response) {
            is AppResult.Failed -> response
            is AppResult.Success -> {
                val policy = CompanyPolicySnapshot(response.value, requestedAt)
                when (
                    val available =
                        admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
                ) {
                    is AppResult.Failed -> available
                    is AppResult.Success -> AppResult.Success(policy)
                }
            }
        }
    }

    private suspend fun beforeTransport(
        workspaceId: String,
        access: DocumentAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val ownership = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        return admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
    }

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

    private fun retryLimit() =
        Failure(
            FailureKind.Conflict,
            "Check the document status before continuing.",
            code = "client.document_retry_limit",
        )

    private fun waiting() =
        Failure(
            FailureKind.Service,
            "Document delivery will resume shortly.",
            code = "client.document_upload_waiting",
        )
}
