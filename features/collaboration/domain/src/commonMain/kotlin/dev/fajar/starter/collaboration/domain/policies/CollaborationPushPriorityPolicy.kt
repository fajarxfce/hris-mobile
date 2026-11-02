package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*

const val COLLABORATION_PUSH_PRIORITY_CAPACITY = 16

fun validCollaborationPushHint(hint: CollaborationPushHint): Boolean =
    listOf(hint.eventId, hint.companyId, hint.deviceId).all(::validCollaborationMessageId) &&
        hint.revision > 0 &&
        hint.issuedAt in 0..253_402_300_799L &&
        hint.expiresAt in 0..253_402_300_799L &&
        hint.expiresAt - hint.issuedAt in 1..86_400L &&
        ((hint.roomId == null && hint.matrixEventId == null) ||
            (hint.roomId != null &&
                hint.matrixEventId != null &&
                hint.roomId.startsWith('!') &&
                hint.roomId.length in 2..255 &&
                hint.roomId.all { it.code in 33..126 } &&
                validCollaborationEventId(hint.matrixEventId) &&
                hint.matrixEventId.all { it.code in 33..126 }))

fun validCollaborationPushHints(hints: List<CollaborationPushHint>): Boolean =
    hints.size <= COLLABORATION_PUSH_PRIORITY_CAPACITY &&
        hints.all(::validCollaborationPushHint) &&
        hints.map { it.companyId to it.deviceId }.distinct().size == hints.size

fun validCollaborationPushPriority(value: CollaborationPushPriority): Boolean =
    validCollaborationMessageId(value.generation) && validCollaborationPushHints(value.hints)

fun nextCollaborationPushHints(
    current: List<CollaborationPushHint>,
    hint: CollaborationPushHint,
    nowEpochSeconds: Long,
): List<CollaborationPushHint> {
    require(validCollaborationPushHints(current) && validCollaborationPushHint(hint))
    val retained = current.filter { nowEpochSeconds < it.expiresAt }
    if (nowEpochSeconds < hint.issuedAt || nowEpochSeconds >= hint.expiresAt) return retained
    val index =
        retained.indexOfFirst { it.companyId == hint.companyId && it.deviceId == hint.deviceId }
    if (index < 0) return (retained + hint).takeLast(COLLABORATION_PUSH_PRIORITY_CAPACITY)
    val prior = retained[index]
    if (hint.revision < prior.revision) return retained
    if (
        hint.revision == prior.revision &&
            (hint.eventId != prior.eventId ||
                hint.roomId != prior.roomId ||
                hint.matrixEventId != prior.matrixEventId ||
                hint.issuedAt <= prior.issuedAt ||
                hint.expiresAt < prior.expiresAt)
    )
        return retained
    return retained.toMutableList().also { it[index] = hint }.toList()
}

/** This only identifies retained local state; worker admission separately checks freshness. */
fun collaborationPushParent(
    session: Session?,
    server: IdentityServer,
    build: AppBuild,
): AppResult<CollaborationNativeParent> {
    if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
        return collaborationRecoveryFailure(
            "client.collaboration_native_required",
            FailureKind.Unavailable,
        )
    if (
        session == null ||
            session.binding.audience != server.audience ||
            session.id.length !in 1..128 ||
            session.id.any { it.code !in 33..126 } ||
            !validCollaborationMessageId(session.user.id) ||
            !validCollaborationMessageId(session.binding.serverId)
    )
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    return AppResult.Success(
        CollaborationNativeParent(
            session.id,
            server.audience,
            session.user.id,
            session.binding.serverId,
        )
    )
}
