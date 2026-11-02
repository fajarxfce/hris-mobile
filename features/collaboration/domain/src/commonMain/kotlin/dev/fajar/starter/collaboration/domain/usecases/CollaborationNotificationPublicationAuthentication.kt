package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationActionRepository
import dev.fajar.starter.common.result.*

/** Shared internal issuance/revalidation implementation; this never grants a native OS lease. */
internal suspend fun CollaborationNotificationPublicationAuthority.authenticate(
    body: CollaborationNotificationPublicationBody,
    actions: CollaborationNotificationActionRepository,
): AppResult<Unit> {
    if (!validCollaborationNotificationPublicationBody(body))
        return notificationPublicationFailure(FailureKind.Validation)
    val message = body.message ?: return notificationPublicationFailure(FailureKind.Validation)
    val tokens = body.actions ?: return notificationPublicationFailure(FailureKind.Validation)
    guard(body.parent, message.access).let { if (it is AppResult.Failed) return it }
    val opened = mutableListOf<CollaborationNotificationAction>()
    for (token in listOf(tokens.reply, tokens.markRead, tokens.mute, tokens.dismiss)) {
        val result = checked(body.parent, message) { actions.open(body.parent, token) }
        if (result is AppResult.Failed) return result
        opened += (result as AppResult.Success).value
    }
    return if (validCollaborationPublicationActions(body, opened)) AppResult.Success(Unit)
    else notificationPublicationFailure(FailureKind.Conflict)
}
