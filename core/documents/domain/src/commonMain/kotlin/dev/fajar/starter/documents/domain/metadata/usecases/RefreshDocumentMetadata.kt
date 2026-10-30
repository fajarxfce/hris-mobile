@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.mergeDocumentMetadataPage
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.policies.documentCatalogFailureClosesRead
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One finite acquisition. Observing or refreshing never sends a business correction. */
class RefreshDocumentMetadata(
    private val remote: DocumentMetadataRepository,
    private val directory: DocumentRepository,
    private val cache: DocumentMetadataCacheRepository,
    private val admission: DocumentMetadataAdmissionRepository,
    private val catalog: DocumentCatalogRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex,
) {
    suspend operator fun invoke(
        workspaceId: String,
        documentId: String,
        after: Long? = null,
    ): AppResult<Unit> =
        withTimeoutOrNull(30.seconds) {
            if (
                Uuid.parseOrNull(documentId)?.toString() != documentId ||
                    (after != null && after !in 0..999)
            )
                return@withTimeoutOrNull documentFailure("client.document_metadata_invalid")
            execution.withLock { acquire(workspaceId, documentId, after) }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun acquire(
        workspaceId: String,
        documentId: String,
        after: Long?,
    ): AppResult<Unit> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val observedAdmission =
            when (val result = admission.current(access, documentId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val previous =
            when (val result = cache.checkpoint(access, documentId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val before = continueAccess(workspaceId, access)
        if (before is AppResult.Failed) return before
        if ((after ?: -1L) > (previous?.historyThrough ?: -1L))
            return documentFailure("client.document_metadata_history_gap", FailureKind.Conflict)
        val requestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        val policyOwner = continueAccess(workspaceId, access)
        if (policyOwner is AppResult.Failed) return policyOwner
        val policy =
            when (policyResponse) {
                is AppResult.Failed ->
                    return reject(access, documentId, policyResponse, global = true)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val ready = admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
        if (ready is AppResult.Failed) return reject(access, documentId, ready, global = true)
        val content = loadContent(workspaceId, access, documentId, after, policy)
        if (content is AppResult.Failed) return content
        val (head, page) = (content as AppResult.Success).value
        val merged =
            mergeDocumentMetadataPage(
                previous,
                head,
                page,
                after,
                clock.now(),
                Uuid.random().toString(),
            )
        if (merged is AppResult.Failed) return merged
        return publish(
            workspaceId,
            access,
            observedAdmission,
            previous,
            (merged as AppResult.Success).value,
            page.items,
            policy,
        )
    }

    private suspend fun loadContent(
        workspaceId: String,
        access: DocumentAccess,
        documentId: String,
        after: Long?,
        policy: CompanyPolicySnapshot,
    ): AppResult<Pair<DocumentMetadataHead, DocumentMetadataPage>> {
        val response = remote.head(access, documentId)
        val headOwner = continueRead(workspaceId, access, documentId, policy)
        if (headOwner is AppResult.Failed) return headOwner
        val head =
            when (response) {
                is AppResult.Failed -> return reject(access, documentId, response)
                is AppResult.Success -> response.value
            }
        if (head.id != documentId)
            return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
        val owned = ownEmployment(workspaceId, access, head, policy)
        val employmentOwner = continueAccess(workspaceId, access)
        if (employmentOwner is AppResult.Failed) return employmentOwner
        if (owned is AppResult.Failed) return reject(access, documentId, owned)
        val history =
            if (head.metadataSupported) remote.history(access, documentId, after)
            else AppResult.Success(DocumentMetadataPage(emptyList(), null))
        val historyOwner = continueRead(workspaceId, access, documentId, policy)
        if (historyOwner is AppResult.Failed) return historyOwner
        val page =
            when (history) {
                is AppResult.Failed -> return reject(access, documentId, history)
                is AppResult.Success -> history.value
            }
        return AppResult.Success(head to page)
    }

    private suspend fun publish(
        workspaceId: String,
        access: DocumentAccess,
        observedAdmission: DocumentMetadataReadAdmission,
        previous: DocumentMetadataCheckpoint?,
        next: DocumentMetadataCheckpoint,
        rows: List<DocumentMetadataRevision>,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val saved = cache.commit(access, previous, next, rows)
        val savedOwner = continueRead(workspaceId, access, next.head.id, policy)
        if (savedOwner is AppResult.Failed) return savedOwner
        if (saved is AppResult.Failed) return saved
        if (!(saved as AppResult.Success).value)
            return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
        val admitted = admission.admit(access, next.head.id, observedAdmission.revision)
        val finalOwner = continueRead(workspaceId, access, next.head.id, policy)
        if (finalOwner is AppResult.Failed) {
            // Clearing a known denial also needs admitted completion, even if durable I/O waited.
            val priorDenial = observedAdmission.denial
            if (priorDenial != null && continueAccess(workspaceId, access) is AppResult.Success)
                reject(access, next.head.id, AppResult.Failed(priorDenial))
            return finalOwner
        }
        if (admitted is AppResult.Failed) return admitted
        return if ((admitted as AppResult.Success).value) AppResult.Success(Unit)
        else documentFailure("client.document_metadata_changed", FailureKind.Conflict)
    }

    private suspend fun ownEmployment(
        workspaceId: String,
        access: DocumentAccess,
        head: DocumentMetadataHead,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        var after: String? = null
        val cursors = mutableSetOf<String>()
        repeat(10) {
            val response = directory.employments(access, after)
            val owner = continueRead(workspaceId, access, head.id, policy)
            if (owner is AppResult.Failed) return owner
            val page =
                when (response) {
                    is AppResult.Failed -> return response
                    is AppResult.Success -> response.value
                }
            if (page.metadataSupported != head.metadataSupported)
                return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
            if (page.items.any { it.id == head.employmentId }) return AppResult.Success(Unit)
            val next =
                page.nextCursor
                    ?: return documentFailure("document_access_denied", FailureKind.AccessDenied)
            if (!cursors.add(next))
                return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
            after = next
        }
        return documentFailure("client.document_employment_limit", FailureKind.Unavailable)
    }

    private suspend fun reject(
        access: DocumentAccess,
        documentId: String,
        failure: AppResult.Failed,
        global: Boolean = false,
    ): AppResult.Failed {
        if (
            documentCatalogFailureClosesRead(failure.failure) ||
                failure.failure.code == "document_not_found"
        ) {
            withTimeoutOrNull(2.seconds) { admission.restrict(access, documentId, failure.failure) }
            if (global || failure.failure.code in workspaceAccessLossCodes)
                withTimeoutOrNull(2.seconds) { catalog.restrict(access, failure.failure) }
        }
        return failure
    }

    private suspend fun continueAccess(
        workspaceId: String,
        access: DocumentAccess,
    ): AppResult<DocumentAccess> {
        currentCoroutineContext().ensureActive()
        return validateDocumentContinuation(access, currentAccess(workspaceId))
    }

    private suspend fun continueRead(
        workspaceId: String,
        access: DocumentAccess,
        documentId: String,
        policy: CompanyPolicySnapshot,
    ): AppResult<DocumentAccess> {
        val owner = continueAccess(workspaceId, access)
        if (owner is AppResult.Failed) return owner
        val available = admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
        if (available is AppResult.Failed)
            return reject(access, documentId, available, global = true)
        return owner
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
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitDocumentCatalog(workspaceId, workspace, latest, build, clock.now())
    }
}
