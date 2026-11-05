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

/** Observe local quote targets, including deletion outside the currently displayed history page. */
class ObserveCollaborationQuotes(
    private val quotes: CollaborationQuoteRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        expectedAccess: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ): Flow<AppResult<List<CollaborationQuotedMessage>>> {
        if (!validCollaborationQuoteQuery(conversationId, roomId, eventIds))
            return flowOf(
                collaborationSetupFailure(
                    "client.collaboration_invalid_message",
                    FailureKind.Validation,
                )
            )
        val selected = eventIds.toList()
        return sessions
            .observeMessageAccess(workspaceId, workspaces, server, build, clock)
            .flatMapLatest { owner ->
                when {
                    owner is AppResult.Failed -> flowOf(owner)
                    (owner as AppResult.Success).value != expectedAccess ->
                        flowOf(
                            collaborationSetupFailure(
                                "client.session_changed",
                                FailureKind.Unauthorized,
                            )
                        )
                    else ->
                        lists.observeAdmission(expectedAccess).flatMapLatest { gate ->
                            when (gate) {
                                is AppResult.Failed -> flowOf(gate)
                                is AppResult.Success ->
                                    gate.value.denial?.let { flowOf(AppResult.Failed(it)) }
                                        ?: quotes
                                            .observeQuotes(
                                                expectedAccess,
                                                conversationId,
                                                roomId,
                                                selected,
                                            )
                                            .map {
                                                checked(workspaceId, expectedAccess, selected, it)
                                            }
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
    }

    private suspend fun checked(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        selected: List<String>,
        result: AppResult<List<CollaborationQuotedMessage>>,
    ): AppResult<List<CollaborationQuotedMessage>> {
        val gate = lists.admission(expected)
        val current = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        if (result is AppResult.Success) {
            if (
                result.value.size > selected.size ||
                    result.value.map { it.eventId }.distinct().size != result.value.size ||
                    result.value.any {
                        it.eventId !in selected ||
                            !validCollaborationHistorySender(it.sender) ||
                            !validCollaborationText(it.text)
                    }
            )
                return collaborationSetupFailure(
                    "client.collaboration_invalid_message",
                    FailureKind.Storage,
                )
            return AppResult.Success(result.value.toList())
        }
        return result
    }
}
