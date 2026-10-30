package dev.fajar.starter.documents.domain.metadata.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import dev.fajar.starter.documents.domain.metadata.working.policies.*
import dev.fajar.starter.documents.domain.metadata.working.repositories.DocumentMetadataWorkingRepository
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DiscardDocumentMetadataWorkingCopy(
    private val copies: DocumentMetadataWorkingRepository,
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
        expected: DocumentMetadataWorkingCopy,
    ): AppResult<Unit> =
        withTimeoutOrNull(10.seconds) { execution.withLock { discard(workspaceId, expected) } }
            ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun discard(
        workspaceId: String,
        expected: DocumentMetadataWorkingCopy,
    ): AppResult<Unit> {
        val valid = validateDocumentMetadataWorkingCopy(expected)
        if (valid is AppResult.Failed) return valid
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val id = expected.head.id
        val allowed = admitted(workspaceId, access, id)
        if (allowed is AppResult.Failed) return allowed
        val stored = copies.current(access, id)
        val loaded = admitted(workspaceId, access, id)
        if (loaded is AppResult.Failed) return loaded
        when (stored) {
            is AppResult.Failed -> return stored
            is AppResult.Success -> {
                if (stored.value == null) return AppResult.Success(Unit)
                if (stored.value != expected)
                    return documentFailure(
                        "client.document_working_copy_changed",
                        FailureKind.Conflict,
                    )
            }
        }
        val removed = copies.remove(access, expected)
        val owner = admitted(workspaceId, access, id)
        if (owner is AppResult.Failed) return owner
        if (removed is AppResult.Failed) return removed
        val current = copies.current(access, id)
        val finalOwner = admitted(workspaceId, access, id)
        if (finalOwner is AppResult.Failed) return finalOwner
        return when (current) {
            is AppResult.Failed -> current
            is AppResult.Success ->
                if (current.value == null) AppResult.Success(Unit)
                else documentFailure("client.document_working_copy_changed", FailureKind.Conflict)
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
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitDocumentCatalog(workspaceId, workspace, latest, build, clock.now())
    }

    private suspend fun admitted(
        workspaceId: String,
        access: DocumentAccess,
        documentId: String,
    ): AppResult<Unit> {
        val module = catalog.admission(access)
        currentCoroutineContext().ensureActive()
        val moduleOwner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (moduleOwner is AppResult.Failed) return moduleOwner
        when (module) {
            is AppResult.Failed -> return module
            is AppResult.Success ->
                module.value.denial?.let {
                    return AppResult.Failed(it)
                }
        }
        val resource = admission.current(access, documentId)
        currentCoroutineContext().ensureActive()
        val resourceOwner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (resourceOwner is AppResult.Failed) return resourceOwner
        return when (resource) {
            is AppResult.Failed -> resource
            is AppResult.Success ->
                resource.value.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
    }
}
