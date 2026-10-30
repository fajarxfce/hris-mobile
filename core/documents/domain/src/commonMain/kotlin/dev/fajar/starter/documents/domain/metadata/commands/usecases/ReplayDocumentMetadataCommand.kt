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

/** Explicit bounded delivery. The operation, observed version and body are never regenerated. */
class ReplayDocumentMetadataCommand(
    private val commands: DocumentMetadataCommandRepository,
    private val remote: DocumentMetadataRepository,
    private val directory: DocumentRepository,
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
        operationId: String,
    ): AppResult<DocumentMetadataCommandRecord> =
        withTimeoutOrNull(30.seconds) {
            if (Uuid.parseOrNull(operationId)?.toString() != operationId)
                return@withTimeoutOrNull documentFailure(
                    "client.document_command_changed",
                    FailureKind.Conflict,
                )
            execution.withLock { load(workspaceId, operationId) }
        } ?: documentFailure("client.document_timeout", FailureKind.Timeout)

    private suspend fun load(
        workspaceId: String,
        operationId: String,
    ): AppResult<DocumentMetadataCommandRecord> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val found = commands.find(access, operationId)
        val owner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        val record =
            when (found) {
                is AppResult.Failed -> return found
                is AppResult.Success ->
                    found.value
                        ?: return documentFailure(
                            "client.document_command_changed",
                            FailureKind.Conflict,
                        )
            }
        if (record.command.change.operationId != operationId || !record.command.belongsTo(access))
            return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
        val allowed = admitted(workspaceId, access, record.command.change.documentId)
        if (allowed is AppResult.Failed) return allowed
        if (
            record.outcome is DocumentMetadataCommandOutcome.Confirmed ||
                record.outcome is DocumentMetadataCommandOutcome.Rejected
        )
            return AppResult.Success(record)
        val editing = admitDocumentMetadataEditing(access)
        if (editing is AppResult.Failed) return editing
        if (record.attempts >= DOCUMENT_METADATA_MAXIMUM_ATTEMPTS)
            return documentFailure("client.document_retry_limit", FailureKind.Conflict)
        val policy =
            when (val result = preflight(workspaceId, access, record.command)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return send(workspaceId, access, record, policy)
    }

    private suspend fun preflight(
        workspaceId: String,
        access: DocumentAccess,
        command: DocumentMetadataCommand,
    ): AppResult<CompanyPolicySnapshot> {
        val id = command.change.documentId
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        val owner = admitted(workspaceId, access, id)
        if (owner is AppResult.Failed) return owner
        val policy =
            when (response) {
                is AppResult.Failed -> return reject(access, id, response, global = true)
                is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
            }
        val ready = continueReady(workspaceId, access, id, policy)
        if (ready is AppResult.Failed) return ready
        val header = remote.head(access, id)
        val headerOwner = continueReady(workspaceId, access, id, policy)
        if (headerOwner is AppResult.Failed) return headerOwner
        val head =
            when (header) {
                is AppResult.Failed -> return reject(access, id, header)
                is AppResult.Success -> header.value
            }
        if (head.id != id || head.employmentId != command.employmentId)
            return reject(
                access,
                id,
                documentFailure("document_access_denied", FailureKind.AccessDenied),
            )
        if (!head.metadataSupported)
            return documentFailure("client.document_metadata_unsupported", FailureKind.Unavailable)
        // Current ownership is required; a later document version or archive cannot replace the
        // original command.
        val owned = ownedEmployment(workspaceId, access, command, policy)
        if (owned is AppResult.Failed) return owned
        return AppResult.Success(policy)
    }

    private suspend fun ownedEmployment(
        workspaceId: String,
        access: DocumentAccess,
        command: DocumentMetadataCommand,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        var after: String? = null
        val cursors = mutableSetOf<String>()
        repeat(10) {
            val response = directory.employments(access, after)
            val owner = continueReady(workspaceId, access, command.change.documentId, policy)
            if (owner is AppResult.Failed) return owner
            val page =
                when (response) {
                    is AppResult.Failed ->
                        return reject(access, command.change.documentId, response)
                    is AppResult.Success -> response.value
                }
            if (!page.metadataSupported)
                return documentFailure(
                    "client.document_metadata_unsupported",
                    FailureKind.Unavailable,
                )
            if (page.items.any { it.id == command.employmentId }) return AppResult.Success(Unit)
            val next =
                page.nextCursor
                    ?: return reject(
                        access,
                        command.change.documentId,
                        documentFailure("document_access_denied", FailureKind.AccessDenied),
                    )
            if (!cursors.add(next))
                return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
            after = next
        }
        return documentFailure("client.document_employment_limit", FailureKind.Unavailable)
    }

    private suspend fun send(
        workspaceId: String,
        access: DocumentAccess,
        original: DocumentMetadataCommandRecord,
        policy: CompanyPolicySnapshot,
    ): AppResult<DocumentMetadataCommandRecord> {
        val sending =
            original.copy(
                revision = original.revision + 1,
                attempts = original.attempts + 1,
                outcome = DocumentMetadataCommandOutcome.Unconfirmed(),
            )
        val started = commands.update(access, original, sending.outcome, sending.attempts)
        val beforeHttp =
            continueReady(workspaceId, access, original.command.change.documentId, policy)
        if (beforeHttp is AppResult.Failed) return beforeHttp
        when (started) {
            is AppResult.Failed -> return started
            is AppResult.Success ->
                if (!started.value)
                    return documentFailure("client.document_command_changed", FailureKind.Conflict)
        }
        val response = remote.save(access, original.command.change)
        currentCoroutineContext().ensureActive()
        val owner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        val outcome =
            when (response) {
                is AppResult.Failed ->
                    resolveDocumentMetadataCommandFailure(original.outcome, response.failure)
                is AppResult.Success ->
                    if (
                        response.value.id == original.command.change.documentId &&
                            response.value.version == original.command.change.expectedVersion + 1
                    )
                        DocumentMetadataCommandOutcome.Confirmed(response.value.version)
                    else
                        DocumentMetadataCommandOutcome.Unconfirmed(
                            Failure(
                                FailureKind.Unexpected,
                                "The document response could not be read.",
                                code = "client.invalid_response",
                            )
                        )
            }
        return retain(workspaceId, access, sending, outcome, response)
    }

    private suspend fun retain(
        workspaceId: String,
        access: DocumentAccess,
        sending: DocumentMetadataCommandRecord,
        outcome: DocumentMetadataCommandOutcome,
        response: AppResult<DocumentMutationReceipt>,
    ): AppResult<DocumentMetadataCommandRecord> {
        val saved = commands.update(access, sending, outcome)
        val owner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        // Preserve authoritative read denials even if saving the operation outcome failed.
        if (response is AppResult.Failed)
            reject(access, sending.command.change.documentId, response)
        when (saved) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value)
                    return documentFailure("client.document_command_changed", FailureKind.Conflict)
        }
        val record = commands.find(access, sending.command.change.operationId)
        val publication = admitted(workspaceId, access, sending.command.change.documentId)
        if (publication is AppResult.Failed) return publication
        return when (record) {
            is AppResult.Failed -> record
            is AppResult.Success ->
                if (record.value?.command == sending.command)
                    AppResult.Success(requireNotNull(record.value))
                else documentFailure("client.document_command_changed", FailureKind.Conflict)
        }
    }

    private suspend fun continueReady(
        workspaceId: String,
        access: DocumentAccess,
        id: String,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val owner = admitted(workspaceId, access, id)
        if (owner is AppResult.Failed) return owner
        val available = admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
        return if (available is AppResult.Failed) reject(access, id, available, global = true)
        else available
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
