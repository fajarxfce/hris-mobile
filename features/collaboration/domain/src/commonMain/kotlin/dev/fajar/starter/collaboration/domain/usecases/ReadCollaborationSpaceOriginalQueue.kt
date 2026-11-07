package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlinx.coroutines.*

/** Current-admission recovery facts; never resumes, settles, restages or acknowledges a queue. */
class ReadCollaborationSpaceOriginalQueue(
    private val conversations: CollaborationSpaceOriginalConversationRepository,
    private val identities: CollaborationSpaceIdentityRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val messages: CollaborationMessageRepository,
    private val completions: CollaborationMessageCompletionRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        position: CollaborationOriginalConversationPosition,
    ): AppResult<CollaborationOriginalQueueMatch> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationOriginalQueueRequest(runtime, position))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var attempt: CollaborationSpaceSetupAttempt? = null
        try {
            return withTimeoutOrNull(15_000) {
                val parent =
                    sessions.captureCollaborationSpaceParent(
                        runtime.owner.parent,
                        server,
                        build,
                        clock,
                    )
                if (parent is AppResult.Failed) return@withTimeoutOrNull parent
                val current =
                    CollaborationSpaceSetupAttempt(
                        runtime.owner,
                        directory,
                        sessions,
                        server,
                        build,
                        clock,
                        requestedAt,
                        started,
                        Instant.fromEpochMilliseconds(
                            (parent as AppResult.Success).value.binding.expiresAtEpochMillis
                        ),
                    )
                attempt = current
                read(current, runtime, position)
            } ?: (attempt?.failed(timeout().failure) ?: timeout())
        } finally {
            if (attempt?.restriction != null) {
                withContext(NonCancellable) { runCatching { runtimes.closeSpace(runtime.owner) } }
            }
        }
    }

    private suspend fun read(
        attempt: CollaborationSpaceSetupAttempt,
        runtime: CollaborationSpaceRuntimeReady,
        position: CollaborationOriginalConversationPosition,
    ): AppResult<CollaborationOriginalQueueMatch> {
        val admitted = attempt.admission()
        if (admitted is AppResult.Failed) return admitted
        val gate = (admitted as AppResult.Success).value
        gate.denial?.let {
            return attempt.failed(it)
        }
        val conversation = conversations.get(runtime.owner, position)
        attempt.restrict(conversation)
        val afterConversation = attempt.unchangedAdmission(gate)
        if (afterConversation is AppResult.Failed) return afterConversation
        if (conversation is AppResult.Failed) return attempt.failed(conversation.failure)
        val metadata = (conversation as AppResult.Success).value
        if (
            !validCollaborationSpaceOriginalConversation(metadata, runtime.owner, position) ||
                metadata.room == null
        )
            return invalidSpaceSetup()
        val identity = identities.read(runtime.owner)
        attempt.restrict(identity)
        val afterIdentity = attempt.unchangedAdmission(gate)
        if (afterIdentity is AppResult.Failed) return afterIdentity
        if (identity is AppResult.Failed) return attempt.failed(identity.failure)
        val observation = (identity as AppResult.Success).value
        val selected = observation.identity ?: return changedSpaceSetup()
        if (
            observation.owner != runtime.owner ||
                !validCollaborationSpaceIdentity(runtime.owner, selected) ||
                selected.origin != CollaborationSpaceIdentityOrigin.LEGACY ||
                selected.legacyCompanyId != position.companyId ||
                selected.serverName != metadata.room.serverName
        )
            return invalidSpaceSetup()
        return match(attempt, gate, runtime, metadata, selected)
    }

    private suspend fun match(
        attempt: CollaborationSpaceSetupAttempt,
        gate: CollaborationSpaceDirectoryAdmission,
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceOriginalConversation,
        identity: CollaborationSpaceIdentity,
    ): AppResult<CollaborationOriginalQueueMatch> {
        val acquired =
            runtimes.inspectOriginalQueue(runtime, requireNotNull(conversation.room).roomId)
        // Detach bounded private facts before admission/storage suspension can mutate a fake or
        // externally supplied repository collection. SDK failures never become remote group denial.
        val snapshot =
            when (acquired) {
                is AppResult.Failed -> null
                is AppResult.Success ->
                    acquired.value
                        .takeIf { it.original.messages.size <= 128 }
                        ?.let {
                            it.copy(
                                original =
                                    it.original.copy(messages = it.original.messages.toList())
                            )
                        }
            }
        val afterSnapshot = attempt.unchangedAdmission(gate)
        if (afterSnapshot is AppResult.Failed) return afterSnapshot
        if (acquired is AppResult.Failed) return attempt.failed(acquired.failure)
        if (snapshot == null) return invalidSpaceSetup()
        val requested =
            collaborationOriginalQueueLogicalIds(runtime, conversation, identity, snapshot)
        if (requested is AppResult.Failed) return requested
        val binding = snapshot.original.binding
        val access =
            CollaborationLocalAccess(
                runtime.owner.parent.sessionId,
                binding.audience,
                binding.accountId,
                binding.companyId,
            )
        val read = readMessages(attempt, gate, access, (requested as AppResult.Success).value)
        if (read is AppResult.Failed) return read
        val (records, completed) = (read as AppResult.Success).value
        val result =
            matchCollaborationOriginalQueue(
                runtime,
                conversation,
                identity,
                snapshot,
                records,
                completed,
            )
        if (result is AppResult.Failed) return result
        val open = runtimes.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
        val afterOpen = attempt.unchangedAdmission(gate)
        if (afterOpen is AppResult.Failed) return afterOpen
        if (open is AppResult.Failed) return attempt.failed(open.failure)
        return if ((open as AppResult.Success).value) result else changedSpaceSetup()
    }

    private suspend fun readMessages(
        attempt: CollaborationSpaceSetupAttempt,
        gate: CollaborationSpaceDirectoryAdmission,
        access: CollaborationLocalAccess,
        ids: List<String>,
    ): AppResult<Pair<List<CollaborationMessageRecord>, List<CollaborationMessageCompletion>>> {
        val records = mutableListOf<CollaborationMessageRecord>()
        val completed = mutableListOf<CollaborationMessageCompletion>()
        for (id in ids) {
            val received = messages.get(access, id)
            val afterRecord = attempt.unchangedAdmission(gate)
            if (afterRecord is AppResult.Failed) return afterRecord
            if (received is AppResult.Failed) return attempt.failed(received.failure)
            val record = (received as AppResult.Success).value ?: return changedSpaceSetup()
            if (record.intent.id != id) return invalidSpaceSetup()
            records += record
            if (record.state is CollaborationMessageState.Sent) {
                val read = completions.readCompletion(access, id)
                val proof =
                    (read as? AppResult.Success)
                        ?.value
                        ?.takeIf { it.eventIds.size in 1..128 }
                        ?.let { it.copy(eventIds = it.eventIds.toList()) }
                val afterCompletion = attempt.unchangedAdmission(gate)
                if (afterCompletion is AppResult.Failed) return afterCompletion
                if (read is AppResult.Failed) return attempt.failed(read.failure)
                completed += proof ?: return changedSpaceSetup()
            }
        }
        return AppResult.Success(records.toList() to completed.toList())
    }

    private fun timeout() =
        collaborationRecoveryFailure("client.collaboration_recovery_timeout", FailureKind.Timeout)
}
