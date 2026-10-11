package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application owner serializes manual retries and bounded background delivery. */
class ReplayInboxAction(
    private val commands: InboxCommandRepository,
    private val remote: InboxRepository,
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : SyncTask {
    override val key = KEY
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        commandId: String,
    ): AppResult<InboxCommandRecord> =
        execution.withLock {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            val record =
                when (val result = commands.find(access, commandId)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success ->
                        result.value
                            ?: return@withLock inboxCommandFailure("client.inbox_command_changed")
                }
            deliver(workspaceId, access, record)
        }

    override suspend fun invoke(): SyncResult =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { deliverBatch() }
                ?: SyncResult.Retry(Failure(FailureKind.Timeout, "Inbox delivery timed out."))
        }

    private suspend fun deliverBatch(): SyncResult {
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
        val access =
            when (val result = currentAccess(workspace.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value
            }
        var cursor: String? = null
        var delivered = 0
        repeat(3) {
            val page =
                when (val result = commands.page(access, cursor)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success -> result.value
                }
            for (record in page.items) {
                if (record.outcome is InboxCommandOutcome.Confirmed) {
                    when (val result = retireConfirmed(workspace.id, access, record)) {
                        is AppResult.Failed -> return syncFailure(result.failure)
                        is AppResult.Success -> Unit
                    }
                    continue
                }
                if (
                    record.attempts >= INBOX_AUTOMATIC_ATTEMPTS ||
                        record.outcome is InboxCommandOutcome.Rejected
                )
                    continue
                when (val result = deliver(workspace.id, access, record)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success ->
                        when (val outcome = result.value.outcome) {
                            is InboxCommandOutcome.Unconfirmed ->
                                return outcome.failure?.let(::syncFailure) ?: SyncResult.Retry()
                            is InboxCommandOutcome.Rejected ->
                                return SyncResult.Blocked(outcome.failure)
                            else -> Unit
                        }
                }
                delivered++
                if (delivered == 10) return SyncResult.Retry()
            }
            val next = page.nextCursor ?: return SyncResult.Complete
            if (next == cursor)
                return SyncResult.Blocked(
                    Failure(
                        FailureKind.Storage,
                        "Inbox delivery progress is unavailable.",
                        code = "client.invalid_command_page",
                    )
                )
            cursor = next
        }
        return SyncResult.Complete
    }

    private suspend fun retireConfirmed(
        workspaceId: String,
        access: InboxAccess,
        record: InboxCommandRecord,
    ): AppResult<Boolean> {
        val receipt = record.outcome as InboxCommandOutcome.Confirmed
        val page =
            when (
                val result =
                    cache.observe(access, InboxCacheQuery.Item(record.command.itemId)).first()
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        if (
            page.checkpoint?.published != true ||
                validateInboxCache(access, page.checkpoint, clock.now()) is AppResult.Failed
        )
            return AppResult.Success(false)
        val item = page.items.singleOrNull()
        if (
            item != null &&
                (item.version < receipt.version || !inboxActionApplied(record.command.action, item))
        )
            return AppResult.Success(false)
        // A published replica either reflects this receipt or no longer exposes the item.
        // Only confirmed evidence is retired; uncertain attempts require their original retry.
        return commands.remove(access, record)
    }

    private suspend fun deliver(
        workspaceId: String,
        access: InboxAccess,
        record: InboxCommandRecord,
    ): AppResult<InboxCommandRecord> {
        if (!record.command.belongsTo(access))
            return inboxCommandFailure("client.inbox_scope_changed", FailureKind.AccessDenied)
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        if (record.outcome is InboxCommandOutcome.Confirmed) return AppResult.Success(record)
        if (record.attempts >= INBOX_MAXIMUM_ATTEMPTS)
            return inboxCommandFailure("client.inbox_retry_limit")
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> {
                    if (
                        policyResponse.failure.code in
                            setOf("session_revoked", "company_access_denied", "sync_access_denied")
                    ) {
                        val cleared = cache.clear(access)
                        if (cleared is AppResult.Failed) return cleared
                    }
                    return policyResponse
                }
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.COMMUNICATIONS, clock.now())
        if (availability is AppResult.Failed) return availability
        val attempt = record.attempts + 1
        when (
            val saved = commands.update(access, record, InboxCommandOutcome.Unconfirmed(), attempt)
        ) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value) return inboxCommandFailure("client.inbox_command_changed")
        }
        val sending =
            record.copy(
                revision = record.revision + 1,
                attempts = attempt,
                outcome = InboxCommandOutcome.Unconfirmed(),
            )
        currentCoroutineContext().ensureActive()
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val stillAvailable =
            admitCompanyOperation(policy, build, CompanyModule.COMMUNICATIONS, clock.now())
        if (stillAvailable is AppResult.Failed) return stillAvailable
        val result = remote.act(access.sessionId, record.command)
        currentCoroutineContext().ensureActive()
        val outcome =
            when (result) {
                is AppResult.Failed -> resolveInboxCommandFailure(record.outcome, result.failure)
                is AppResult.Success -> {
                    if (
                        result.value.id != record.command.itemId ||
                            result.value.version !in
                                record.command.expectedVersion..record.command.expectedVersion + 1
                    )
                        InboxCommandOutcome.Unconfirmed(
                            Failure(
                                FailureKind.Unexpected,
                                "The inbox response could not be read.",
                                code = "client.invalid_response",
                            )
                        )
                    else InboxCommandOutcome.Confirmed(result.value.version)
                }
            }
        // A receipt belongs to this retained account command even if its screen has changed.
        when (val saved = commands.update(access, sending, outcome)) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value) return inboxCommandFailure("client.inbox_command_changed")
        }
        currentCoroutineContext().ensureActive()
        when (val current = validateInboxCommandAccess(currentAccess(workspaceId), access)) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        return AppResult.Success(sending.copy(revision = sending.revision + 1, outcome = outcome))
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<InboxAccess> {
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
        return admitInbox(workspaceId, workspace, session, clock.now())
    }

    companion object {
        const val KEY = "hris-inbox-commands"
    }
}
