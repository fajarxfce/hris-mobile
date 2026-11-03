package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One company's advisory source changes, independent of the selected company or live expiry. */
class ObserveCollaborationNotificationChanges(
    private val changes: CollaborationNotificationChangeRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        parent: CollaborationNativeParent,
        companyId: String,
        conversationId: String,
    ): Flow<AppResult<Unit>> {
        if (!validCollaborationMessageId(companyId) || !validCollaborationMessageId(conversationId))
            return flowOf(failed(FailureKind.Validation))
        val access =
            CollaborationLocalAccess(parent.sessionId, parent.audience, parent.accountId, companyId)
        return sessions
            .observe()
            .onCompletion { cause -> if (cause == null) emit(failed(FailureKind.Unavailable)) }
            .map { observed ->
                when (observed) {
                    is AppResult.Failed -> observed
                    is AppResult.Success -> {
                        val captured = collaborationPushParent(observed.value, server, build)
                        if (captured is AppResult.Failed) captured
                        else if ((captured as AppResult.Success).value != parent)
                            failed(FailureKind.Unauthorized)
                        else AppResult.Success(Unit)
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { owned ->
                when (owned) {
                    is AppResult.Failed -> flowOf(owned)
                    is AppResult.Success ->
                        lists
                            .observeAdmission(access)
                            .onCompletion { cause ->
                                if (cause == null) emit(failed(FailureKind.Unavailable))
                            }
                            .distinctUntilChanged()
                            .flatMapLatest { gate ->
                                when (gate) {
                                    is AppResult.Failed -> flowOf(gate)
                                    is AppResult.Success ->
                                        gate.value.denial?.let { flowOf(AppResult.Failed(it)) }
                                            ?: changes
                                                .observe(access, conversationId)
                                                .map { checked(parent, access, it) }
                                                .onCompletion { cause ->
                                                    if (cause == null)
                                                        emit(
                                                            checked(
                                                                parent,
                                                                access,
                                                                failed(FailureKind.Unavailable),
                                                            )
                                                        )
                                                }
                                }
                            }
                }
            }
            .onCompletion { cause -> if (cause == null) emit(failed(FailureKind.Unavailable)) }
            .catch { cause ->
                if (cause is CancellationException) throw cause
                emit(failed(FailureKind.Unexpected))
            }
            .buffer(0)
    }

    private suspend fun checked(
        parent: CollaborationNativeParent,
        access: CollaborationLocalAccess,
        value: AppResult<Unit>,
    ): AppResult<Unit> =
        withTimeoutOrNull(8_000) {
            val gate = lists.admission(access)
            currentCoroutineContext().ensureActive()
            // A known denial carries no content or grant. Do not delay it behind a pending
            // session read or replace it with that read's timeout.
            if (gate is AppResult.Success)
                gate.value.denial?.let {
                    return@withTimeoutOrNull AppResult.Failed(it)
                }
            val current = sessions.guardPushParent(server, build, parent)
            currentCoroutineContext().ensureActive()
            if (current is AppResult.Failed) current
            else if (gate is AppResult.Failed) gate else value
        } ?: failed(FailureKind.Unavailable)

    private fun failed(kind: FailureKind) =
        collaborationSetupFailure("client.collaboration_notification_changes_unavailable", kind)
}
