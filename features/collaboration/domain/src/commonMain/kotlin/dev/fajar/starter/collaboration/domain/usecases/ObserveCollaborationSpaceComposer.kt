package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Local editing is observed independently of message history and native connection readiness. */
class ObserveCollaborationSpaceComposer(
    private val drafts: CollaborationSpaceDraftRepository,
    private val outbox: CollaborationSpaceComposerOutboxRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): Flow<AppResult<CollaborationSpaceComposerSnapshot>> =
        flow {
                if (!validCollaborationSpaceConversationId(conversationId)) {
                    emit(invalidDraft())
                    return@flow
                }
                emitAll(Observation(access, conversationId).observe())
            }
            .distinctUntilChanged()

    /** Each collector owns its denial latch and joins obsolete content readers. */
    private inner class Observation(
        val access: CollaborationSpaceLocalAccess,
        val conversationId: String,
    ) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<CollaborationSpaceComposerSnapshot>> =
            sessions
                .observe()
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
                            directory.observeAdmission(access).map { admission(it, reopen = true) }
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

        private fun content(): Flow<AppResult<CollaborationSpaceComposerSnapshot>> = flow {
            val allowed = current()
            if (allowed is AppResult.Failed) {
                emit(allowed)
                return@flow
            }
            combine(
                    drafts.observe(access, conversationId).map(::captureDraft).onStart<
                        AppResult<CollaborationDraft?>?
                    > {
                        emit(null)
                    },
                    outbox.observe(access).map(::captureEntries).onStart<
                        AppResult<List<CollaborationComposerOutboxEntry>>?
                    > {
                        emit(null)
                    },
                ) { editor, retained ->
                    when {
                        editor is AppResult.Failed -> editor
                        retained is AppResult.Failed -> retained
                        editor == null || retained == null -> null
                        else ->
                            AppResult.Success(
                                CollaborationSpaceComposerSnapshot(
                                    (editor as AppResult.Success).value,
                                    (retained as AppResult.Success).value,
                                )
                            )
                    }
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { captured ->
                    val guard = current()
                    emit(if (guard is AppResult.Failed) guard else captured)
                }
        }

        private fun captureDraft(
            read: AppResult<CollaborationDraft?>
        ): AppResult<CollaborationDraft?> =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success -> {
                    val draft = read.value
                    if (
                        draft != null &&
                            (!validCollaborationDraft(draft) ||
                                draft.conversationId != conversationId)
                    )
                        collaborationRecoveryFailure(
                            "client.collaboration_invalid_response",
                            FailureKind.Unexpected,
                        )
                    else read
                }
            }

        private fun captureEntries(
            read: AppResult<List<CollaborationComposerOutboxEntry>>
        ): AppResult<List<CollaborationComposerOutboxEntry>> =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success -> {
                    val entries = read.value.toList()
                    if (
                        entries.size > 128 ||
                            entries.map { it.submission.id }.distinct().size != entries.size ||
                            entries.any {
                                !validCollaborationComposerSubmission(it.submission) ||
                                    !validCollaborationSpaceMessageIntent(
                                        access,
                                        collaborationSpaceComposerIntent(access, it.submission),
                                    )
                            }
                    )
                        collaborationRecoveryFailure(
                            "client.collaboration_invalid_response",
                            FailureKind.Unexpected,
                        )
                    else
                        AppResult.Success(
                            entries.filter {
                                it.submission.original.conversationId == conversationId
                            }
                        )
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
