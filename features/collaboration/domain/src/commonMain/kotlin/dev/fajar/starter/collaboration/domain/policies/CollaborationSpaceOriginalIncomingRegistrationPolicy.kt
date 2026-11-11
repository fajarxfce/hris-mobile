package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Matches the registered original to the installed source without inferring a selected Company. */
fun validCollaborationSpaceOriginalIncomingRegistration(
    runtime: CollaborationSpaceRuntimeReady,
    captured: CollaborationSpaceIncomingRoomDemand,
    source: CollaborationSpaceIncomingSource,
    registration: CollaborationSpaceRegistrationRecord,
    platform: CollaborationNativePlatform,
): Boolean {
    val target = captured.target as? CollaborationSpaceIncomingTarget.Original ?: return false
    val access = runtime.owner.localDirectoryAccess()
    val command = registration.command
    val legacy = command.legacy ?: return false
    val receipt =
        (registration.outcome as? CollaborationSpaceRegistrationOutcome.Registered)?.receipt
            ?: return false
    val parent = runtime.owner.parent
    return validCollaborationOriginalConversationOwner(runtime.owner) &&
        validCollaborationSpaceIncomingRoomDemand(captured) &&
        validCollaborationSpaceIncomingSource(source) &&
        captured.sourceId == source.storeId &&
        validCollaborationSpaceRegistrationRecord(access, registration) &&
        validCollaborationSpaceConversationId(runtime.deviceId) &&
        validCollaborationSpaceConversationId(runtime.operationId) &&
        receipt.id == runtime.deviceId &&
        command.platform == platform &&
        command.origin == CollaborationSpaceDeviceOrigin.LEGACY &&
        command.binding.nativeSessionId == parent.nativeSessionId &&
        legacy.binding.audience == parent.audience &&
        legacy.binding.accountId == parent.accountId &&
        legacy.binding.nativeSessionId == parent.nativeSessionId &&
        legacy.binding.companyId == target.position.companyId &&
        validCollaborationSpaceConversationId(legacy.deviceId) &&
        command.provider.matrixUserId == source.matrixUserId &&
        legacy.identity.matrixUserId == source.matrixUserId &&
        legacy.identity.matrixDeviceId == source.matrixDeviceId
}
