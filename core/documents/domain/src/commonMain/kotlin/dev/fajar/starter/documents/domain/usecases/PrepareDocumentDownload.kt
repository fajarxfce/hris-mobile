@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogSnapshot
import dev.fajar.starter.documents.domain.replica.policies.sameStoredDocumentScope
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Freezes an admitted catalog selection before content transfer. No OS file is opened here. */
class PrepareDocumentDownload(
    private val files: DocumentFileRepository,
    private val content: DocumentContentRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex,
) {
    suspend operator fun invoke(
        workspaceId: String,
        catalog: DocumentCatalogSnapshot,
        revisionId: String,
    ): AppResult<LocalDocumentFile> =
        withTimeoutOrNull(30.seconds) {
            execution.withLock {
                val access =
                    when (val result = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val selection =
                    when (val result = validateSelection(catalog, revisionId, access)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val requestedAt = clock.now()
                val response = workspaces.clientPolicy(access.sessionId, access.companyId)
                currentCoroutineContext().ensureActive()
                val admission = validateDocumentContinuation(access, currentAccess(workspaceId))
                if (admission is AppResult.Failed) return@withLock admission
                val policy =
                    when (response) {
                        is AppResult.Failed -> return@withLock response
                        is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
                    }
                val available =
                    admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
                if (available is AppResult.Failed) return@withLock available
                val inspected = content.inspect(access, selection.evidence)
                currentCoroutineContext().ensureActive()
                val admitted = beforeReservation(workspaceId, access, policy)
                if (admitted is AppResult.Failed) return@withLock admitted
                if (inspected is AppResult.Failed) return@withLock inspected
                val rows = files.observe(access).first()
                currentCoroutineContext().ensureActive()
                val continuation = beforeReservation(workspaceId, access, policy)
                if (continuation is AppResult.Failed) return@withLock continuation
                val existing =
                    when (rows) {
                        is AppResult.Failed -> return@withLock rows
                        is AppResult.Success ->
                            rows.value.firstOrNull { it.draft.download == selection }
                    }
                if (existing != null) return@withLock AppResult.Success(existing)
                // A new reservation gets a fresh ID even after an older copy was removed. An old
                // writer therefore cannot complete a replacement that reused the same storage
                // revision.
                val draft =
                    DocumentFileDraft(
                        Uuid.random().toString(),
                        DocumentFileDeclaration(
                            selection.evidence.fileName,
                            selection.evidence.mediaType,
                            selection.evidence.size,
                        ),
                        clock.now(),
                        selection,
                    )
                val saved =
                    files.reserveDownload(
                        access,
                        draft,
                        DOCUMENT_LOCAL_CAPACITY,
                        DOCUMENT_LOCAL_FILES,
                    )
                currentCoroutineContext().ensureActive()
                val publication = beforeReservation(workspaceId, access, policy)
                if (publication is AppResult.Failed) return@withLock publication
                when (saved) {
                    is AppResult.Failed -> saved
                    is AppResult.Success ->
                        when (val outcome = saved.value) {
                            is DocumentFileReservation.Stored -> AppResult.Success(outcome.file)
                            DocumentFileReservation.CapacityExceeded ->
                                documentFailure(
                                    "client.document_storage_full",
                                    FailureKind.Conflict,
                                )
                            DocumentFileReservation.Conflict ->
                                documentFailure(
                                    "client.document_file_changed",
                                    FailureKind.Conflict,
                                )
                            DocumentFileReservation.ScopeChanged ->
                                documentFailure(
                                    "client.document_scope_changed",
                                    FailureKind.AccessDenied,
                                )
                        }
                }
            }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private fun validateSelection(
        catalog: DocumentCatalogSnapshot,
        revisionId: String,
        access: DocumentAccess,
    ): AppResult<DocumentDownloadSource> {
        val checkpoint =
            catalog.checkpoint ?: return documentFailure("client.document_selection_invalid")
        if (!checkpoint.published) return documentFailure("client.document_selection_invalid")
        if (!sameStoredDocumentScope(checkpoint.access, access))
            return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
        val employmentId =
            catalog.selection.employmentId
                ?: return documentFailure("client.document_selection_invalid")
        if (checkpoint.employments.none { it.id == employmentId })
            return documentFailure("client.document_selection_invalid")
        val evidence =
            catalog.page.items.singleOrNull { it.evidence.revisionId == revisionId }?.evidence
                ?: return documentFailure("client.document_selection_invalid")
        val selection =
            DocumentDownloadSource(employmentId, catalog.selection.classification, evidence)
        return when (val valid = validateDocumentDownload(selection)) {
            is AppResult.Failed -> valid
            is AppResult.Success -> AppResult.Success(selection)
        }
    }

    private suspend fun beforeReservation(
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
}
