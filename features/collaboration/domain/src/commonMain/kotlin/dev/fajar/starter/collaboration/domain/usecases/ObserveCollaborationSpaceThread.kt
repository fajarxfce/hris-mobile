package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One local subscription under Group authority, including content retained in an Original room. */
class ObserveCollaborationSpaceThread(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val spaces: CollaborationSpaceHistoryRepository,
    private val originals: CollaborationHistoryRepository,
    private val spaceMessages: CollaborationSpaceMessageRepository,
    private val originalMessages: CollaborationMessageRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val originalIdentities: CollaborationIdentityRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        window: CollaborationHistoryWindow = CollaborationHistoryWindow(),
    ): Flow<AppResult<CollaborationSpaceThreadSnapshot>> =
        flow {
                if (
                    !validCollaborationSpaceConversationId(conversationId) ||
                        !validCollaborationHistoryWindow(window)
                ) {
                    emit(
                        collaborationRecoveryFailure(
                            "client.collaboration_invalid_history",
                            FailureKind.Validation,
                        )
                    )
                    return@flow
                }
                emitAll(Observation(access, conversationId, window).observe())
            }
            .distinctUntilChanged()

    /** The collector owns its denial latch and all child reads; no process or ViewModel state. */
    private inner class Observation(
        val access: CollaborationSpaceLocalAccess,
        val conversationId: String,
        val window: CollaborationHistoryWindow,
    ) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<CollaborationSpaceThreadSnapshot>> =
            sessions
                .observe()
                .map { session ->
                    when (session) {
                        is AppResult.Failed -> session
                        is AppResult.Success ->
                            localCollaborationSpaceAccess(access, session.value, server.audience)
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { owned ->
                    when (owned) {
                        is AppResult.Failed -> flowOf(AppResult.Failed(denial ?: owned.failure))
                        is AppResult.Success ->
                            directory.observeAdmission(access).map { admission(it, reopen = true) }
                    }
                }
                .buffer(0)
                .distinctUntilChanged()
                .flatMapLatest { admitted ->
                    when (admitted) {
                        is AppResult.Failed -> flowOf(admitted)
                        is AppResult.Success -> observeBindings()
                    }
                }
                .buffer(0)
                .distinctUntilChanged()
                // Select authority and binding before the only content-producing child.
                .flatMapLatest { binding ->
                    when (binding) {
                        is AppResult.Failed -> flowOf(binding)
                        is AppResult.Success -> observeSelected(binding.value)
                    }
                }
                .buffer(0)

        private fun admission(
            result: AppResult<CollaborationSpaceDirectoryAdmission>,
            reopen: Boolean,
        ): AppResult<Unit> =
            when (result) {
                is AppResult.Failed -> AppResult.Failed(denial ?: result.failure)
                is AppResult.Success -> {
                    val gate = result.value
                    if (!validCollaborationSpaceDirectoryAdmission(gate)) {
                        denial?.let { AppResult.Failed(it) }
                            ?: collaborationRecoveryFailure(
                                "client.collaboration_invalid_response",
                                FailureKind.Unexpected,
                            )
                    } else {
                        if (gate.denial != null || reopen) denial = gate.denial
                        denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                    }
                }
            }

        private suspend fun current(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = admission(directory.admission(access), reopen = false)
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Failed) return gate
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            return if (owned is AppResult.Failed) owned else AppResult.Success(Unit)
        }

        private fun observeBindings(): Flow<AppResult<CollaborationSpaceRoomBinding?>> = flow {
            val admitted = current()
            if (admitted is AppResult.Failed) {
                emit(admitted)
                return@flow
            }
            emitAll(bindings.observeByConversation(access, conversationId))
        }

        private fun observeSelected(
            binding: CollaborationSpaceRoomBinding?
        ): Flow<AppResult<CollaborationSpaceThreadSnapshot>> = flow {
            val admitted = current()
            if (admitted is AppResult.Failed) {
                emit(admitted)
                return@flow
            }
            if (binding == null) {
                emitAll(observeUnresolved())
                return@flow
            }
            when (
                val selected =
                    resolveCollaborationSpaceHistoryLocation(access, conversationId, binding)
            ) {
                is AppResult.Failed -> emit(selected)
                is AppResult.Success -> emitAll(observeThread(binding.conversation, selected.value))
            }
        }

        private fun observeUnresolved(): Flow<AppResult<CollaborationSpaceThreadSnapshot>> = flow {
            coroutineScope {
                // One finite read shared by the canonical facts and peer lookup. The selected
                // binding child owns and joins it, including late non-cancellable storage reads.
                val stored = async {
                    when (val read = metadata.getById(access, conversationId)) {
                        is AppResult.Failed -> read
                        is AppResult.Success -> {
                            val value = read.value
                            when {
                                value == null ->
                                    collaborationRecoveryFailure(
                                        "client.collaboration_conversation_unavailable",
                                        FailureKind.Unavailable,
                                    )
                                value.id != conversationId ||
                                    !validCollaborationSpaceConversationMetadata(access, value) ->
                                    collaborationRecoveryFailure(
                                        "client.collaboration_invalid_response",
                                        FailureKind.Unexpected,
                                    )
                                else -> AppResult.Success(value)
                            }
                        }
                    }
                }
                val context = flow { emit(stored.await()) }
                combine(
                        context.onStart<AppResult<CollaborationSpaceConversationMetadata>?> {
                            emit(null)
                        },
                        observeCanonicalCommands(),
                        observeParticipants(context, null).onStart<
                            CollaborationSpaceThreadParticipants?
                        > {
                            emit(null)
                        },
                    ) { facts, queued, participants ->
                        when {
                            facts is AppResult.Failed -> facts
                            queued is AppResult.Failed -> queued
                            facts == null || queued == null || participants == null -> null
                            else ->
                                AppResult.Success(
                                    CollaborationSpaceThreadSnapshot.Unresolved(
                                        access,
                                        (facts as AppResult.Success).value,
                                        (queued as AppResult.Success).value,
                                        participants,
                                    )
                                )
                        }
                    }
                    .filterNotNull()
                    .collect { snapshot ->
                        val allowed = current()
                        emit(if (allowed is AppResult.Failed) allowed else snapshot)
                    }
            }
        }

        /**
         * Cold read owned by the selected binding child; the same validation serves both origins.
         */
        private fun observeCanonicalCommands():
            Flow<AppResult<List<CollaborationSpaceMessageRecord>>?> =
            spaceMessages
                .observe(access, conversationId)
                .map { records ->
                    when (records) {
                        is AppResult.Failed -> records
                        is AppResult.Success ->
                            collaborationSpaceThreadCommands(access, conversationId, records.value)
                    }
                }
                .onStart<AppResult<List<CollaborationSpaceMessageRecord>>?> { emit(null) }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun observeParticipants(
            conversation: Flow<AppResult<CollaborationSpaceConversationMetadata>>,
            location: CollaborationSpaceHistoryLocation?,
        ): Flow<CollaborationSpaceThreadParticipants> {
            val contacts =
                conversation.flatMapLatest { facts ->
                    when (facts) {
                        is AppResult.Failed -> flowOf(facts)
                        is AppResult.Success -> {
                            val metadata = facts.value
                            val peer =
                                if (metadata.firstAccountId == access.accountId)
                                    metadata.secondAccountId
                                else metadata.firstAccountId
                            directory.observeContact(access, peer).map { contact ->
                                when (contact) {
                                    is AppResult.Failed -> contact
                                    is AppResult.Success ->
                                        collaborationSpaceThreadContact(access, peer, contact.value)
                                }
                            }
                        }
                    }
                }
            val selected =
                registrations.observeAll(access).map { records ->
                    when (records) {
                        is AppResult.Failed -> records
                        is AppResult.Success ->
                            collaborationSpaceThreadSelectedIdentity(access, records.value)
                    }
                }
            val original = flow {
                val read =
                    if (location is CollaborationSpaceHistoryLocation.Original)
                        originalIdentities.current(location.access)
                    else AppResult.Success(null)
                emit(
                    when (read) {
                        is AppResult.Failed -> read
                        is AppResult.Success -> {
                            val identity = read.value
                            if (
                                identity != null &&
                                    !validCollaborationHistorySender(identity.matrixUserId)
                            )
                                collaborationRecoveryFailure(
                                    "client.collaboration_invalid_response",
                                    FailureKind.Unexpected,
                                )
                            else read
                        }
                    }
                )
            }
            return combine(contacts, selected, original, ::CollaborationSpaceThreadParticipants)
                .distinctUntilChanged()
        }

        private fun observeThread(
            conversation: CollaborationSpaceConversationMetadata,
            location: CollaborationSpaceHistoryLocation,
        ): Flow<AppResult<CollaborationSpaceThreadSnapshot>> = flow {
            val pages =
                when (location) {
                    is CollaborationSpaceHistoryLocation.Space ->
                        spaces.observe(
                            location.access,
                            location.conversationId,
                            location.roomId,
                            window.after,
                            window.limit,
                            window.before,
                            window.overlap,
                        )
                    is CollaborationSpaceHistoryLocation.Original ->
                        originals.observe(
                            location.access,
                            location.conversationId,
                            location.roomId,
                            window.after,
                            window.limit,
                            window.before,
                            window.overlap,
                        )
                }
            val capturedHistory =
                pages
                    .map { page ->
                        when (page) {
                            is AppResult.Failed -> page
                            is AppResult.Success ->
                                collaborationSpaceThreadHistory(
                                    access,
                                    conversationId,
                                    location,
                                    window,
                                    page.value,
                                )
                        }
                    }
                    .onStart<AppResult<CollaborationSpaceThreadHistory>?> { emit(null) }
            val retainedCommands =
                when (location) {
                    is CollaborationSpaceHistoryLocation.Space ->
                        flowOf(AppResult.Success(emptyList<CollaborationMessageRecord>()))
                    is CollaborationSpaceHistoryLocation.Original ->
                        originalMessages.observe(location.access, location.conversationId).map {
                            records ->
                            when (records) {
                                is AppResult.Failed -> records
                                is AppResult.Success ->
                                    collaborationSpaceOriginalThreadCommands(
                                        location,
                                        records.value,
                                    )
                            }
                        }
                }.onStart<AppResult<List<CollaborationMessageRecord>>?> { emit(null) }
            // First content carries actual local participant results, avoiding bubble-side changes
            // from synthetic unread identities. Known content/command failures still publish early.
            combine(
                    capturedHistory,
                    observeCanonicalCommands(),
                    retainedCommands,
                    observeParticipants(flowOf(AppResult.Success(conversation)), location).onStart<
                        CollaborationSpaceThreadParticipants?
                    > {
                        emit(null)
                    },
                ) { history, canonical, original, participants ->
                    when {
                        history is AppResult.Failed -> history
                        canonical is AppResult.Failed -> canonical
                        original is AppResult.Failed -> original
                        history == null ||
                            canonical == null ||
                            original == null ||
                            participants == null -> null
                        else ->
                            AppResult.Success(
                                CollaborationSpaceThreadSnapshot.Bound(
                                    (history as AppResult.Success).value,
                                    (canonical as AppResult.Success).value,
                                    (original as AppResult.Success).value,
                                    conversation,
                                    participants,
                                )
                            )
                    }
                }
                .filterNotNull()
                .collect { snapshot ->
                    val allowed = current()
                    emit(if (allowed is AppResult.Failed) allowed else snapshot)
                }
        }
    }
}
