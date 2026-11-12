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
class ObserveCollaborationSpaceThreadHistory(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val spaces: CollaborationSpaceHistoryRepository,
    private val originals: CollaborationHistoryRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        window: CollaborationHistoryWindow = CollaborationHistoryWindow(),
    ): Flow<AppResult<CollaborationSpaceThreadHistory>> =
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
        fun observe(): Flow<AppResult<CollaborationSpaceThreadHistory>> =
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

        private fun observeStored(): Flow<AppResult<CollaborationSpaceThreadHistory>> = flow {
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
            pages.collect { page ->
                val snapshot =
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
                val allowed = current()
                emit(if (allowed is AppResult.Failed) allowed else snapshot)
            }
        }
    }
}
