@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.commands.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.*
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.repositories.*
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

/** Explicit confirmation retains a command. It never performs HTTP or automatic delivery. */
class PrepareDocumentMetadataCommand(
    private val commands: DocumentMetadataCommandRepository,
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
        observed: DocumentMetadataView,
        input: DocumentMetadataInput,
        reason: String,
    ): AppResult<DocumentMetadataCommandRecord> =
        withTimeoutOrNull(10.seconds) {
            execution.withLock { prepare(workspaceId, observed, input, reason) }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun prepare(
        workspaceId: String,
        observed: DocumentMetadataView,
        input: DocumentMetadataInput,
        reason: String,
    ): AppResult<DocumentMetadataCommandRecord> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val command =
            when (
                val result =
                    prepareDocumentMetadataCommand(
                        access,
                        observed,
                        input,
                        reason,
                        clock.now(),
                        Uuid.random().toString(),
                    )
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val allowed = admitted(workspaceId, access, command.change.documentId)
        if (allowed is AppResult.Failed) return allowed
        val checkpoint = cache.checkpoint(access, command.change.documentId)
        val beforeInsert = admitted(workspaceId, access, command.change.documentId)
        if (beforeInsert is AppResult.Failed) return beforeInsert
        when (checkpoint) {
            is AppResult.Failed -> return checkpoint
            is AppResult.Success ->
                if (checkpoint.value?.head != observed.snapshot.checkpoint?.head)
                    return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
        }
        val inserted = commands.insert(access, command)
        val owner = admitted(workspaceId, access, command.change.documentId)
        if (owner is AppResult.Failed) return owner
        when (inserted) {
            is AppResult.Failed -> return inserted
            is AppResult.Success ->
                when (inserted.value) {
                    DocumentMetadataCommandAdmission.STORED -> Unit
                    DocumentMetadataCommandAdmission.OCCUPIED ->
                        return documentFailure(
                            "client.document_command_occupied",
                            FailureKind.Conflict,
                        )
                    DocumentMetadataCommandAdmission.CAPACITY_EXCEEDED ->
                        return documentFailure("client.command_queue_full", FailureKind.Storage)
                    DocumentMetadataCommandAdmission.SCOPE_CHANGED ->
                        return documentFailure(
                            "client.document_scope_changed",
                            FailureKind.AccessDenied,
                        )
                }
        }
        val stored = commands.find(access, command.change.operationId)
        val finalOwner = admitted(workspaceId, access, command.change.documentId)
        if (finalOwner is AppResult.Failed) return finalOwner
        return when (stored) {
            is AppResult.Failed -> stored
            is AppResult.Success ->
                if (stored.value?.command == command)
                    AppResult.Success(requireNotNull(stored.value))
                else documentFailure("client.document_command_changed", FailureKind.Conflict)
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
