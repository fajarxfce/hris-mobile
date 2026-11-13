package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One admitted offline edit; command, optimistic preview and recovery wake are retained together.
 */
class QueueCollaborationSpaceEdit(
    private val messages: CollaborationSpaceMessageRepository,
    private val companyMessages: CollaborationMessageRepository,
    private val publications: CollaborationSpaceEditPublicationRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val schedules: SyncScheduleRepository,
    private val clock: Clock,
) {
    private val submissions = Mutex()

    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        request: CollaborationSpaceEditRequest,
    ): AppResult<CollaborationSpaceMessageRecord> =
        submissions.withLock { Submission(access, request).run() }

    /** Owns the denial latch and all finite reads for exactly one user gesture. */
    private inner class Submission(
        private val access: CollaborationSpaceLocalAccess,
        private val request: CollaborationSpaceEditRequest,
    ) {
        private var denial: Failure? = null

        private suspend fun current(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val session = sessions.current()
            currentCoroutineContext().ensureActive()
            if (session is AppResult.Failed) return AppResult.Failed(denial ?: session.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (session as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
            val read = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return AppResult.Failed(denial ?: read.failure)
            val gate = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate))
                return AppResult.Failed(denial ?: unavailable(FailureKind.Unexpected).failure)
            denial = denial ?: gate.denial
            val latest = sessions.current()
            currentCoroutineContext().ensureActive()
            denial?.let {
                return AppResult.Failed(it)
            }
            if (latest is AppResult.Failed) return latest
            val current =
                localCollaborationSpaceAccess(
                    access,
                    (latest as AppResult.Success).value,
                    server.audience,
                )
            return if (current is AppResult.Failed) current else AppResult.Success(Unit)
        }

        private suspend fun retained(
            record: CollaborationSpaceMessageRecord
        ): AppResult<CollaborationSpaceMessageRecord> {
            if (!retainedCollaborationSpaceEdit(access, request, record)) return unavailable()
            if (
                record.state !is CollaborationMessageState.Sent &&
                    record.state != CollaborationMessageState.Cancelled
            ) {
                withTimeoutOrNull(1_500) {
                    schedules.request(CollaborationSpaceRecoveryWake.WORK_KEY)
                }
                current().let { if (it is AppResult.Failed) return it }
            }
            return AppResult.Success(record)
        }

        suspend fun run(): AppResult<CollaborationSpaceMessageRecord> {
            if (!validCollaborationSpaceEditRequest(request))
                return unavailable(FailureKind.Validation)
            repeat(3) {
                current().let { if (it is AppResult.Failed) return it }
                val read = messages.get(access, request.id)
                current().let { if (it is AppResult.Failed) return it }
                if (read is AppResult.Failed) return read
                (read as AppResult.Success).value?.let {
                    return retained(it)
                }
                val source = readHistory()
                if (source is AppResult.Failed) return source
                val history = (source as AppResult.Success).value ?: return@repeat
                val prepared = readProjection(history)
                if (prepared is AppResult.Failed) return prepared
                val value = (prepared as AppResult.Success).value ?: return@repeat
                currentCoroutineContext().ensureActive()
                val written =
                    publications.publishEdit(
                        access,
                        value.edit.intent,
                        value.location,
                        value.revision,
                        value.edit.publication,
                    )
                current().let { if (it is AppResult.Failed) return it }
                // The same gesture is read back before any source/index recomputation.
                val readback = messages.get(access, request.id)
                current().let { if (it is AppResult.Failed) return it }
                if (readback is AppResult.Failed) return readback
                (readback as AppResult.Success).value?.let {
                    return retained(it)
                }
                if (written is AppResult.Failed) return written
                when ((written as AppResult.Success).value) {
                    CollaborationSpaceEditPublicationResult.CHANGED -> Unit
                    CollaborationSpaceEditPublicationResult.CAPACITY_EXCEEDED ->
                        return collaborationRecoveryFailure(
                            "client.command_queue_full",
                            FailureKind.Storage,
                        )
                    CollaborationSpaceEditPublicationResult.SCOPE_CHANGED ->
                        return collaborationRecoveryFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
                    CollaborationSpaceEditPublicationResult.STORED,
                    CollaborationSpaceEditPublicationResult.RETAINED ->
                        return unavailable(FailureKind.Unexpected)
                }
            }
            return unavailable()
        }

        private suspend fun readHistory(): AppResult<EditHistory?> {
            val bound = bindings.getByConversation(access, request.conversationId)
            current().let { if (it is AppResult.Failed) return it }
            if (bound is AppResult.Failed) return bound
            val binding = (bound as AppResult.Success).value ?: return unavailable()
            val selected =
                resolveCollaborationSpaceHistoryLocation(access, request.conversationId, binding)
            if (selected is AppResult.Failed) return selected
            val location = (selected as AppResult.Success).value
            if (location.roomId != request.reviewed.target.roomId) return unavailable()

            val registered = registrations.observeAll(access).first()
            current().let { if (it is AppResult.Failed) return it }
            if (registered is AppResult.Failed) return registered
            val identity =
                collaborationSpaceThreadSelectedIdentity(
                    access,
                    (registered as AppResult.Success).value,
                )
            if (identity is AppResult.Failed) return identity
            val sender = (identity as AppResult.Success).value?.matrixUserId ?: return unavailable()

            val acquired =
                publications.readEditSource(access, location, request.reviewed.target.eventId)
            current().let { if (it is AppResult.Failed) return it }
            if (acquired is AppResult.Failed) return acquired
            val history = (acquired as AppResult.Success).value ?: return AppResult.Success(null)
            val originalPosition = binding.original?.conversation?.position
            val companyAccess =
                originalPosition?.let {
                    CollaborationLocalAccess(
                        access.sessionId,
                        access.audience,
                        access.accountId,
                        it.companyId,
                    )
                }
            return AppResult.Success(
                EditHistory(
                    binding,
                    location,
                    sender,
                    history,
                    companyAccess,
                    originalPosition?.conversationId,
                )
            )
        }

        private suspend fun displaySource(
            history: EditHistory
        ): AppResult<CollaborationSpaceInboxHistorySource> {
            val original = history.snapshot.original ?: return unavailable()
            val binding = history.binding
            val location = history.location
            val companyAccess = history.companyAccess
            val groupOriginal =
                original.logicalId?.let { id -> messages.get(access, id) }
                    ?: AppResult.Success(null)
            current().let { if (it is AppResult.Failed) return it }
            if (groupOriginal is AppResult.Failed) return groupOriginal
            val companyOriginal =
                if (companyAccess != null && original.logicalId != null)
                    companyMessages.get(companyAccess, original.logicalId)
                else AppResult.Success(null)
            current().let { if (it is AppResult.Failed) return it }
            if (companyOriginal is AppResult.Failed) return companyOriginal
            val resolved =
                resolveCollaborationSpaceInboxHistorySource(
                    access,
                    binding,
                    location,
                    original,
                    (groupOriginal as AppResult.Success).value,
                    (companyOriginal as AppResult.Success).value,
                )
            return resolved
        }

        private suspend fun readProjection(history: EditHistory): AppResult<Prepared?> {
            val selected = displaySource(history)
            if (selected is AppResult.Failed) return selected
            val selection = (selected as AppResult.Success).value
            val original = history.snapshot.original ?: return unavailable()
            val physical =
                CollaborationSpaceInboxSource.History(
                    (history.location as? CollaborationSpaceHistoryLocation.Original)
                        ?.access
                        ?.companyId,
                    history.location.conversationId,
                    history.location.roomId,
                    original.sender,
                    original.primaryEventId,
                )
            val proven =
                (selection as? CollaborationSpaceInboxHistorySource.Original)?.association?.source
            val sources = listOfNotNull(physical, proven).distinct()
            val companyAccess = history.companyAccess
            val indexRead = inbox.conversation(access, request.conversationId, 1)
            current().let { if (it is AppResult.Failed) return it }
            if (indexRead is AppResult.Failed) return indexRead
            val anchors = inbox.activities(access, request.conversationId, sources)
            current().let { if (it is AppResult.Failed) return it }
            if (anchors is AppResult.Failed) return anchors
            val conversation = (indexRead as AppResult.Success).value
            val originalActivity = (anchors as AppResult.Success).value
            if (conversation.version != originalActivity.version) return AppResult.Success(null)
            // Existing physical history identity survives later original-command proof.
            // Ambiguous or missing anchors require reconciliation, never a new placement.
            val source = originalActivity.values.singleOrNull()?.source ?: return unavailable()
            if (source != physical && source != proven) return unavailable(FailureKind.Unexpected)

            val group = messages.observe(access, request.conversationId).first()
            current().let { if (it is AppResult.Failed) return it }
            if (group is AppResult.Failed) return group
            val groupRecords = (group as AppResult.Success).value
            if (groupRecords.size > 128) return unavailable(FailureKind.Unexpected)
            // Another owner may have committed this same gesture after the initial lookup.
            // Re-enter the bounded exact-read path before considering a different proposal.
            if (groupRecords.any { it.intent.id == request.id }) return AppResult.Success(null)
            val company =
                if (companyAccess != null && history.companyConversationId != null)
                    companyMessages.observe(companyAccess, history.companyConversationId).first()
                else AppResult.Success(emptyList())
            current().let { if (it is AppResult.Failed) return it }
            if (company is AppResult.Failed) return company
            val prepared =
                prepareCollaborationSpaceInboxEdit(
                    access,
                    request,
                    history.sender,
                    clock.now(),
                    history.snapshot,
                    source,
                    conversation,
                    originalActivity,
                    groupRecords,
                    companyAccess,
                    history.companyConversationId,
                    (company as AppResult.Success).value,
                )
            if (prepared is AppResult.Failed) return prepared
            val proposal = (prepared as AppResult.Success).value
            val revision = history.snapshot.revision ?: return unavailable()
            return AppResult.Success(Prepared(history.location, revision, proposal))
        }
    }

    private data class EditHistory(
        val binding: CollaborationSpaceRoomBinding,
        val location: CollaborationSpaceHistoryLocation,
        val sender: String,
        val snapshot: CollaborationSpaceEditSourceSnapshot,
        val companyAccess: CollaborationLocalAccess?,
        val companyConversationId: String?,
    ) {
        override fun toString() = "EditHistory([redacted])"
    }

    private data class Prepared(
        val location: CollaborationSpaceHistoryLocation,
        val revision: String,
        val edit: CollaborationSpacePreparedEdit,
    ) {
        override fun toString() = "PreparedEdit([redacted])"
    }

    private fun unavailable(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_edit_unavailable", kind)
}
