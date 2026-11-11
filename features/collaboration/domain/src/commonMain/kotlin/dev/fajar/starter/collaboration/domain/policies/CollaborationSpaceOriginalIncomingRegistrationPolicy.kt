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
    if (!validCollaborationSpaceIncomingRoomDemand(captured) || captured.sourceId != source.storeId)
        return false
    val legacy =
        registeredCollaborationSpaceOriginalIncomingSdk(runtime, source, registration, platform)
            ?: return false
    return legacy.binding.companyId == target.position.companyId
}

/** Resolves registered physical provenance before a Company-qualified room lookup is possible. */
fun registeredCollaborationSpaceOriginalIncomingSdk(
    runtime: CollaborationSpaceRuntimeReady,
    source: CollaborationSpaceIncomingSource,
    registration: CollaborationSpaceRegistrationRecord,
    platform: CollaborationNativePlatform,
): CollaborationSpaceLegacySdk? {
    val access = runtime.owner.localDirectoryAccess()
    val command = registration.command
    val legacy = command.legacy ?: return null
    val receipt =
        (registration.outcome as? CollaborationSpaceRegistrationOutcome.Registered)?.receipt
            ?: return null
    val parent = runtime.owner.parent
    val valid =
        validCollaborationOriginalConversationOwner(runtime.owner) &&
            validCollaborationSpaceIncomingSource(source) &&
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
            validCollaborationSpaceConversationId(legacy.deviceId) &&
            command.provider.matrixUserId == source.matrixUserId &&
            legacy.identity.matrixUserId == source.matrixUserId &&
            legacy.identity.matrixDeviceId == source.matrixDeviceId
    return legacy.takeIf { valid }
}
