@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.companyPolicyDeadline
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A foreground transfer. Cancellation retains only committed encrypted blocks for explicit resume.
 */
class DownloadDocumentFile(
    private val files: DocumentFileRepository,
    private val content: DocumentContentRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex,
) {
    suspend operator fun invoke(workspaceId: String, fileId: String): AppResult<LocalDocumentFile> =
        withTimeoutOrNull(120.seconds) {
            execution.withLock {
                if (Uuid.parseOrNull(fileId)?.toString() != fileId) return@withLock changed()
                val access =
                    when (val result = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val file =
                    when (val result = files.find(access, fileId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value ?: return@withLock changed()
                    }
                currentCoroutineContext().ensureActive()
                val source = file.draft.download ?: return@withLock changed()
                val valid = validateDocumentDownload(source)
                if (valid is AppResult.Failed) return@withLock valid
                if (
                    file.upload != null ||
                        file.storedBytes !in 0..source.evidence.size ||
                        file.draft.declaration.size != source.evidence.size
                )
                    return@withLock changed()
                val policy =
                    when (val result = acquirePolicy(workspaceId, access)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val inspected = content.inspect(access, source.evidence)
                currentCoroutineContext().ensureActive()
                val continued = admitted(workspaceId, access, policy)
                if (continued is AppResult.Failed) return@withLock continued
                if (inspected is AppResult.Failed) return@withLock inspected
                if (file.sha256 != null)
                    return@withLock if (
                        file.sha256 == source.evidence.sha256 &&
                            file.storedBytes == source.evidence.size
                    )
                        AppResult.Success(file)
                    else changed()
                transfer(workspaceId, access, file, policy)
            }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun transfer(
        workspaceId: String,
        access: DocumentAccess,
        initial: LocalDocumentFile,
        initialPolicy: CompanyPolicySnapshot,
    ): AppResult<LocalDocumentFile> {
        val evidence = requireNotNull(initial.draft.download).evidence
        var file = initial
        var policy = initialPolicy
        val parts =
            ((evidence.size - file.storedBytes + DOCUMENT_DOWNLOAD_CHUNK_BYTES - 1) /
                    DOCUMENT_DOWNLOAD_CHUNK_BYTES)
                .toInt()
        repeat(parts) {
            // Renew normal admission expiry before a new range; errors never start a retry loop.
            if (clock.now() >= companyPolicyDeadline(policy)) {
                policy =
                    when (val result = acquirePolicy(workspaceId, access)) {
                        is AppResult.Failed -> return result
                        is AppResult.Success -> result.value
                    }
            }
            val before = admitted(workspaceId, access, policy)
            if (before is AppResult.Failed) return before
            val response = content.chunk(access, evidence, file.storedBytes)
            when (response) {
                is AppResult.Failed -> {
                    currentCoroutineContext().ensureActive()
                    val continuation = admitted(workspaceId, access, policy)
                    return if (continuation is AppResult.Failed) continuation else response
                }
                is AppResult.Success -> {
                    val bytes = response.value
                    try {
                        currentCoroutineContext().ensureActive()
                        val continuation = admitted(workspaceId, access, policy)
                        if (continuation is AppResult.Failed) return continuation
                        val expected =
                            minOf(
                                DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(),
                                evidence.size - file.storedBytes,
                            )
                        if (bytes.size.toLong() != expected) return changed()
                        val saved = files.appendDownload(access, file, bytes)
                        currentCoroutineContext().ensureActive()
                        val publication = admitted(workspaceId, access, policy)
                        if (publication is AppResult.Failed) return publication
                        file =
                            when (saved) {
                                is AppResult.Failed -> return saved
                                is AppResult.Success -> {
                                    val next = saved.value ?: return changed()
                                    if (
                                        next.draft != initial.draft ||
                                            next.upload != null ||
                                            next.sha256 != null ||
                                            next.storedBytes != file.storedBytes + expected ||
                                            next.revision <= file.revision
                                    )
                                        return changed()
                                    next
                                }
                            }
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }
        if (file.storedBytes != evidence.size) return changed()
        val beforeCompletion = admitted(workspaceId, access, policy)
        if (beforeCompletion is AppResult.Failed) return beforeCompletion
        val completed = files.completeDownload(access, file)
        currentCoroutineContext().ensureActive()
        val publication = admitted(workspaceId, access, policy)
        if (publication is AppResult.Failed) return publication
        return when (completed) {
            is AppResult.Failed -> completed
            is AppResult.Success ->
                when (val outcome = completed.value) {
                    DocumentFileCompletion.Changed -> changed()
                    DocumentFileCompletion.ChecksumMismatch ->
                        documentFailure("client.document_checksum_mismatch", FailureKind.Storage)
                    is DocumentFileCompletion.Ready -> {
                        val ready = outcome.file
                        if (
                            ready.draft != initial.draft ||
                                ready.storedBytes != evidence.size ||
                                ready.sha256 != evidence.sha256
                        )
                            changed()
                        else AppResult.Success(ready)
                    }
                }
        }
    }

    private suspend fun acquirePolicy(
        workspaceId: String,
        access: DocumentAccess,
    ): AppResult<CompanyPolicySnapshot> {
        val before = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (before is AppResult.Failed) return before
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val ownership = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
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

    private suspend fun admitted(
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
        return admitDocumentCatalog(workspaceId, workspace, latest, build, clock.now())
    }

    private fun changed() = documentFailure("client.document_file_changed", FailureKind.Conflict)
}
