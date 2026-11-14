package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only visible identities: bounded indexed metadata and contact observation, no directory scan. */
class ObserveCollaborationSpaceInboxLabels(
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val catalog: CollaborationSpaceConversationCatalogRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationIds: List<String>,
    ): Flow<AppResult<List<CollaborationSpaceInboxLabel>>> {
        if (!validCollaborationSpaceInboxLabelRequest(access, conversationIds))
            return flowOf(
                collaborationRecoveryFailure(
                    "client.collaboration_invalid_request",
                    FailureKind.Validation,
                )
            )
        val requested = conversationIds.toList()
        return flow { emitAll(Observation(access, requested).observe()) }.distinctUntilChanged()
    }

    /** This collected page owns the cache and all of its at most fifty contact subscriptions. */
    private inner class Observation(
        val access: CollaborationSpaceLocalAccess,
        val ids: List<String>,
    ) {
        var denial: Failure? = null
        var generation: String? = null
        val bindings = mutableMapOf<String, CollaborationSpaceInboxLabel>()

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<List<CollaborationSpaceInboxLabel>>> =
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
                        is AppResult.Success -> observeBindings()
                    }
                }
                .buffer(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun observeBindings(): Flow<AppResult<List<CollaborationSpaceInboxLabel>>> =
            catalog
                .observeVersion(access)
                .distinctUntilChanged()
                .mapLatest { result ->
                    if (result is AppResult.Failed) result
                    else {
                        val version = (result as AppResult.Success).value
                        if (version != null && !validCollaborationSpaceCatalogVersion(version))
                            collaborationRecoveryFailure(
                                "client.collaboration_invalid_response",
                                FailureKind.Unexpected,
                            )
                        else {
                            if (version?.generation != generation) {
                                bindings.clear()
                                generation = version?.generation
                            }
                            val captured =
                                ids.map { id ->
                                    bindings[id]
                                        ?: collaborationSpaceInboxLabelBinding(
                                                access,
                                                id,
                                                metadata.getById(access, id),
                                            )
                                            .also {
                                                currentCoroutineContext().ensureActive()
                                                if (it.peerAccountId != null && it.failure == null)
                                                    bindings[id] = it
                                            }
                                }
                            AppResult.Success(captured)
                        }
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { result ->
                    when (result) {
                        is AppResult.Failed -> flowOf(result)
                        is AppResult.Success -> {
                            val sources = result.value.map { label -> observeContact(label) }
                            if (sources.isEmpty()) flowOf(AppResult.Success(emptyList()))
                            else combine(sources) { AppResult.Success(it.filterNotNull()) }
                        }
                    }
                }
                .buffer(0)
                .map { result ->
                    val gate = current(reopen = false)
                    if (gate is AppResult.Failed) gate else result
                }

        /** Missing entries in a partial snapshot are still pending, never a contact removal. */
        private fun observeContact(
            label: CollaborationSpaceInboxLabel
        ): Flow<CollaborationSpaceInboxLabel?> =
            flow {
                    val peer = label.peerAccountId
                    if (peer == null) emit(label)
                    else {
                        emit(null)
                        emitAll(
                            directory.observeContact(access, peer).map {
                                collaborationSpaceInboxLabelContact(access, label, it)
                            }
                        )
                    }
                }
                .distinctUntilChanged()

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
