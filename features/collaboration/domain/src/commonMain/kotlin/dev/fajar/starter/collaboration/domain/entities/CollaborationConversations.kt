package dev.fajar.starter.collaboration.domain.entities

/** A bounded discovery result, not an offline history or messaging capability. */
data class CollaborationPage<T>(val items: List<T>, val nextCursor: String?)

data class CollaborationRead<T>(val owner: CollaborationOwner, val value: T)

data class CollaborationParticipant(val accountId: String, val displayName: String)

/** Retained company DM identity; the other participant need not still be eligible. */
data class CollaborationDirectConversation(
    val id: String,
    val companyId: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: Long,
    val version: Long,
)

enum class CollaborationDirectRoomState {
    NOT_REQUESTED,
    QUEUED,
    RUNNING,
    READY,
    FAILED,
    CANCELLED,
}

/** READY confirms provisioning only. Content access and recipient keys remain separate. */
data class CollaborationDirectRoom(
    val conversationId: String,
    val version: Long?,
    val state: CollaborationDirectRoomState,
    val matrixRoomId: String?,
    val creatorAccountId: String?,
    val retryable: Boolean,
    val retryAt: Long?,
)

data class CollaborationOpenDirect(val operationId: String, val peerAccountId: String)

data class CollaborationRequestDirectRoom(
    val operationId: String,
    val conversationId: String,
    val deviceId: String,
    val deviceVersion: Long,
    val provisioningVersion: Long?,
)
