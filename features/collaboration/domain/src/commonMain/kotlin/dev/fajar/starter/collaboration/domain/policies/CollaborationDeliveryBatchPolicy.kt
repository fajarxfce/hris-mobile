package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Select current-store work before applying the finite batch limit; retain other stores' work. */
fun collaborationDeliveryBatch(
    records: List<CollaborationMessageRecord>,
    access: CollaborationLocalAccess,
    conversationId: String,
    device: CollaborationMessageDevice,
): AppResult<List<CollaborationMessageRecord>> {
    if (
        !validCollaborationMessageDevice(device) ||
            records.size > 128 ||
            records.map { it.intent.id }.distinct().size != records.size ||
            records.any {
                !validCollaborationMessageId(it.intent.id) ||
                    !validCollaborationMessageState(it.state) ||
                    it.intent.owner != access.messageOwner() ||
                    it.intent.conversationId != conversationId
            }
    )
        return collaborationSetupFailure("client.collaboration_queue_mapping_invalid_record")
    return AppResult.Success(
        records
            .filter {
                val original =
                    when (val state = it.state) {
                        is CollaborationMessageState.Staging -> state.device
                        is CollaborationMessageState.Staged -> state.device
                        is CollaborationMessageState.Sent -> return@filter false
                        else -> return@filter true
                    }
                original.nativeSessionId == device.nativeSessionId &&
                    original.deviceId == device.deviceId
            }
            .sortedWith(
                compareBy<CollaborationMessageRecord> { it.intent.createdAt }
                    .thenBy { it.intent.id }
            )
            .take(8)
    )
}
