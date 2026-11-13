package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One bounded account/Group command stream. No per-row subscriptions, repair scan or transport. */
class ObserveCollaborationSpaceMessages(
    private val messages: CollaborationSpaceMessageRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String? = null,
    ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> =
        flow {
                if (
                    collaborationSpaceMessageInventory(access, emptyList()) == null ||
                        conversationId != null &&
                            !validCollaborationSpaceConversationId(conversationId)
                ) {
                    emit(
                        collaborationRecoveryFailure(
                            "client.collaboration_invalid_request",
                            FailureKind.Validation,
                        )
                    )
                    return@flow
                }
                emitAll(Observation(access, conversationId).observe())
            }
            .distinctUntilChanged()

    /** One collector owns this denial latch and cancels its only command subscription. */
    private inner class Observation(
        val access: CollaborationSpaceLocalAccess,
        val conversationId: String?,
    ) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> =
            sessions
                .observe()
                .map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success ->
                            localCollaborationSpaceAccess(access, result.value, server.audience)
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { result ->
                    when (result) {
                        is AppResult.Failed -> flowOf(AppResult.Failed(denial ?: result.failure))
                        is AppResult.Success ->
                            directory.observeAdmission(access).map { admission(it, reopen = true) }
                    }
                }
                .buffer(0)
                .distinctUntilChanged()
                .flatMapLatest { result ->
                    when (result) {
                        is AppResult.Failed -> flowOf(result)
                        is AppResult.Success ->
                            messages.observe(access, conversationId).map { records ->
                                val gate = current()
                                when {
                                    gate is AppResult.Failed -> gate
                                    records is AppResult.Failed -> records
                                    else -> {
                                        val values =
                                            collaborationSpaceMessageInventory(
                                                access,
                                                (records as AppResult.Success).value,
                                            )
                                        if (
                                            values == null ||
                                                conversationId != null &&
                                                    values.any {
                                                        it.intent.conversationId != conversationId
                                                    }
                                        )
                                            collaborationRecoveryFailure(
                                                "client.collaboration_invalid_response",
                                                FailureKind.Unexpected,
                                            )
                                        else AppResult.Success(values)
                                    }
                                }
                            }
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
                    if (!validCollaborationSpaceDirectoryAdmission(gate))
                        denial?.let { AppResult.Failed(it) }
                            ?: collaborationRecoveryFailure(
                                "client.collaboration_invalid_response",
                                FailureKind.Unexpected,
                            )
                    else {
                        if (gate.denial != null || reopen) denial = gate.denial
                        denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                    }
                }
            }

        private suspend fun current(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val admitted = admission(directory.admission(access), reopen = false)
            currentCoroutineContext().ensureActive()
            if (admitted is AppResult.Failed) return admitted
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val current =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            return if (current is AppResult.Failed) current else AppResult.Success(Unit)
        }
    }
}
