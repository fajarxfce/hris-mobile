@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.domain.replica.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.policies.*
import dev.fajar.starter.payroll.domain.replica.repositories.*
import dev.fajar.starter.payroll.domain.repositories.PayslipRepository
import dev.fajar.starter.payroll.domain.repositories.PayslipRevisionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned execution guard covers UI, reconnect, and worker triggers. */
class SyncPayroll(
    private val feed: SyncFeedRepository,
    private val remote: PayslipRepository,
    private val cache: PayslipReplicaRepository,
    private val revisions: PayslipRevisionRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) : SyncTask {
    override val key = KEY

    suspend operator fun invoke(workspaceId: String): AppResult<PayrollSyncOutcome> =
        withTimeoutOrNull(30.seconds) { execution.withLock { synchronize(workspaceId) } }
            ?: AppResult.Success(PayrollSyncOutcome(false, 5))

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

    private suspend fun synchronize(workspaceId: String): AppResult<PayrollSyncOutcome> {
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
        val admissionOwner = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (admissionOwner is AppResult.Failed) return admissionOwner
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validatePayrollContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, policyResponse)
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability = admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (availability is AppResult.Failed) return reject(access, availability)
        val readAccess = boundPayrollAuthorization(access, policy, clock.now())
        val synchronized =
            synchronizePages(workspaceId, readAccess, policy, readAdmission.denial != null)
        currentCoroutineContext().ensureActive()
        val finalOwner = validatePayrollContinuation(access, currentAccess(workspaceId))
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

    /** Scope/format replacement keeps owned published data until its successor is complete. */
    private suspend fun prepareCheckpoint(
        workspaceId: String,
        access: PayrollAccess,
        replacePublished: Boolean,
    ): AppResult<PayrollCheckpoint?> {
        var checkpoint =
            when (val result = cache.checkpoint(access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val checkpointOwner = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (checkpointOwner is AppResult.Failed) return checkpointOwner
        if (checkpoint != null && !sameStoredPayrollScope(checkpoint.access, access)) {
            val cleared = withTimeoutOrNull(2.seconds) { cache.clear(access) }
            if (cleared !is AppResult.Success || !cleared.value)
                return payslipFailure("client.payslip_scope_changed", FailureKind.AccessDenied)
            checkpoint = null
        } else if (
            checkpoint != null &&
                ((replacePublished && checkpoint.published) ||
                    checkpoint.recordVersion < PayrollCheckpoint.CURRENT_RECORD_VERSION ||
                    !samePayrollScope(checkpoint.access, access))
        ) {
            // Reauthentication, a format upgrade or an earlier denial rebuilds the projection.
            // An incomplete replacement keeps its cursor across bounded runs.
            val replacement =
                initialPayrollCheckpoint(access, Uuid.random().toString(), clock.now())
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
        access: PayrollAccess,
        policy: CompanyPolicySnapshot,
        replacePublished: Boolean,
    ): AppResult<PayrollSyncOutcome> {
        var checkpoint =
            when (val result = prepareCheckpoint(workspaceId, access, replacePublished)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        var restarted = false
        repeat(10) {
            val ownership = validatePayrollContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return ownership
            if (checkpoint == null) {
                val initial =
                    initialPayrollCheckpoint(access, Uuid.random().toString(), clock.now())
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
                admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
            if (stillAvailable is AppResult.Failed) return stillAvailable
            val incoming = nextPage(access, previous)
            currentCoroutineContext().ensureActive()
            val pageOwnership = validatePayrollContinuation(access, currentAccess(workspaceId))
            if (pageOwnership is AppResult.Failed) return pageOwnership
            if (incoming is AppResult.Failed) {
                if (incoming.failure.code in cursorResetCodes && !restarted) {
                    // Cursor retention is unrelated to permission to read stored facts.
                    val replacement =
                        initialPayrollCheckpoint(access, Uuid.random().toString(), clock.now())
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
            val hydrated = hydrate(workspaceId, access, policy, page.changes)
            currentCoroutineContext().ensureActive()
            val hydrationOwnership = validatePayrollContinuation(access, currentAccess(workspaceId))
            if (hydrationOwnership is AppResult.Failed) return hydrationOwnership
            val entries =
                when (hydrated) {
                    is AppResult.Failed -> return hydrated
                    is AppResult.Success -> hydrated.value
                }
            val complete =
                page.phase == PayrollSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
            val finalAvailability =
                admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
            if (finalAvailability is AppResult.Failed) return finalAvailability
            val retained = retainStatements(workspaceId, access, policy, entries)
            if (retained is AppResult.Failed) return retained
            val next = advancePayrollCheckpoint(previous, page, clock.now())
            when (val committed = cache.commit(previous, next, entries)) {
                is AppResult.Failed -> return committed
                is AppResult.Success ->
                    if (!committed.value) return publicationConflict(workspaceId, access)
            }
            checkpoint = next
            if (complete) return AppResult.Success(PayrollSyncOutcome(true, page.pollAfterSeconds))
        }
        return AppResult.Success(PayrollSyncOutcome(false, 5))
    }

    private suspend fun nextPage(
        access: PayrollAccess,
        checkpoint: PayrollCheckpoint,
    ): AppResult<PayrollSyncPage> =
        when (checkpoint.phase) {
            PayrollSyncPhase.BOOTSTRAP ->
                when (
                    val result =
                        feed.bootstrap(
                            access.sessionId,
                            access.companyId,
                            payrollCollections,
                            checkpoint.cursor,
                            limit = 8,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val page = result.value
                        if (page.collections != payrollCollections)
                            payslipFailure("client.payslip_scope_changed", FailureKind.AccessDenied)
                        else if (
                            (page.nextCursor == null && page.changesCursor == null) ||
                                (page.nextCursor != null && page.nextCursor == checkpoint.cursor)
                        )
                            payslipFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                PayrollSyncPage(
                                    page.items.map { SyncChange(it, SyncOperation.UPSERT) },
                                    if (page.nextCursor == null) PayrollSyncPhase.CHANGES
                                    else PayrollSyncPhase.BOOTSTRAP,
                                    requireNotNull(page.nextCursor ?: page.changesCursor),
                                    true,
                                    false,
                                    5,
                                )
                            )
                    }
                }
            PayrollSyncPhase.CHANGES ->
                when (
                    val result =
                        feed.changes(
                            access.sessionId,
                            access.companyId,
                            payrollCollections,
                            requireNotNull(checkpoint.cursor),
                            limit = 8,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        if (result.value.hasMore && result.value.cursor == checkpoint.cursor)
                            payslipFailure("client.invalid_response", FailureKind.Unexpected)
                        else
                            AppResult.Success(
                                PayrollSyncPage(
                                    result.value.items,
                                    PayrollSyncPhase.CHANGES,
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
        access: PayrollAccess,
        policy: CompanyPolicySnapshot,
        changes: List<SyncChange>,
    ): AppResult<List<PayrollReplicaEntry>> {
        if (
            changes.size > 8 ||
                changes.any {
                    it.reference.collection !in payrollCollections || it.reference.version < 0
                }
        )
            return payslipFailure("client.invalid_response", FailureKind.Unexpected)
        val references = linkedMapOf<Pair<SyncCollection, String>, SyncChange>()
        for (change in changes) {
            val previous = references[change.reference.collection to change.reference.id]
            if (
                previous == null ||
                    change.reference.version > previous.reference.version ||
                    (change.reference.version == previous.reference.version &&
                        change.operation == SyncOperation.DELETE)
            )
                references[change.reference.collection to change.reference.id] = change
        }
        val entries = mutableListOf<PayrollReplicaEntry>()
        for (batch in references.values.groupBy { it.reference.id }.values.chunked(4)) {
            val results =
                batch
                    .map { group ->
                        flow { emit(hydratePayslip(workspaceId, access, policy, group)) }
                    }
                    .merge()
                    .transformWhile { result ->
                        emit(result)
                        result !is AppResult.Failed
                    }
                    .toList()
            for (result in results) when (result) {
                is AppResult.Failed -> return result
                is AppResult.Success -> entries.addAll(result.value)
            }
        }
        return AppResult.Success(entries.toList())
    }

    /** Acquiring a slip also refreshes its independently versioned payment progress. */
    private suspend fun hydratePayslip(
        workspaceId: String,
        access: PayrollAccess,
        policy: CompanyPolicySnapshot,
        changes: List<SyncChange>,
    ): AppResult<List<PayrollReplicaEntry>> {
        val slip = changes.singleOrNull { it.reference.collection == SyncCollection.PAYSLIPS }
        val payment =
            changes.singleOrNull { it.reference.collection == SyncCollection.PAYROLL_PAYMENTS }
        val entries = mutableListOf<PayrollReplicaEntry>()
        if (slip?.operation == SyncOperation.DELETE) {
            entries += PayrollReplicaEntry(slip.reference, null)
            if (payment?.operation == SyncOperation.DELETE)
                entries += PayrollReplicaEntry(payment.reference, null)
            return AppResult.Success(entries.toList())
        }
        val id = changes.first().reference.id
        if (slip != null) {
            val value =
                when (val loaded = remote.details(access, id)) {
                    is AppResult.Failed -> return loaded
                    is AppResult.Success -> loaded.value
                }
            currentCoroutineContext().ensureActive()
            if (value.summary.id != id || value.summary.version < slip.reference.version)
                return payslipFailure("client.invalid_response", FailureKind.Unexpected)
            entries +=
                PayrollReplicaEntry(
                    SyncReference(SyncCollection.PAYSLIPS, id, value.summary.version),
                    PayrollRecord.Slip(value),
                )
        }
        val ownership = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val available = admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (available is AppResult.Failed) return available
        if (payment?.operation == SyncOperation.DELETE) {
            entries += PayrollReplicaEntry(payment.reference, null)
        } else {
            val value =
                when (val loaded = remote.payments(access, id)) {
                    is AppResult.Failed -> return loaded
                    is AppResult.Success -> loaded.value
                }
            currentCoroutineContext().ensureActive()
            if (value.payslipId != id || value.version < (payment?.reference?.version ?: 0L))
                return payslipFailure("client.invalid_response", FailureKind.Unexpected)
            entries +=
                PayrollReplicaEntry(
                    SyncReference(SyncCollection.PAYROLL_PAYMENTS, id, value.version),
                    PayrollRecord.Payments(value),
                )
        }
        return AppResult.Success(entries.toList())
    }

    /**
     * Retain both sides before replacing a head, including replicas acquired before history
     * support.
     */
    private suspend fun retainStatements(
        workspaceId: String,
        access: PayrollAccess,
        policy: CompanyPolicySnapshot,
        entries: List<PayrollReplicaEntry>,
    ): AppResult<Unit> {
        for (entry in entries) {
            val incoming = (entry.record as? PayrollRecord.Slip)?.value ?: continue
            val stored = cache.observeDetails(access, incoming.summary.id).first()
            currentCoroutineContext().ensureActive()
            val owner = validatePayrollContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Failed) return owner
            val previous =
                when (stored) {
                    is AppResult.Failed -> return stored
                    is AppResult.Success -> {
                        val valid = validateStoredPayrollReplica(access, stored.value.checkpoint)
                        if (valid is AppResult.Failed) return valid
                        stored.value.view?.payslip
                    }
                }
            val head =
                previous?.takeIf { it.summary.version > incoming.summary.version } ?: incoming
            for (statement in listOfNotNull(previous, incoming).distinct()) {
                val valid = validatePayslipRevision(head, statement.summary.version, statement)
                if (valid is AppResult.Failed) return valid
                val available =
                    admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
                if (available is AppResult.Failed) return available
                val result = revisions.retainRevision(access, statement)
                currentCoroutineContext().ensureActive()
                val continued = validatePayrollContinuation(access, currentAccess(workspaceId))
                if (continued is AppResult.Failed) return continued
                when (result) {
                    is AppResult.Failed -> return result
                    is AppResult.Success ->
                        if (!result.value) return publicationConflict(workspaceId, access)
                }
            }
        }
        return admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
    }

    private suspend fun publicationConflict(
        workspaceId: String,
        access: PayrollAccess,
    ): AppResult.Failed =
        when (val current = validatePayrollContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> current
            is AppResult.Success -> payslipFailure("client.payslip_changed", FailureKind.Conflict)
        }

    private suspend fun reject(access: PayrollAccess, failure: AppResult.Failed): AppResult.Failed {
        if (payslipFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) { cache.clear(access) }
        }
        return failure
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<PayrollAccess> {
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
        return admitPayroll(workspaceId, workspace, latest, build, clock.now())
    }

    companion object {
        const val KEY = "hris-payroll-sync"
    }
}

private val payrollCollections = setOf(SyncCollection.PAYSLIPS, SyncCollection.PAYROLL_PAYMENTS)

private val cursorResetCodes =
    setOf(
        "sync_cursor_expired",
        "sync_cursor_out_of_range",
        "sync_cursor_invalidated",
        "sync_bootstrap_limit",
        "invalid_sync_cursor",
    )
