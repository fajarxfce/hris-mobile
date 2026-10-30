package dev.fajar.starter.documents.domain.metadata.commands.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.*
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.repositories.*
import dev.fajar.starter.documents.domain.metadata.working.policies.matchesAcknowledgedMetadataCommand
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

/** Discard unsent/rejected input or acknowledge a receipt after current facts have caught up. */
class ReviewDocumentMetadataCommand(
    private val commands: DocumentMetadataCommandRepository,
    private val copies: DocumentMetadataWorkingRepository,
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
        expected: DocumentMetadataCommandRecord,
    ): AppResult<Boolean> =
        withTimeoutOrNull(10.seconds) { execution.withLock { review(workspaceId, expected) } }
            ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun review(
        workspaceId: String,
        expected: DocumentMetadataCommandRecord,
    ): AppResult<Boolean> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!expected.command.belongsTo(access))
            return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
        if (expected.outcome is DocumentMetadataCommandOutcome.Unconfirmed)
            return documentFailure("client.document_command_unconfirmed", FailureKind.Conflict)
        val id = expected.command.change.documentId
        val allowed = admitted(workspaceId, access, id)
        if (allowed is AppResult.Failed) return allowed
        val stored = commands.find(access, expected.command.change.operationId)
        val loaded = admitted(workspaceId, access, id)
        if (loaded is AppResult.Failed) return loaded
        when (stored) {
            is AppResult.Failed -> return stored
            is AppResult.Success -> if (stored.value != expected) return AppResult.Success(false)
        }
        val outcome = expected.outcome
        if (outcome is DocumentMetadataCommandOutcome.Confirmed) {
            val checkpoint = cache.checkpoint(access, id)
            val acquired = admitted(workspaceId, access, id)
            if (acquired is AppResult.Failed) return acquired
            when (checkpoint) {
                is AppResult.Failed -> return checkpoint
                is AppResult.Success -> {
                    val head = checkpoint.value?.head
                    if (
                        head == null ||
                            !head.metadataSupported ||
                            head.metadata == null ||
                            head.id != id ||
                            head.employmentId != expected.command.employmentId ||
                            head.version < outcome.version
                    )
                        return documentFailure(
                            "client.document_updates_pending",
                            FailureKind.Conflict,
                        )
                }
            }
        }
        if (outcome is DocumentMetadataCommandOutcome.Confirmed) {
            val cleaned = removeMatchingDraft(workspaceId, access, expected.command)
            if (cleaned is AppResult.Failed) return cleaned
        }
        val removed = commands.remove(access, expected)
        val finalOwner = admitted(workspaceId, access, id)
        return if (finalOwner is AppResult.Failed) finalOwner else removed
    }

    private suspend fun removeMatchingDraft(
        workspaceId: String,
        access: DocumentAccess,
        command: DocumentMetadataCommand,
    ): AppResult<Unit> {
        val stored = copies.current(access, command.change.documentId)
        val owner = admitted(workspaceId, access, command.change.documentId)
        if (owner is AppResult.Failed) return owner
        val copy =
            when (stored) {
                is AppResult.Failed -> return stored
                is AppResult.Success -> stored.value ?: return AppResult.Success(Unit)
            }
        if (!matchesAcknowledgedMetadataCommand(copy, command)) return AppResult.Success(Unit)
        val removed = copies.remove(access, copy)
        val finalOwner = admitted(workspaceId, access, command.change.documentId)
        if (finalOwner is AppResult.Failed) return finalOwner
        // A CAS miss preserves a newer draft; this command's receipt remains acknowledged.
        return if (removed is AppResult.Failed) removed else AppResult.Success(Unit)
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
