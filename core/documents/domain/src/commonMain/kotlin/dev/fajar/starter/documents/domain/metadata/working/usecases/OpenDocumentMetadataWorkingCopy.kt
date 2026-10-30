@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.admitDocumentMetadataEditing
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
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reopen owned input locally; only a new draft takes its baseline from the current admitted header.
 */
class OpenDocumentMetadataWorkingCopy(
    private val copies: DocumentMetadataWorkingRepository,
    private val admission: DocumentMetadataAdmissionRepository,
    private val catalog: DocumentCatalogRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex,
    private val cache: DocumentMetadataCacheRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        documentId: String,
    ): AppResult<DocumentMetadataWorkingCopy> =
        withTimeoutOrNull(10.seconds) {
            if (Uuid.parseOrNull(documentId)?.toString() != documentId)
                return@withTimeoutOrNull documentFailure("client.document_working_copy_invalid")
            execution.withLock { open(workspaceId, documentId) }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun open(
        workspaceId: String,
        id: String,
    ): AppResult<DocumentMetadataWorkingCopy> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val allowed = admitted(workspaceId, access, id)
        if (allowed is AppResult.Failed) return allowed
        val stored = copies.current(access, id)
        val loaded = admitted(workspaceId, access, id)
        if (loaded is AppResult.Failed) return loaded
        when (stored) {
            is AppResult.Failed -> return stored
            is AppResult.Success ->
                stored.value?.let {
                    return checked(id, it)
                }
        }
        val editing = admitDocumentMetadataEditing(access)
        if (editing is AppResult.Failed) return editing
        val found = cache.checkpoint(access, id)
        val owner = admitted(workspaceId, access, id)
        if (owner is AppResult.Failed) return owner
        val head =
            when (found) {
                is AppResult.Failed -> return found
                is AppResult.Success ->
                    found.value?.head
                        ?: return documentFailure(
                            "client.document_updates_pending",
                            FailureKind.Conflict,
                        )
            }
        if (head.id != id)
            return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
        val copy =
            when (
                val result =
                    initialDocumentMetadataWorkingCopy(head, Uuid.random().toString(), clock.now())
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val saved = copies.save(access, null, copy)
        val savedOwner = admitted(workspaceId, access, id)
        if (savedOwner is AppResult.Failed) return savedOwner
        if (saved is AppResult.Failed) return saved
        // A competing creator wins its own exact copy; never overwrite it with the proposed empty
        // one.
        val current = copies.current(access, id)
        val finalOwner = admitted(workspaceId, access, id)
        if (finalOwner is AppResult.Failed) return finalOwner
        return when (current) {
            is AppResult.Failed -> current
            is AppResult.Success ->
                current.value?.let { checked(id, it) }
                    ?: documentFailure("client.document_working_copy_changed", FailureKind.Conflict)
        }
    }

    private fun checked(
        id: String,
        copy: DocumentMetadataWorkingCopy,
    ): AppResult<DocumentMetadataWorkingCopy> {
        if (copy.head.id != id)
            return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
        return when (val valid = validateDocumentMetadataWorkingCopy(copy)) {
            is AppResult.Failed -> valid
            is AppResult.Success -> AppResult.Success(copy)
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
