package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only a new durable request emits; advancing or acknowledging a pass cannot schedule itself. */
class ObserveCollaborationSpaceInboxRepairDemand(
    private val progress: CollaborationSpaceInboxRepairProgressRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(access: CollaborationSpaceLocalAccess): Flow<AppResult<String?>> =
        flow {
                if (!validCollaborationSpaceInboxRepairRequest(access, null, 1)) {
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
                            progress.observe(access).map { records ->
                                val gate = current()
                                when {
                                    gate is AppResult.Failed -> gate
                                    records is AppResult.Failed -> records
                                    else -> {
                                        val value = (records as AppResult.Success).value
                                        if (
                                            value != null &&
                                                !validCollaborationSpaceInboxRepairProgress(value)
                                        )
                                            collaborationRecoveryFailure(
                                                "client.collaboration_invalid_response",
                                                FailureKind.Unexpected,
                                            )
                                        else AppResult.Success(value?.latestDemand)
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
