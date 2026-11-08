package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Parallel scoped local reads with one publication gate; no API, SDK, mutation or retry. */
class ObserveCollaborationThread(
    private val history: CollaborationHistoryRepository,
    private val messages: CollaborationMessageRepository,
    private val lists: CollaborationListRepository,
    private val identities: CollaborationIdentityRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val recipientObservations: CollaborationRecipientObservationRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        conversationId: String,
        window: CollaborationHistoryWindow,
    ): Flow<AppResult<CollaborationRead<CollaborationThreadSnapshot>>> =
        flow {
                if (
                    !validCollaborationMessageId(conversationId) ||
                        !validCollaborationHistoryWindow(window)
                ) {
                    emit(
                        collaborationSetupFailure(
                            "client.collaboration_invalid_history",
                            FailureKind.Validation,
                        )
                    )
                    return@flow
                }
                val identity = sessions.current()
                currentCoroutineContext().ensureActive()
                if (identity is AppResult.Failed) {
                    emit(identity)
                    return@flow
                }
                val session = (identity as AppResult.Success).value
                val captured = collaborationCapturedLocalAccess(expected, session, server.audience)
                if (captured is AppResult.Failed) {
                    emit(captured)
                    return@flow
                }
                // Reads stay private and account-bound until continuation verifies the selected
                // workspace and admission after the snapshot. Do not decrypt the same workspace
                // again before starting these reads; the caller already observes local ownership.
                val owner =
                    CollaborationOwner(
                        expected.sessionId,
                        expected.audience,
                        expected.accountId,
                        expected.companyId,
                        requireNotNull(session).binding.serverId,
                    )
                emitAll(
                    lists.observeAdmission(expected).flatMapLatest { gate ->
                        when (gate) {
                            is AppResult.Failed -> flowOf(gate)
                            is AppResult.Success ->
                                gate.value.denial?.let { flowOf(AppResult.Failed(it)) }
                                    ?: observeStored(workspaceId, owner, conversationId, window)
                        }
                    }
                )
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun observeStored(
        workspaceId: String,
        owner: CollaborationOwner,
        conversationId: String,
        window: CollaborationHistoryWindow,
    ): Flow<AppResult<CollaborationRead<CollaborationThreadSnapshot>>> {
        val access = owner.localListAccess()
        val context = flow { emit(readStoredContext(owner, conversationId)) }
        val pages =
            history.observe(
                access,
                conversationId,
                after = window.after,
                before = window.before,
                limit = window.limit,
                overlap = window.overlap,
            )
        val commands = messages.observe(access, conversationId)
        // Optional display metadata must not hold the first local message window hostage.
        val recipient =
            recipientObservations
                .observe(access, conversationId)
                .map { observed ->
                    (observed as? AppResult.Success)?.value?.let {
                        validCollaborationRecipientObservation(it) &&
                            it.conversationId == conversationId &&
                            !it.ready
                    } == true
                }
                .onStart { emit(false) }
                .distinctUntilChanged()
        return combine(context, pages, commands, recipient) {
                contextResult,
                pageResult,
                commandResult,
                recipientResult ->
                // Freeze bounded repository values before the suspending publication guard.
                val snapshot =
                    when {
                        contextResult is AppResult.Failed -> contextResult
                        pageResult is AppResult.Failed -> pageResult
                        commandResult is AppResult.Failed -> commandResult
                        else ->
                            collaborationThreadSnapshot(
                                access,
                                conversationId,
                                window,
                                (contextResult as AppResult.Success).value,
                                (pageResult as AppResult.Success).value,
                                (commandResult as AppResult.Success).value,
                            )
                    }
                val current = continuation(workspaceId, owner)
                when {
                    current is AppResult.Failed -> current
                    snapshot is AppResult.Failed -> snapshot
                    else ->
                        AppResult.Success(
                            CollaborationRead(
                                owner,
                                (snapshot as AppResult.Success)
                                    .value
                                    .copy(recipientUnavailable = recipientResult),
                            )
                        )
                }
            }
            .buffer(0)
    }

    private suspend fun readStoredContext(
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<CollaborationThreadContext> {
        val access = owner.localListAccess()
        val stored = lists.get(access, conversationId)
        currentCoroutineContext().ensureActive()
        if (stored is AppResult.Failed) return stored
        val summary = (stored as AppResult.Success).value
        val allowed = collaborationThreadContext(owner, conversationId, summary, null)
        if (allowed is AppResult.Failed) return allowed
        val known = identities.current(access)
        currentCoroutineContext().ensureActive()
        if (known is AppResult.Failed) return known
        return collaborationThreadContext(
            owner,
            conversationId,
            summary,
            (known as AppResult.Success).value,
        )
    }

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(owner.localListAccess())
        val current =
            sessions.captureCollaborationLocalOwner(workspaceId, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val checked = requireCollaborationOwner(current, owner, gate)
        if (checked is AppResult.Failed) return checked
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }
}
