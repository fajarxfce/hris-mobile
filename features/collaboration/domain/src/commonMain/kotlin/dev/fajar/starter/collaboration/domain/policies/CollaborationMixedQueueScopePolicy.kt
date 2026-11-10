package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Complete paused provenance. Null means this SPACE room has no company command ledger. */
fun collaborationMixedQueueCompanyAccess(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceConversation,
    binding: CollaborationSpaceRoomBinding,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
): AppResult<CollaborationLocalAccess?> {
    if (!validCollaborationSpaceConversation(conversation, runtime.owner)) return scopeMismatch()
    if (binding.room.origin == CollaborationSpaceRoomOrigin.LEGACY) {
        return collaborationSpaceRecoveryQueueAccess(runtime, conversation, binding, snapshot)
    }
    val physical = snapshot.original
    val installed = physical.binding
    val device = physical.device
    if (
        !validCollaborationSpaceConversation(conversation, runtime.owner) ||
            !validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) ||
            binding.conversation != conversation.localMetadata() ||
            snapshot.runtime != runtime ||
            !queueIdentity(runtime.deviceId) ||
            !queueIdentity(runtime.operationId) ||
            installed.audience != runtime.owner.parent.audience ||
            installed.accountId != runtime.owner.parent.accountId ||
            !queueIdentity(installed.companyId) ||
            !queueIdentity(installed.nativeSessionId) ||
            !validCollaborationMessageDevice(device) ||
            !queueIdentity(device.deviceId) ||
            !queueIdentity(device.bootstrapOperationId) ||
            device.nativeSessionId != installed.nativeSessionId ||
            device.roomId != binding.room.roomId
    )
        return scopeMismatch()
    val sender =
        "@hris_${installed.companyId.replace("-", "")}_${installed.accountId.replace("-", "")}:${binding.room.serverName}"
    val originals = physical.messages
    if (
        originals.size > 128 ||
            originals.any {
                it.serializedContent.length !in 2..96_000 ||
                    !validCollaborationQueuedOriginal(it) ||
                    it.logicalId == null ||
                    !queueIdentity(it.logicalId) ||
                    it.origin.sender != sender
            } ||
            originals.map { it.logicalId }.distinct().size != originals.size ||
            originals.map { it.transactionId }.distinct().size != originals.size
    )
        return scopeMismatch()
    return AppResult.Success(null)
}

/** Current transport must name the captured physical device; this does not grant delivery. */
fun matchCollaborationSpaceQueueTransport(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceConversation,
    binding: CollaborationSpaceRoomBinding,
    transport: CollaborationSpaceDirectTransport,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
    platform: CollaborationNativePlatform,
): AppResult<Unit> {
    val provenance = collaborationMixedQueueCompanyAccess(runtime, conversation, binding, snapshot)
    if (provenance is AppResult.Failed) return provenance
    val installed = snapshot.original.binding
    val sender =
        "@hris_${installed.companyId.replace("-", "")}_${installed.accountId.replace("-", "")}:${binding.room.serverName}"
    if (
        !validCollaborationSpaceDirectTransport(transport) ||
            transport.conversation != conversation ||
            transport.room != binding.room ||
            transport.device.id != runtime.deviceId ||
            transport.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
            transport.device.platform != platform ||
            transport.matrixUserId != sender ||
            transport.matrixDeviceId != "HRIS_${snapshot.original.device.deviceId.replace("-", "")}"
    )
        return scopeMismatch()
    return AppResult.Success(Unit)
}

private fun queueIdentity(value: String) =
    validCollaborationMessageId(value) && value != "00000000-0000-0000-0000-000000000000"

private fun scopeMismatch() = collaborationSetupFailure("client.collaboration_mixed_queue_scope")
