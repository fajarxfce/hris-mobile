package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Observe owned recipient draft and originals without canonical setup or native acquisition. */
class ObserveCollaborationSpaceRecipientProjection(
    private val projections: CollaborationSpaceRecipientProjectionRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): Flow<AppResult<CollaborationSpaceRecipientProjection>> =
        flow {
                if (!validCollaborationSpaceRecipientCoordinates(access, peerAccountId)) {
                    emit(invalidDraft())
                    return@flow
                }
                emitAll(Observation(access, peerAccountId).observe())
            }
            .distinctUntilChanged()

    /** Each collector owns its denial latch and joins obsolete content readers. */
    private inner class Observation(
        val access: CollaborationSpaceLocalAccess,
        val peerAccountId: String,
    ) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<CollaborationSpaceRecipientProjection>> =
            sessions
                .observe()
                .onCompletion { cause -> if (cause == null) emit(AppResult.Success(null)) }
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
                            directory
                                .observeAdmission(access)
                                .map { admission(it, reopen = true) }
                                .onCompletion { cause ->
                                    if (cause == null)
                                        emit(
                                            denial?.let { AppResult.Failed(it) }
                                                ?: collaborationRecoveryFailure(
                                                    "client.collaboration_revalidation_required",
                                                    FailureKind.Unavailable,
                                                )
                                        )
                                }
                    }
                }
                .buffer(0)
                .distinctUntilChanged()
                .flatMapLatest { allowed ->
                    when (allowed) {
                        is AppResult.Failed -> flowOf(allowed)
                        is AppResult.Success -> content()
                    }
                }
                .buffer(0)
                .distinctUntilChanged()

        private fun content(): Flow<AppResult<CollaborationSpaceRecipientProjection>> = flow {
            val allowed = current()
            if (allowed is AppResult.Failed) {
                emit(allowed)
                return@flow
            }
            projections.observe(access, peerAccountId).map(::captureProjection).collect { captured
                ->
                val guard = current()
                emit(if (guard is AppResult.Failed) guard else captured)
            }
        }

        private fun captureProjection(
            read: AppResult<CollaborationSpaceRecipientProjection>
        ): AppResult<CollaborationSpaceRecipientProjection> =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success -> {
                    captureCollaborationSpaceRecipientProjection(peerAccountId, read.value)
                }
            }

        private fun admission(
            read: AppResult<CollaborationSpaceDirectoryAdmission>,
            reopen: Boolean,
        ): AppResult<Unit> =
            when (read) {
                is AppResult.Failed -> AppResult.Failed(denial ?: read.failure)
                is AppResult.Success -> {
                    val gate = read.value
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
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
            val gate = admission(directory.admission(access), reopen = false)
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Failed) return gate
            val latest = sessions.current()
            currentCoroutineContext().ensureActive()
            if (latest is AppResult.Failed) return AppResult.Failed(denial ?: latest.failure)
            return localCollaborationSpaceAccess(
                    access,
                    (latest as AppResult.Success).value,
                    server.audience,
                )
                .let { if (it is AppResult.Failed) it else AppResult.Success(Unit) }
        }
    }
}
