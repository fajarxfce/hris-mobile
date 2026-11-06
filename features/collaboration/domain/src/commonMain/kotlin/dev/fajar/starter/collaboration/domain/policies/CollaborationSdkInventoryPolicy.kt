package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Structural provenance only. Missing files/records remain facts, never enrollment permission. */
fun validCollaborationSdkInventory(
    original: CollaborationSpaceLegacySdk,
    inventory: CollaborationSdkInventory,
): Boolean {
    if (
        inventory.binding != original.binding ||
            inventory.deviceId != original.deviceId ||
            (!inventory.directoryPresent &&
                (inventory.stateDatabasePresent || inventory.cryptoDatabasePresent))
    )
        return false
    val credential = inventory.credential ?: return true
    if (
        credential.matrixUserId != original.identity.matrixUserId ||
            credential.matrixDeviceId != original.identity.matrixDeviceId ||
            !validCollaborationMessageId(credential.bootstrapOperationId) ||
            !validCollaborationMessageId(credential.messageOperationId) ||
            credential.revision !in 0..9_007_199_254_740_991L
    )
        return false
    val handover = credential.handover ?: return true
    return credential.initialized &&
        credential.revision > 0 &&
        validCollaborationMessageId(handover.operationId) &&
        validCollaborationMessageId(handover.spaceId) &&
        validCollaborationMessageId(handover.deviceId) &&
        handover.expectedDeviceVersion in 0..999_998 &&
        handover.fingerprint == original.identity.fingerprint
}
