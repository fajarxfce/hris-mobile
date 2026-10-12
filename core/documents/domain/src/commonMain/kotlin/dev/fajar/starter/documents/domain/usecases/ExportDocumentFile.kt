@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/**
 * Explicit foreground disclosure. The picker selects an empty destination before fresh admission.
 */
class ExportDocumentFile(
    private val files: DocumentFileRepository,
    private val content: DocumentContentRepository,
    private val outputs: DocumentOutputRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, fileId: String): AppResult<Boolean> =
        withTimeoutOrNull(7.minutes) {
            if (Uuid.parseOrNull(fileId)?.toString() != fileId) return@withTimeoutOrNull changed()
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val file =
                when (val result = files.find(access, fileId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value ?: return@withTimeoutOrNull changed()
                }
            currentCoroutineContext().ensureActive()
            val origin = file.draft.download ?: return@withTimeoutOrNull changed()
            val valid = validateDocumentDownload(origin)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            val evidence = origin.evidence
            if (
                file.upload != null ||
                    file.sha256 != evidence.sha256 ||
                    file.storedBytes != evidence.size ||
                    file.draft.declaration !=
                        DocumentFileDeclaration(
                            evidence.fileName,
                            evidence.mediaType,
                            evidence.size,
                        )
            )
                return@withTimeoutOrNull changed()
            val beforeSelection = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (beforeSelection is AppResult.Failed) return@withTimeoutOrNull beforeSelection
            val requestId = Uuid.random().toString()
            try {
                val selected =
                    withTimeoutOrNull(5.minutes) {
                        outputs.select(requestId, file.draft.declaration)
                    }
                        ?: return@withTimeoutOrNull documentFailure(
                            "client.document_selection_timeout",
                            FailureKind.Timeout,
                        )
                currentCoroutineContext().ensureActive()
                val continuation = validateDocumentContinuation(access, currentAccess(workspaceId))
                if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
                when (selected) {
                    is AppResult.Failed -> return@withTimeoutOrNull selected
                    is AppResult.Success ->
                        if (!selected.value) return@withTimeoutOrNull AppResult.Success(false)
                }
                // A save dialog can outlive the policy used to show the page. No plaintext has
                // left the encrypted repository yet. Recheck resource access after the dialog.
                withTimeoutOrNull(2.minutes) { copy(workspaceId, access, file, requestId) }
                    ?: documentFailure("client.document_export_timeout", FailureKind.Timeout)
            } finally {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5.seconds) { outputs.release(requestId) }
                }
            }
        } ?: documentFailure("client.document_export_timeout", FailureKind.Timeout)

    private suspend fun copy(
        workspaceId: String,
        access: DocumentAccess,
        file: LocalDocumentFile,
        requestId: String,
    ): AppResult<Boolean> {
        val evidence = requireNotNull(file.draft.download).evidence
        var policy =
            when (val result = acquirePolicy(workspaceId, access, evidence)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val parts =
            ((evidence.size + DOCUMENT_DOWNLOAD_CHUNK_BYTES - 1) / DOCUMENT_DOWNLOAD_CHUNK_BYTES)
                .toInt()
        var offset = 0L
        repeat(parts) {
            if (clock.now() >= companyPolicyDeadline(policy)) {
                policy =
                    when (val result = acquirePolicy(workspaceId, access, evidence)) {
                        is AppResult.Failed -> return result
                        is AppResult.Success -> result.value
                    }
            }
            val before = admitted(workspaceId, access, policy)
            if (before is AppResult.Failed) return before
            val acquired = files.chunk(access, file, offset)
            when (acquired) {
                is AppResult.Failed -> {
                    currentCoroutineContext().ensureActive()
                    val continued = admitted(workspaceId, access, policy)
                    return if (continued is AppResult.Failed) continued else acquired
                }
                is AppResult.Success -> {
                    val bytes = acquired.value.bytes
                    try {
                        currentCoroutineContext().ensureActive()
                        val continued = admitted(workspaceId, access, policy)
                        if (continued is AppResult.Failed) return continued
                        val expected =
                            minOf(DOCUMENT_DOWNLOAD_CHUNK_BYTES.toLong(), evidence.size - offset)
                        if (bytes.size.toLong() != expected) return changed()
                        val written = outputs.write(requestId, offset, bytes)
                        currentCoroutineContext().ensureActive()
                        val after = admitted(workspaceId, access, policy)
                        if (after is AppResult.Failed) return after
                        if (written is AppResult.Failed) return written
                        offset += expected
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }
        if (offset != evidence.size) return changed()
        // Final content authorization also protects browser streams, which commit on completion.
        val finalPolicy =
            when (val result = acquirePolicy(workspaceId, access, evidence)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val completed = outputs.complete(requestId)
        currentCoroutineContext().ensureActive()
        val publication = admitted(workspaceId, access, finalPolicy)
        if (publication is AppResult.Failed) return publication
        return when (completed) {
            is AppResult.Failed -> completed
            is AppResult.Success -> AppResult.Success(true)
        }
    }

    private suspend fun acquirePolicy(
        workspaceId: String,
        access: DocumentAccess,
        evidence: DocumentEvidence,
    ): AppResult<CompanyPolicySnapshot> {
        val before = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (before is AppResult.Failed) return before
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val ownership = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val policy =
            when (response) {
                is AppResult.Failed -> return response
                is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
            }
        val available = admitted(workspaceId, access, policy)
        if (available is AppResult.Failed) return available
        val inspected = content.inspect(access, evidence)
        currentCoroutineContext().ensureActive()
        val continued = admitted(workspaceId, access, policy)
        if (continued is AppResult.Failed) return continued
        return when (inspected) {
            is AppResult.Failed -> inspected
            is AppResult.Success -> AppResult.Success(policy)
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
