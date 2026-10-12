@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

class ImportDocumentFile(
    private val inputs: DocumentInputRepository,
    private val files: DocumentFileRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String): AppResult<LocalDocumentFile?> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val id = Uuid.random().toString()
        try {
            val selected =
                withTimeoutOrNull(5.minutes) { inputs.select(id, DOCUMENT_INPUT_TYPES) }
                    ?: return documentFailure(
                        "client.document_selection_timeout",
                        FailureKind.Timeout,
                    )
            currentCoroutineContext().ensureActive()
            val admission = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (admission is AppResult.Failed) return admission
            val input =
                when (selected) {
                    is AppResult.Failed -> return selected
                    is AppResult.Success -> selected.value ?: return AppResult.Success(null)
                }
            val declaration =
                when (val result = resolveDocumentFile(input)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            val imported =
                withTimeoutOrNull(2.minutes) {
                    files.import(
                        access,
                        DocumentFileDraft(id, declaration, clock.now()),
                        DOCUMENT_LOCAL_CAPACITY,
                        DOCUMENT_LOCAL_FILES,
                    )
                } ?: return documentFailure("client.document_import_timeout", FailureKind.Timeout)
            currentCoroutineContext().ensureActive()
            val publication = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return publication
            return when (imported) {
                is AppResult.Failed -> imported
                is AppResult.Success ->
                    when (val outcome = imported.value) {
                        is DocumentFileReservation.Stored -> AppResult.Success(outcome.file)
                        DocumentFileReservation.CapacityExceeded ->
                            documentFailure("client.document_storage_full", FailureKind.Storage)
                        DocumentFileReservation.ScopeChanged ->
                            documentFailure(
                                "client.document_scope_changed",
                                FailureKind.AccessDenied,
                            )
                        DocumentFileReservation.Conflict ->
                            documentFailure("client.document_import_conflict", FailureKind.Conflict)
                    }
            }
        } finally {
            // IDs are allocated before selection, so cleanup still addresses a lost result.
            // Neither cleanup can delete a completed file or silently retry an operation.
            withContext(NonCancellable) {
                withTimeoutOrNull(5.seconds) { inputs.release(id) }
                withTimeoutOrNull(5.seconds) { files.discardIncomplete(access, id) }
            }
        }
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
}
