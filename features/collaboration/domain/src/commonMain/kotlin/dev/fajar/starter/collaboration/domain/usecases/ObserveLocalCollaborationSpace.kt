package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Current local account/root admission, independent of SDK readiness and selected HR company. */
class ObserveLocalCollaborationSpace(
    private val roots: CollaborationSpaceRootRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<CollaborationSpaceLocalAccess?>> =
        sessions
            .observe()
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        currentCollaborationSpaceAccountAccess(result.value, server.audience)
                }
            }
            .onCompletion { cause -> if (cause == null) emit(AppResult.Success(null)) }
            .distinctUntilChanged()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success ->
                        result.value?.let { account -> observeRoot(account) }
                            ?: flowOf(AppResult.Success(null))
                }
            }
            .buffer(0)
            .distinctUntilChanged()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success ->
                        result.value?.let { Observation(it).observe() }
                            ?: flowOf(AppResult.Success(null))
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun observeRoot(
        account: CollaborationSpaceAccountAccess
    ): Flow<AppResult<CollaborationSpaceLocalAccess?>> =
        roots
            .observe(account)
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> {
                        val root = result.value
                        when {
                            root == null -> AppResult.Success(null)
                            !validCollaborationMessageId(root.spaceId) -> invalidLocalSpace()
                            else ->
                                AppResult.Success(
                                    CollaborationSpaceLocalAccess(
                                        account.sessionId,
                                        account.audience,
                                        account.accountId,
                                        root.spaceId,
                                    )
                                )
                        }
                    }
                }
            }
            // Cancel the previous admission/consumer before waiting for another account's root.
            .onStart { emit(AppResult.Success(null)) }
            .onCompletion { cause -> if (cause == null) emit(AppResult.Success(null)) }

    /** One current Group admission stream owns its denial latch; only admitted facts reopen it. */
    private inner class Observation(val access: CollaborationSpaceLocalAccess) {
        var denial: Failure? = null

        fun observe(): Flow<AppResult<CollaborationSpaceLocalAccess?>> =
            directory
                .observeAdmission(access)
                .map { observed ->
                    if (observed is AppResult.Failed) AppResult.Failed(denial ?: observed.failure)
                    else {
                        val gate = (observed as AppResult.Success).value
                        if (!validCollaborationSpaceDirectoryAdmission(gate))
                            denial?.let { AppResult.Failed(it) } ?: invalidLocalSpace()
                        else if (gate.denial != null) {
                            denial = gate.denial
                            AppResult.Failed(requireNotNull(denial))
                        } else current()
                    }
                }
                .onStart { emit(AppResult.Success(null)) }
                .onCompletion { cause -> if (cause == null) emit(AppResult.Success(null)) }

        private suspend fun current(): AppResult<CollaborationSpaceLocalAccess?> {
            currentCoroutineContext().ensureActive()
            val before = sessions.current()
            currentCoroutineContext().ensureActive()
            if (before is AppResult.Failed) return AppResult.Failed(denial ?: before.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (before as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return owned
            val admission = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (admission is AppResult.Failed) return AppResult.Failed(denial ?: admission.failure)
            val gate = (admission as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate))
                return denial?.let { AppResult.Failed(it) } ?: invalidLocalSpace()
            if (gate.denial != null) {
                denial = gate.denial
                return AppResult.Failed(requireNotNull(denial))
            }
            val account =
                CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
            val root = roots.current(account)
            currentCoroutineContext().ensureActive()
            if (root is AppResult.Failed) return AppResult.Failed(denial ?: root.failure)
            if ((root as AppResult.Success).value?.spaceId != access.spaceId)
                return AppResult.Success(null)
            val after = sessions.current()
            currentCoroutineContext().ensureActive()
            if (after is AppResult.Failed) return AppResult.Failed(denial ?: after.failure)
            val latest =
                localCollaborationSpaceAccess(
                    access,
                    (after as AppResult.Success).value,
                    server.audience,
                )
            if (latest is AppResult.Failed) return latest
            denial = null
            return AppResult.Success(access)
        }
    }
}

private fun invalidLocalSpace() =
    collaborationRecoveryFailure("client.collaboration_invalid_response", FailureKind.Unexpected)
