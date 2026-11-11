package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun validCollaborationSpaceRoomRequest(
    conversation: CollaborationSpaceConversation,
    request: CollaborationSpaceRoomRequest,
): Boolean =
    validCollaborationSpaceConversation(conversation) &&
        validCollaborationSpaceConversationId(request.deviceId) &&
        request.expectedDeviceVersion in 0..999999 &&
        (request.expectedAttempt == null ||
            (request.expectedAttempt.accountId in
                listOf(conversation.firstAccountId, conversation.secondAccountId) &&
                request.expectedAttempt.revision in 0..999999))

fun validCollaborationSpaceRoomView(value: CollaborationSpaceRoomView): Boolean {
    val conversation = value.conversation
    if (!validCollaborationSpaceConversation(conversation)) return false
    val status = value.provisioning
    if (status != null && !validCollaborationSpaceRoomStatus(status, conversation)) return false
    if ((value.room != null) != (status?.state == CollaborationProvisioningState.SUCCEEDED))
        return false
    val room = value.room ?: return true
    return validCollaborationSpacePublishedRoom(room, conversation.createdAt)
}

fun validCollaborationSpaceRoomIdentity(serverName: String, roomId: String): Boolean =
    validCollaborationSpaceServerName(serverName) &&
        roomId.length in 2..255 &&
        roomId.startsWith('!') &&
        roomId.none { it.isWhitespace() || it.code in 0..31 || it.code in 127..159 }

fun validCollaborationSpacePublishedRoom(
    room: CollaborationSpacePublishedRoom,
    conversationCreatedAt: Instant,
): Boolean = validCollaborationSpacePublishedRoom(room) && room.publishedAt >= conversationCreatedAt

/** Publication shape before real conversation metadata is acquired. */
fun validCollaborationSpacePublishedRoom(room: CollaborationSpacePublishedRoom): Boolean {
    if (
        !validCollaborationSpaceRoomIdentity(room.serverName, room.roomId) ||
            !spaceRoomTime(room.publishedAt)
    )
        return false
    val original = room.original
    return when (room.origin) {
        CollaborationSpaceRoomOrigin.SPACE -> original == null
        CollaborationSpaceRoomOrigin.LEGACY ->
            original != null &&
                validCollaborationOriginalConversationPosition(original.position) &&
                original.roomId == room.roomId &&
                spaceRoomTime(original.boundAt) &&
                original.boundAt <= room.publishedAt
    }
}

fun validCollaborationSpaceRoomStatus(
    status: CollaborationSpaceRoomStatus,
    conversation: CollaborationSpaceConversation,
): Boolean =
    status.attempt.accountId in listOf(conversation.firstAccountId, conversation.secondAccountId) &&
        validCollaborationSpaceRoomStatus(status)

/** Status shape; the owning binding operation separately admits its actual participants. */
fun validCollaborationSpaceRoomStatus(status: CollaborationSpaceRoomStatus): Boolean {
    if (
        !validCollaborationMessageId(status.attempt.accountId) ||
            status.attempt.revision !in 0..999999 ||
            status.attempts !in 0..8 ||
            !listOfNotNull(
                    status.requestedAt,
                    status.availableAt,
                    status.expiresAt,
                    status.completedAt,
                    status.lastOutcome?.at,
                )
                .all(::spaceRoomTime) ||
            status.expiresAt <= status.requestedAt ||
            status.expiresAt > status.requestedAt + 300.seconds ||
            status.availableAt !in status.requestedAt..status.expiresAt ||
            (status.state == CollaborationProvisioningState.QUEUED && status.attempts >= 8) ||
            (status.state in
                listOf(
                    CollaborationProvisioningState.RUNNING,
                    CollaborationProvisioningState.SUCCEEDED,
                ) && status.attempts == 0)
    )
        return false
    val last = status.lastOutcome
    if (last == null && status.attempt.revision != 0L) return false
    if (
        last != null &&
            (last.revision !in 0..status.attempt.revision ||
                !spaceRoomTerminal(last.state) ||
                ((last.state == CollaborationProvisioningState.FAILED) != (last.failure != null)))
    )
        return false
    return if (spaceRoomTerminal(status.state)) {
        last != null &&
            last.revision == status.attempt.revision &&
            last.state == status.state &&
            last.at == status.completedAt &&
            last.at >= status.requestedAt &&
            last.failure == status.failure
    } else {
        status.completedAt == null &&
            status.failure == null &&
            (last == null ||
                (last.revision < status.attempt.revision && last.at <= status.requestedAt))
    }
}

private fun spaceRoomTerminal(state: CollaborationProvisioningState): Boolean =
    state in
        listOf(
            CollaborationProvisioningState.SUCCEEDED,
            CollaborationProvisioningState.FAILED,
            CollaborationProvisioningState.CANCELLED,
        )

private fun spaceRoomTime(value: Instant): Boolean =
    value.epochSeconds in -62_135_596_800L..253_402_300_799L &&
        value.nanosecondsOfSecond % 1000 == 0
