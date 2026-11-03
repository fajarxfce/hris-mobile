package dev.fajar.starter.app.navigation

import dev.fajar.starter.collaboration.domain.entities.CollaborationNotificationAction
import dev.fajar.starter.collaboration.domain.entities.CollaborationNotificationInvocation
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlinx.coroutines.*

/** Application orchestration; a tap never queues a message or marks it read. */
class NativeNotificationNavigation(
    private val open:
        suspend (ActionNotificationInput) -> AppResult<CollaborationNotificationInvocation>,
    private val select: suspend (String, String, WorkspaceSelectionOrigin) -> AppResult<Workspace>,
) {
    suspend operator fun invoke(
        input: ActionNotificationInput,
        origin: WorkspaceSelectionOrigin,
        workspace: Workspace?,
    ): AppResult<PendingAppLink> {
        currentCoroutineContext().ensureActive()
        if (input.kind != ActionNotificationKind.Open || input.replyText != null)
            return unavailable(FailureKind.Validation)
        if (workspace?.id != origin.workspaceId) return unavailable(FailureKind.Conflict)
        return withTimeoutOrNull(15_000) { resolve(input, origin, workspace) }
            ?: unavailable(FailureKind.Timeout)
    }

    private suspend fun resolve(
        input: ActionNotificationInput,
        origin: WorkspaceSelectionOrigin,
        workspace: Workspace?,
    ): AppResult<PendingAppLink> {
        val opened = open(input)
        currentCoroutineContext().ensureActive()
        if (opened is AppResult.Failed) return opened
        val target = (opened as AppResult.Success).value
        if (target.action !is CollaborationNotificationAction.Reply)
            return unavailable(FailureKind.Validation)
        val access = target.scope.access
        val owner = origin.owner
        if (
            access.sessionId != owner.sessionId ||
                access.audience != owner.audience ||
                access.accountId != owner.accountId ||
                target.scope.nativeSessionId != owner.familyId
        )
            return unavailable(FailureKind.Unauthorized)
        val selected =
            if (workspace?.company?.id == access.companyId) workspace
            else {
                val result = select(owner.sessionId, access.companyId, origin)
                currentCoroutineContext().ensureActive()
                if (result is AppResult.Failed) return result
                (result as AppResult.Success).value
            }
        if (
            selected.sessionId != owner.sessionId ||
                selected.audience != owner.audience ||
                selected.admission.accountId != owner.accountId ||
                selected.admission.familyId != owner.familyId ||
                selected.company.id != access.companyId
        )
            return unavailable(FailureKind.Conflict)
        return AppResult.Success(
            PendingAppLink(
                AppLink.ChatThread(owner, access.companyId, target.action.target.conversationId),
                owner.sessionId,
                selected.id,
            )
        )
    }

    private fun unavailable(kind: FailureKind) =
        AppResult.Failed(
            Failure(
                kind,
                "This chat cannot be opened.",
                code = "client.notification_open_unavailable",
            )
        )
}
