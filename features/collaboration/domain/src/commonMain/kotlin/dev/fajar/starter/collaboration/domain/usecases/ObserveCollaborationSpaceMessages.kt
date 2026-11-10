package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Owned local pending facts. Neither observation nor credential age starts network work. */
class ObserveCollaborationSpaceMessages(
    private val messages: CollaborationSpaceMessageRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String? = null,
    ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> {
        if (conversationId != null && !validCollaborationSpaceConversationId(conversationId))
            return flowOf(
                collaborationRecoveryFailure(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            )
        return flow {
            var denial: Failure? = null
            emitAll(
                sessions
                    .observe()
                    .map { session ->
                        when (session) {
                            is AppResult.Failed -> session
                            is AppResult.Success ->
                                localCollaborationSpaceAccess(
                                    access,
                                    session.value,
                                    server.audience,
                                )
                        }
                    }
                    .distinctUntilChanged()
                    .flatMapLatest { owned ->
                        when (owned) {
                            is AppResult.Failed -> flowOf(AppResult.Failed(denial ?: owned.failure))
                            is AppResult.Success ->
                                flow<AppResult<CollaborationSpaceLocalAccess>> {
                                    directory.observeAdmission(owned.value).collect { result ->
                                        val admitted =
                                            when (result) {
                                                is AppResult.Failed ->
                                                    AppResult.Failed(denial ?: result.failure)
                                                is AppResult.Success -> {
                                                    val gate = result.value
                                                    if (
                                                        !validCollaborationSpaceDirectoryAdmission(
                                                            gate
                                                        )
                                                    )
                                                        AppResult.Failed(
                                                            denial
                                                                ?: Failure(
                                                                    FailureKind.Unexpected,
                                                                    "Invalid local admission",
                                                                    code =
                                                                        "client.collaboration_invalid_response",
                                                                )
                                                        )
                                                    else {
                                                        denial = gate.denial
                                                        gate.denial?.let { AppResult.Failed(it) }
                                                            ?: owned
                                                    }
                                                }
                                            }
                                        emit(admitted)
                                    }
                                }
                        }
                    }
                    .buffer(0)
                    .distinctUntilChanged()
                    .flatMapLatest { admitted ->
                        when (admitted) {
                            is AppResult.Failed -> flowOf(admitted)
                            is AppResult.Success -> messages.observe(admitted.value, conversationId)
                        }
                    }
                    .buffer(0)
                    .distinctUntilChanged()
            )
        }
    }
}
