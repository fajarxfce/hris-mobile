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
    private val spaces: CollaborationSpaceHistoryRepository,
    private val originals: CollaborationHistoryRepository,
    private val spaceMessages: CollaborationSpaceMessageRepository,
    private val originalMessages: CollaborationMessageRepository,
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
                        is AppResult.Success -> observeStored()
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

        private fun observeStored(): Flow<AppResult<CollaborationSpaceThreadSnapshot>> = flow {
            val admitted = current()
            if (admitted is AppResult.Failed) {
                emit(admitted)
                return@flow
            }
            val binding = bindings.getByConversation(access, conversationId)
            val selected =
                when (binding) {
                    is AppResult.Failed -> binding
                    is AppResult.Success ->
                        resolveCollaborationSpaceHistoryLocation(
                            access,
                            conversationId,
                            binding.value,
                        )
                }
            val current = current()
            if (current is AppResult.Failed) {
                emit(current)
                return@flow
            }
            if (selected is AppResult.Failed) {
                emit(selected)
                return@flow
            }
            val location = (selected as AppResult.Success).value
            emitAll(observeThread(location))
        }

        private fun observeThread(
            location: CollaborationSpaceHistoryLocation
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
            val canonicalCommands =
                spaceMessages
                    .observe(access, conversationId)
                    .map { records ->
                        when (records) {
                            is AppResult.Failed -> records
                            is AppResult.Success ->
                                collaborationSpaceThreadCommands(
                                    access,
                                    conversationId,
                                    records.value,
                                )
                        }
                    }
                    .onStart<AppResult<List<CollaborationSpaceMessageRecord>>?> { emit(null) }
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
            // An unread source is not an empty store. Known failures can publish immediately;
            // successful content still requires actual results from every selected local source.
            combine(capturedHistory, canonicalCommands, retainedCommands) {
                    history,
                    canonical,
                    original ->
                    when {
                        history is AppResult.Failed -> history
                        canonical is AppResult.Failed -> canonical
                        original is AppResult.Failed -> original
                        history == null || canonical == null || original == null -> null
                        else ->
                            AppResult.Success(
                                CollaborationSpaceThreadSnapshot(
                                    (history as AppResult.Success).value,
                                    (canonical as AppResult.Success).value,
                                    (original as AppResult.Success).value,
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
