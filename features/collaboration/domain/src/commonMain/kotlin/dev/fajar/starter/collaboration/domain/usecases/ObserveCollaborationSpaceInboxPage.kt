package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One bounded local recency page. No discovery, native acquisition or automatic pagination. */
class ObserveCollaborationSpaceInboxPage(
    private val inbox: CollaborationSpaceInboxRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        limit: Int = 50,
    ): Flow<AppResult<CollaborationSpaceInboxPage>> =
        flow {
                if (!validCollaborationSpaceInboxPageRequest(access, after, limit)) {
                    emit(
                        collaborationRecoveryFailure(
                            "client.collaboration_invalid_request",
                            FailureKind.Validation,
                        )
                    )
                    return@flow
                }
                emitAll(Observation(access, after, limit).observe())
            }
            .distinctUntilChanged()

    /** The collected page owns its admission latch and its sole index subscription. */
    private inner class Observation(
        val access: CollaborationSpaceLocalAccess,
        val after: String?,
        val limit: Int,
    ) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<CollaborationSpaceInboxPage>> =
            sessions
                .observe()
                .map { result ->
                    when (result) {
                        is AppResult.Failed -> AppResult.Failed(denial ?: result.failure)
                        is AppResult.Success ->
                            localCollaborationSpaceAccess(access, result.value, server.audience)
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { owner ->
                    when (owner) {
                        is AppResult.Failed -> flowOf(owner)
                        is AppResult.Success ->
                            directory.observeAdmission(access).map { observed ->
                                val gate = admission(observed)
                                if (gate is AppResult.Failed) gate else current(reopen = true)
                            }
                    }
                }
                .buffer(0)
                .distinctUntilChanged()
                .flatMapLatest { gate ->
                    when (gate) {
                        is AppResult.Failed -> flowOf(gate)
                        is AppResult.Success ->
                            inbox.observePage(access, after, limit).map { result ->
                                // Detach a caller-owned list before any admission read can suspend.
                                val snapshot =
                                    when (result) {
                                        is AppResult.Failed -> result
                                        is AppResult.Success -> {
                                            val value = result.value
                                            if (
                                                value is CollaborationSpaceInboxPage.Items &&
                                                    value.entries.size > limit
                                            )
                                                collaborationRecoveryFailure(
                                                    "client.collaboration_invalid_response",
                                                    FailureKind.Unexpected,
                                                )
                                            else
                                                AppResult.Success(
                                                    if (value is CollaborationSpaceInboxPage.Items)
                                                        value.copy(entries = value.entries.toList())
                                                    else value
                                                )
                                        }
                                    }
                                val current = current(reopen = false)
                                when {
                                    current is AppResult.Failed -> current
                                    snapshot is AppResult.Failed -> snapshot
                                    else -> {
                                        val page = (snapshot as AppResult.Success).value
                                        if (
                                            validCollaborationSpaceInboxPage(
                                                access,
                                                after,
                                                limit,
                                                page,
                                            )
                                        )
                                            snapshot
                                        else
                                            collaborationRecoveryFailure(
                                                "client.collaboration_invalid_response",
                                                FailureKind.Unexpected,
                                            )
                                    }
                                }
                            }
                    }
                }
                .buffer(0)

        private fun admission(
            result: AppResult<CollaborationSpaceDirectoryAdmission>
        ): AppResult<Unit> =
            when (result) {
                is AppResult.Failed -> AppResult.Failed(denial ?: result.failure)
                is AppResult.Success -> {
                    val gate = result.value
                    when {
                        !validCollaborationSpaceDirectoryAdmission(gate) ->
                            denial?.let { AppResult.Failed(it) }
                                ?: collaborationRecoveryFailure(
                                    "client.collaboration_invalid_response",
                                    FailureKind.Unexpected,
                                )
                        gate.denial != null -> {
                            denial = gate.denial
                            AppResult.Failed(requireNotNull(denial))
                        }
                        else -> AppResult.Success(Unit)
                    }
                }
            }

        private suspend fun current(reopen: Boolean): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = admission(directory.admission(access))
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Failed) return gate
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val owner =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            if (owner is AppResult.Failed) return owner
            if (reopen) denial = null
            return denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
    }
}
