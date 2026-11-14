package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * One account-owned history signal under current Group admission. The subscriber owns observation;
 * no history scan, key acquisition, per-room subscription or network request.
 */
class ObserveCollaborationSpaceHistoryChanges(
    private val signals: CollaborationHistorySignalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(access: CollaborationSpaceLocalAccess): Flow<AppResult<String?>> =
        flow {
                if (
                    (!validCollaborationSpaceAccountAccess(
                        CollaborationSpaceAccountAccess(
                            access.sessionId,
                            access.audience,
                            access.accountId,
                        )
                    ) || !validCollaborationSpaceConversationId(access.spaceId))
                ) {
                    emit(
                        collaborationRecoveryFailure(
                            "client.collaboration_invalid_request",
                            FailureKind.Validation,
                        )
                    )
                    return@flow
                }
                emitAll(Observation(access).observe())
            }
            .distinctUntilChanged()

    /** One collector owns this denial latch and cancels its only metadata subscription. */
    private inner class Observation(val access: CollaborationSpaceLocalAccess) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<String?>> =
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
                            signals.observe(access).map { signal ->
                                val gate = current()
                                when {
                                    gate is AppResult.Failed -> gate
                                    signal is AppResult.Failed -> signal
                                    else -> {
                                        val value = (signal as AppResult.Success).value
                                        if (value != null && !validCollaborationMessageId(value))
                                            collaborationRecoveryFailure(
                                                "client.collaboration_invalid_response",
                                                FailureKind.Unexpected,
                                            )
                                        else signal
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
