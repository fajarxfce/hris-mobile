@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.repositories.DocumentFileRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

class RemoveDocumentFile(
    private val files: DocumentFileRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, id: String, revision: Long): AppResult<Unit> =
        withTimeoutOrNull(10.seconds) {
            if (Uuid.parseOrNull(id)?.toString() != id || revision < 0)
                return@withTimeoutOrNull documentFailure("client.document_file_invalid")
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val file =
                when (val result = files.find(access, id)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val admission = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (admission is AppResult.Failed) return@withTimeoutOrNull admission
            if (file == null) return@withTimeoutOrNull AppResult.Success(Unit)
            if (file.revision != revision)
                return@withTimeoutOrNull documentFailure(
                    "client.document_file_changed",
                    FailureKind.Conflict,
                )
            if (!canRemoveDocumentFile(file))
                return@withTimeoutOrNull documentFailure(
                    "client.document_upload_active",
                    FailureKind.Conflict,
                )
            val removed = files.remove(access, id, revision)
            currentCoroutineContext().ensureActive()
            val publication = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            when (removed) {
                is AppResult.Failed -> removed
                is AppResult.Success ->
                    if (removed.value) AppResult.Success(Unit)
                    else documentFailure("client.document_file_changed", FailureKind.Conflict)
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
        return admitDocumentCatalog(workspaceId, workspace, latest, build, clock.now())
    }
}
