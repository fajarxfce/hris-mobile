@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.replica.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.policies.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.*
import dev.fajar.starter.documents.domain.replica.repositories.*
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned execution guard covers UI, reconnect, and worker triggers. */
class SyncDocumentCatalog(
    private val feed: SyncFeedRepository,
    private val remote: DocumentRepository,
    private val cache: DocumentCatalogRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) : SyncCompanyData {
    override val dataSet = CompanyDataSet.Documents
    override val key = KEY

    override suspend fun refresh(workspaceId: String): AppResult<DocumentCatalogSyncOutcome> =
        invoke(workspaceId)

    suspend operator fun invoke(
        workspaceId: String,
        force: Boolean = true,
    ): AppResult<DocumentCatalogSyncOutcome> =
        withTimeoutOrNull(30.seconds) { execution.withLock { synchronize(workspaceId, force) } }
            ?: AppResult.Success(DocumentCatalogSyncOutcome(false, 5))

    override suspend fun invoke(): SyncResult {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        return when (val result = invoke(workspace.id)) {
            is AppResult.Failed ->
                if (result.failure.code == "sync_scope_changed") SyncResult.Retry(result.failure)
                else syncFailure(result.failure)
            is AppResult.Success ->
                if (result.value.caughtUp) SyncResult.Complete else SyncResult.Retry()
        }
    }

    private suspend fun synchronize(
        workspaceId: String,
        force: Boolean,
    ): AppResult<DocumentCatalogSyncOutcome> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val readAdmission =
            when (val result = cache.admission(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val admissionOwner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (admissionOwner is AppResult.Failed) return admissionOwner
        if (!force && readAdmission.denial == null) {
            val retained =
                when (val result = cache.checkpoint(access)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val currentOwner = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (currentOwner is AppResult.Failed) return currentOwner
            if (
                retained != null &&
                    retained.published &&
                    retained.recordSchema == DocumentCatalogCheckpoint.CURRENT_RECORD_SCHEMA &&
                    sameDocumentScope(retained.access, access) &&
                    clock.now() < retained.refreshAfter
            )
                return AppResult.Success(DocumentCatalogSyncOutcome(true, 5))
        }
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateDocumentContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, policyResponse)
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
        if (availability is AppResult.Failed) return reject(access, availability)
        val directory = acquireEmployments(workspaceId, access, policy)
        if (directory is AppResult.Failed) return reject(access, directory)
        val selectedDirectory = (directory as AppResult.Success).value
        val synchronized =
            synchronizePages(
                workspaceId,
                access,
                policy,
                readAdmission.denial != null,
                selectedDirectory.employments,
                selectedDirectory.metadataSupported,
            )
        currentCoroutineContext().ensureActive()
        val finalOwner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (finalOwner is AppResult.Failed) return finalOwner
        if (synchronized is AppResult.Failed) return reject(access, synchronized)
        if (!(synchronized as AppResult.Success).value.caughtUp) return synchronized
        return when (val admitted = cache.admit(access, readAdmission.revision)) {
            is AppResult.Failed -> admitted
            is AppResult.Success ->
                if (admitted.value) synchronized
                else
                    when (val current = cache.admission(access)) {
                        is AppResult.Failed -> current
                        is AppResult.Success ->
                            current.value.denial?.let { AppResult.Failed(it) }
                                ?: publicationConflict(workspaceId, access)
                    }
        }
    }

    private suspend fun acquireEmployments(
        workspaceId: String,
        access: DocumentAccess,
        policy: CompanyPolicySnapshot,
    ): AppResult<DocumentCatalogDirectory> {
        val rows = mutableListOf<DocumentEmployment>()
        var after: String? = null
        var supported: Boolean? = null
        val cursors = mutableSetOf<String>()
        repeat(11) {
            val acquired = remote.employments(access, after)
            currentCoroutineContext().ensureActive()
            val owner = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Failed) return owner
            val available =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (available is AppResult.Failed) return available
            val page =
                when (acquired) {
                    is AppResult.Failed -> return acquired
                    is AppResult.Success -> acquired.value
                }
            if (page.items.size > 20 || (supported != null && supported != page.metadataSupported))
                return documentFailure("client.invalid_response", FailureKind.Unexpected)
            supported = page.metadataSupported
            rows += page.items
            if (rows.size > 200) return documentFailure("sync_scope_limit")
            if (
                rows.distinctBy { it.id }.size != rows.size ||
                    rows.distinctBy { it.employeeNumber }.size != rows.size
            )
                return documentFailure("client.invalid_response", FailureKind.Unexpected)
            val cursor =
                page.nextCursor
                    ?: return AppResult.Success(
                        DocumentCatalogDirectory(rows.toList(), page.metadataSupported)
                    )
            if (page.items.isEmpty() || !cursors.add(cursor))
                return documentFailure("client.invalid_response", FailureKind.Unexpected)
            after = cursor
        }
        return documentFailure("sync_scope_limit")
    }

    /** Scope/format replacement keeps owned published data until its successor is complete. */
    private suspend fun prepareCheckpoint(
        workspaceId: String,
        access: DocumentAccess,
        replacePublished: Boolean,
        employments: List<DocumentEmployment>,
        metadataSupported: Boolean,
    ): AppResult<DocumentCatalogCheckpoint?> {
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val checkpointOwner = validateDocumentContinuation(access, currentAccess(workspaceId))
        if (checkpointOwner is AppResult.Failed) return checkpointOwner
        if (checkpoint != null && !sameStoredDocumentScope(checkpoint.access, access)) {
            val cleared = withTimeoutOrNull(2.seconds) { cache.clear(access) }
            if (cleared !is AppResult.Success || !cleared.value)
                return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
            checkpoint = null
        } else if (
            checkpoint != null &&
                ((replacePublished && checkpoint.published) ||
                    !sameDocumentScope(checkpoint.access, access) ||
                    checkpoint.recordSchema < DocumentCatalogCheckpoint.CURRENT_RECORD_SCHEMA ||
                    checkpoint.metadataSupported != metadataSupported)
        ) {
            // Session, format or server capability changes rebuild the owned projection.
            // An incomplete replacement keeps its cursor across bounded runs.
            val replacement =
                initialDocumentCatalogCheckpoint(
                    access,
                    Uuid.random().toString(),
                    clock.now(),
                    employments,
                    metadataSupported,
                )
            when (val started = cache.begin(replacement, checkpoint.generation)) {
                is AppResult.Failed -> return started
                is AppResult.Success ->
                    if (!started.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = replacement
        }
        return AppResult.Success(checkpoint)
    }

    private suspend fun synchronizePages(
        workspaceId: String,
        access: DocumentAccess,
        policy: CompanyPolicySnapshot,
        replacePublished: Boolean,
        employments: List<DocumentEmployment>,
        metadataSupported: Boolean,
    ): AppResult<DocumentCatalogSyncOutcome> {
        var checkpoint =
            when (
                val result =
                    prepareCheckpoint(
                        workspaceId,
                        access,
                        replacePublished,
                        employments,
                        metadataSupported,
                    )
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var restarted = false
        repeat(10) {
            val ownership = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            if (checkpoint == null) {
                val initial =
                    initialDocumentCatalogCheckpoint(
                        access,
                        Uuid.random().toString(),
                        clock.now(),
                        employments,
                        metadataSupported,
                    )
                when (val started = cache.begin(initial, null)) {
                    is AppResult.Failed -> return started
                    is AppResult.Success ->
                        if (!started.value) return publicationConflict(workspaceId, access)
                }
                checkpoint = initial
            }
            val previous = requireNotNull(checkpoint)
            previous.pollAt?.let { due ->
                val wait = due - clock.now()
                if (wait.isPositive()) delay(wait)
            }
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (stillAvailable is AppResult.Failed) return stillAvailable
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            val pageOwnership = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (pageOwnership is AppResult.Failed) return pageOwnership
            if (incoming is AppResult.Failed) {
                if (incoming.failure.code in cursorResetCodes && !restarted) {
                    // Cursor retention is unrelated to permission to read stored facts.
                    val replacement =
                        initialDocumentCatalogCheckpoint(
                            access,
                            Uuid.random().toString(),
                            clock.now(),
                            employments,
                            metadataSupported,
                        )
                    when (val started = cache.begin(replacement, previous.generation)) {
                        is AppResult.Failed -> return started
                        is AppResult.Success ->
                            if (!started.value) return publicationConflict(workspaceId, access)
                    }
                    checkpoint = replacement
                    restarted = true
                    return@repeat
                }
                return incoming
            }
            val page = (incoming as AppResult.Success).value
            val hydrated =
                hydrate(workspaceId, access, policy, page.changes, employments, metadataSupported)
            currentCoroutineContext().ensureActive()
            val hydrationOwnership =
                validateDocumentContinuation(access, currentAccess(workspaceId))
            if (hydrationOwnership is AppResult.Failed) return hydrationOwnership
            val entries =
                when (hydrated) {
                    is AppResult.Failed -> return hydrated
                    is AppResult.Success -> hydrated.value
                }
            val complete =
                page.phase == DocumentCatalogSyncPhase.CHANGES &&
                    !page.hasMore &&
                    !page.pendingPublication
            val finalAvailability =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (finalAvailability is AppResult.Failed) return finalAvailability
            val next = advanceDocumentCatalogCheckpoint(previous, page, clock.now(), employments)
            when (val committed = cache.commit(previous, next, entries)) {
                is AppResult.Failed -> return committed
                is AppResult.Success ->
                    if (!committed.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = next
            if (complete)
                return AppResult.Success(DocumentCatalogSyncOutcome(true, page.pollAfterSeconds))
        }
        return AppResult.Success(DocumentCatalogSyncOutcome(false, 5))
    }

    private suspend fun nextPage(
        access: DocumentAccess,
        checkpoint: DocumentCatalogCheckpoint,
    ): AppResult<DocumentCatalogSyncPage> =
        when (checkpoint.phase) {
            DocumentCatalogSyncPhase.BOOTSTRAP ->
                when (
                    val result =
                        feed.bootstrap(
                            access.sessionId,
                            access.companyId,
                            documentCatalogCollections,
                            checkpoint.cursor,
                            limit = 50,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val page = result.value
                        if (page.collections != documentCatalogCollections)
                            documentFailure(
                                "client.document_scope_changed",
                                FailureKind.AccessDenied,
                            )
                        else if (
                            (page.nextCursor == null && page.changesCursor == null) ||
                                (page.nextCursor != null && page.nextCursor == checkpoint.cursor)
                        )
                            documentFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                DocumentCatalogSyncPage(
                                    page.items.map { SyncChange(it, SyncOperation.UPSERT) },
                                    if (page.nextCursor == null) DocumentCatalogSyncPhase.CHANGES
                                    else DocumentCatalogSyncPhase.BOOTSTRAP,
                                    requireNotNull(page.nextCursor ?: page.changesCursor),
                                    true,
                                    false,
                                    5,
                                )
                            )
                    }
                }
            DocumentCatalogSyncPhase.CHANGES ->
                when (
                    val result =
                        feed.changes(
                            access.sessionId,
                            access.companyId,
                            documentCatalogCollections,
                            requireNotNull(checkpoint.cursor),
                            limit = 50,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        if (result.value.hasMore && result.value.cursor == checkpoint.cursor)
                            documentFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                DocumentCatalogSyncPage(
                                    result.value.items,
                                    DocumentCatalogSyncPhase.CHANGES,
                                    result.value.cursor,
                                    result.value.hasMore,
                                    result.value.pendingPublication,
                                    result.value.pollAfterSeconds,
                                )
                            )
                }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun hydrate(
        workspaceId: String,
        access: DocumentAccess,
        policy: CompanyPolicySnapshot,
        changes: List<SyncChange>,
        employments: List<DocumentEmployment>,
        metadataSupported: Boolean,
    ): AppResult<List<DocumentCatalogEntry>> {
        if (
            changes.size > 50 ||
                changes.any {
                    it.reference.collection != SyncCollection.DOCUMENT_EVIDENCE ||
                        it.reference.version < 0 ||
                        Uuid.parseOrNull(it.reference.id)?.toString() != it.reference.id
                }
        )
            return documentFailure("client.invalid_response", FailureKind.Unexpected)
        val references = linkedMapOf<String, SyncChange>()
        for (change in changes) {
            val previous = references[change.reference.id]
            if (
                previous == null ||
                    change.reference.version > previous.reference.version ||
                    (change.reference.version == previous.reference.version &&
                        change.operation == SyncOperation.DELETE)
            )
                references[change.reference.id] = change
        }
        val entries = mutableListOf<DocumentCatalogEntry>()
        for (batch in references.values.chunked(4)) {
            val owner = validateDocumentContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Failed) return owner
            val available =
                admitCompanyOperation(policy, build, CompanyModule.DOCUMENTS, clock.now())
            if (available is AppResult.Failed) return available
            val results =
                batch
                    .map { change ->
                        flow {
                            if (change.operation == SyncOperation.DELETE) {
                                emit(
                                    AppResult.Success(DocumentCatalogEntry(change.reference, null))
                                )
                            } else {
                                val loaded = remote.record(access, change.reference.id)
                                currentCoroutineContext().ensureActive()
                                emit(
                                    when (loaded) {
                                        is AppResult.Failed ->
                                            // Publication may have been withdrawn after its
                                            // reference.
                                            if (
                                                loaded.failure.code == "document_evidence_not_found"
                                            )
                                                AppResult.Success(
                                                    DocumentCatalogEntry(change.reference, null)
                                                )
                                            else loaded
                                        is AppResult.Success -> {
                                            val value = loaded.value
                                            if (
                                                value.id != change.reference.id ||
                                                    value.metadataSupported != metadataSupported ||
                                                    employments.none {
                                                        it.id == value.employmentId
                                                    } ||
                                                    value.version < change.reference.version
                                            )
                                                documentFailure(
                                                    "client.invalid_response",
                                                    FailureKind.Unexpected,
                                                )
                                            else
                                                AppResult.Success(
                                                    DocumentCatalogEntry(
                                                        change.reference.copy(
                                                            version = value.version
                                                        ),
                                                        value,
                                                    )
                                                )
                                        }
                                    }
                                )
                            }
                        }
                    }
                    .merge()
                    .transformWhile { result ->
                        emit(result)
                        result !is AppResult.Failed
                    }
                    .toList()
            for (result in results) when (result) {
                is AppResult.Failed -> return result
                is AppResult.Success -> entries += result.value
            }
        }
        return AppResult.Success(entries.toList())
    }

    private suspend fun publicationConflict(
        workspaceId: String,
        access: DocumentAccess,
    ): AppResult.Failed =
        when (val current = validateDocumentContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> current
            is AppResult.Success ->
                documentFailure("client.document_catalog_changed", FailureKind.Conflict)
        }

    private suspend fun reject(
        access: DocumentAccess,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (documentCatalogFailureClosesRead(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) { cache.clear(access) }
        }
        return failure
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<DocumentAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return workspaceFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
            }
        val workspace =
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

    companion object {
        const val KEY = "hris-document-catalog-sync"
    }
}

private val documentCatalogCollections = setOf(SyncCollection.DOCUMENT_EVIDENCE)

private val cursorResetCodes =
    setOf(
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
