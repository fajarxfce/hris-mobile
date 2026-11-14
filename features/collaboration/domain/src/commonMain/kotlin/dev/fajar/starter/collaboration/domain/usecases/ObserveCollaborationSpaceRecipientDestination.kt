package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Observe the existing immutable peer/canonical mapping without setup or delivery. */
class ObserveCollaborationSpaceRecipientDestination(
    private val catalog: CollaborationSpaceConversationCatalogRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): Flow<AppResult<CollaborationSpaceConversationMetadata?>> =
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
        fun observe(): Flow<AppResult<CollaborationSpaceConversationMetadata?>> =
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

        private fun content(): Flow<AppResult<CollaborationSpaceConversationMetadata?>> = flow {
            var retained:
                Pair<
                    CollaborationSpaceConversationCatalogVersion,
                    CollaborationSpaceConversationMetadata,
                >? =
                null
            val initial = current()
            if (initial is AppResult.Failed) {
                emit(initial)
                return@flow
            }
            try {
                catalog
                    .observeVersion(access)
                    .distinctUntilChanged()
                    .conflate()
                    .onCompletion { cause ->
                        if (cause == null)
                            emit(
                                collaborationRecoveryFailure(
                                    "client.collaboration_revalidation_required",
                                    FailureKind.Unavailable,
                                )
                            )
                    }
                    .collect { publication ->
                        val before = current()
                        if (before is AppResult.Failed) {
                            retained = null
                            emit(before)
                            return@collect
                        }
                        val version = (publication as? AppResult.Success)?.value
                        val read =
                            when {
                                publication is AppResult.Failed -> publication
                                version == null -> AppResult.Success(null)
                                !validCollaborationSpaceCatalogVersion(version) ->
                                    invalidDestination()
                                else -> resolve(version, retained)
                            }
                        val after = current()
                        if (after is AppResult.Failed) {
                            retained = null
                            emit(after)
                        } else {
                            val value = (read as? AppResult.Success)?.value
                            retained =
                                if (version != null && value != null) version to value else null
                            // A newer publication overtook the exact lookup. The owned version
                            // collector supplies the next frame; do not poll or restart setup.
                            if (read != null) emit(read)
                        }
                    }
            } finally {
                retained = null
            }
        }

        private suspend fun resolve(
            version: CollaborationSpaceConversationCatalogVersion,
            retained:
                Pair<
                    CollaborationSpaceConversationCatalogVersion,
                    CollaborationSpaceConversationMetadata,
                >?,
        ): AppResult<CollaborationSpaceConversationMetadata?>? {
            if (
                retained != null &&
                    retained.first.generation == version.generation &&
                    version.revision >= retained.first.revision
            )
                return AppResult.Success(retained.second)
            val read = capture(metadata.findByPeer(access, peerAccountId))
            if (read is AppResult.Failed) return read
            // The pair is immutable within a generation. Only cache it after a stable
            // published witness; absent peers must still react to later insertions.
            return when (val latest = catalog.observeVersion(access).first()) {
                is AppResult.Failed -> latest
                is AppResult.Success -> if (latest.value == version) read else null
            }
        }

        private fun capture(
            read: AppResult<CollaborationSpaceConversationMetadata?>
        ): AppResult<CollaborationSpaceConversationMetadata?> =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success -> {
                    val value = read.value
                    if (
                        value != null &&
                            (!validCollaborationSpaceConversationMetadata(access, value) ||
                                peerAccountId !in
                                    listOf(value.firstAccountId, value.secondAccountId))
                    )
                        invalidDestination()
                    else read
                }
            }

        private fun invalidDestination() =
            collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )

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
