package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Local facts only. Credential age and network availability do not schedule or hide data. */
class ObserveCollaborationSpaceDirectory(
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    /** One owned public contact, without a directory inventory or canonical conversation. */
    fun contact(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): Flow<AppResult<CollaborationSpaceContact?>> =
        flow {
                if (!validCollaborationSpaceRecipientCoordinates(access, peerAccountId)) {
                    emit(
                        collaborationRecoveryFailure(
                            "client.collaboration_conversation_unavailable",
                            FailureKind.Validation,
                        )
                    )
                    return@flow
                }
                emitAll(ContactObservation(access, peerAccountId).observe())
            }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceDirectorySnapshot>> =
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
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success ->
                        directory.observeAdmission(result.value).map { admission ->
                            when (admission) {
                                is AppResult.Failed -> admission
                                is AppResult.Success -> {
                                    val gate = admission.value
                                    when {
                                        !validCollaborationSpaceDirectoryAdmission(gate) ->
                                            collaborationRecoveryFailure(
                                                "client.collaboration_invalid_response",
                                                FailureKind.Unexpected,
                                            )
                                        gate.denial != null ->
                                            AppResult.Failed(requireNotNull(gate.denial))
                                        else -> result
                                    }
                                }
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success -> directory.observe(access.value)
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    /** A collector owns its denial latch and joins obsolete exact-contact readers. */
    private inner class ContactObservation(
        val access: CollaborationSpaceLocalAccess,
        val peerAccountId: String,
    ) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<CollaborationSpaceContact?>> =
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

        private fun content(): Flow<AppResult<CollaborationSpaceContact?>> = flow {
            val allowed = current()
            if (allowed is AppResult.Failed) {
                emit(allowed)
                return@flow
            }
            directory
                .observeContact(access, peerAccountId)
                .map { result ->
                    when (result) {
                        is AppResult.Failed -> {
                            if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                                denial = result.failure
                            result
                        }
                        is AppResult.Success ->
                            collaborationSpaceThreadContact(access, peerAccountId, result.value)
                    }
                }
                .collect { captured ->
                    val guard = current()
                    emit(if (guard is AppResult.Failed) guard else captured)
                }
        }

        private fun admission(
            result: AppResult<CollaborationSpaceDirectoryAdmission>,
            reopen: Boolean,
        ): AppResult<Unit> =
            when (result) {
                is AppResult.Failed -> {
                    if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                        denial = result.failure
                    AppResult.Failed(denial ?: result.failure)
                }
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
            val first = sessions.current()
            currentCoroutineContext().ensureActive()
            if (first is AppResult.Failed) return AppResult.Failed(denial ?: first.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (first as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
            val gate = admission(directory.admission(access), reopen = false)
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Failed) return gate
            val last = sessions.current()
            currentCoroutineContext().ensureActive()
            if (last is AppResult.Failed) return AppResult.Failed(denial ?: last.failure)
            return localCollaborationSpaceAccess(
                    access,
                    (last as AppResult.Success).value,
                    server.audience,
                )
                .let { if (it is AppResult.Failed) it else AppResult.Success(Unit) }
        }
    }
}
