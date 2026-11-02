package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared admission check, without performing a draft operation or invoking another use case. */
internal suspend fun SessionRepository.checkDraftContinuation(
    workspaceId: String,
    access: CollaborationLocalAccess,
    lists: CollaborationListRepository,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<Unit> =
    checkDraftContinuation(
        CollaborationLocalScope.Selected(workspaceId),
        access,
        lists,
        workspaces,
        server,
        build,
        clock,
    )

internal suspend fun SessionRepository.checkDraftContinuation(
    scope: CollaborationLocalScope,
    access: CollaborationLocalAccess,
    lists: CollaborationListRepository,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<Unit> {
    currentCoroutineContext().ensureActive()
    val gate = lists.admission(access)
    val current = captureMessageAccess(scope, workspaces, server, build, clock)
    currentCoroutineContext().ensureActive()
    if (gate is AppResult.Success)
        gate.value.denial?.let {
            return AppResult.Failed(it)
        }
    if (current is AppResult.Failed) return current
    if ((current as AppResult.Success).value != access)
        return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
    if (gate is AppResult.Failed) return gate
    return AppResult.Success(Unit)
}

internal fun ownsDraftConversation(
    access: CollaborationLocalAccess,
    conversationId: String,
    value: CollaborationDirectSummary?,
): Boolean {
    val conversation = value?.conversation ?: return false
    return conversation.id == conversationId &&
        conversation.companyId == access.companyId &&
        access.accountId in listOf(conversation.firstAccountId, conversation.secondAccountId)
}

internal fun changedDraft() =
    collaborationSetupFailure("client.collaboration_draft_changed", FailureKind.Conflict)

internal fun invalidDraft() =
    collaborationSetupFailure("client.collaboration_invalid_message", FailureKind.Validation)
