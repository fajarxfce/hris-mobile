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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Acquires the employee's own references and a fresh, expiring upload confirmation context. */
class LoadDocumentUploadPreparation(
    private val files: DocumentFileRepository,
    private val remote: DocumentRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        fileId: String,
        after: String? = null,
    ): AppResult<DocumentUploadPreparation> =
        withTimeoutOrNull(30.seconds) {
            if (
                Uuid.parseOrNull(fileId)?.toString() != fileId ||
                    (after != null && after.length !in 1..32)
            )
                return@withTimeoutOrNull documentFailure("client.document_file_invalid")
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val file =
                when (val result = files.find(access, fileId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success ->
                        result.value
                            ?: return@withTimeoutOrNull documentFailure(
                                "client.document_file_changed",
                                FailureKind.Conflict,
                            )
                }
            currentCoroutineContext().ensureActive()
            if (
                file.draft.download != null ||
                    file.sha256 == null ||
                    file.storedBytes != file.draft.declaration.size
            )
                return@withTimeoutOrNull documentFailure("client.document_import_incomplete")
            if (file.upload != null && !isDocumentUploadRejected(file.upload))
                return@withTimeoutOrNull documentFailure(
                    "client.document_upload_active",
                    FailureKind.Conflict,
                )
            val requestedAt = clock.now()
            val response = workspaces.clientPolicy(access.sessionId, access.companyId)
            currentCoroutineContext().ensureActive()
            val continued = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (continued is AppResult.Failed) return@withTimeoutOrNull continued
            val policy =
                when (response) {
                    is AppResult.Failed -> return@withTimeoutOrNull response
                    is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
                }
            val available =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (available is AppResult.Failed) return@withTimeoutOrNull available
            val directory = remote.employments(access, after)
            currentCoroutineContext().ensureActive()
            val publication =
                when (
                    val result = validateDocumentContinuation(access, currentAccess(workspaceId))
                ) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (stillAvailable is AppResult.Failed) return@withTimeoutOrNull stillAvailable
            val maintenance =
                policy.policy.maintenance?.let {
                    policy.requestedAt + (it.startsAt - policy.policy.serverTime)
                }
            val expiry =
                minOf(
                    requestedAt + DOCUMENT_EVIDENCE_PERIOD,
                    publication.authorizationExpiresAt,
                    publication.sessionExpiresAt,
                    companyPolicyDeadline(policy),
                    maintenance ?: publication.authorizationExpiresAt,
                )
            if (expiry <= clock.now())
                return@withTimeoutOrNull documentFailure(
                    "client.document_preparation_expired",
                    FailureKind.Conflict,
                )
            if (
                directory is AppResult.Success &&
                    file.upload?.intent?.input?.metadata != null &&
                    !directory.value.metadataSupported
            )
                return@withTimeoutOrNull documentFailure("client.document_metadata_unsupported")
            when (directory) {
                is AppResult.Failed -> directory
                is AppResult.Success ->
                    AppResult.Success(
                        DocumentUploadPreparation(
                            publication,
                            file,
                            directory.value,
                            after,
                            requestedAt,
                            expiry,
                        )
                    )
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
