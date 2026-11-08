package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Missing original keys never authorize a new account; first initialization has exact lineage. */
fun collaborationActivationStore(
    owner: CollaborationOwner,
    device: CollaborationDeviceSetup,
    record: CollaborationSetupCommandRecord?,
    inventory: CollaborationSdkInventory,
): CollaborationActivationStore {
    if (inventory.binding != owner.setupBinding() || inventory.deviceId != device.id)
        return CollaborationActivationStore.RECOVERY_REQUIRED
    if (
        !inventory.directoryPresent &&
            (inventory.cryptoDatabasePresent || inventory.stateDatabasePresent)
    )
        return CollaborationActivationStore.RECOVERY_REQUIRED
    val credential = inventory.credential
    if (credential?.handover != null) return CollaborationActivationStore.TRANSFER_PENDING
    val original = record?.command
    val bootstrap = original?.action as? CollaborationSetupAction.Bootstrap
    val firstCommand =
        original != null &&
            original.binding == owner.setupBinding() &&
            bootstrap != null &&
            bootstrap.deviceId == device.id &&
            bootstrap.gatewayVersion == null &&
            (device.gateway == null || device.gateway.version == 0L)
    if (credential == null) {
        val empty =
            !inventory.directoryPresent &&
                !inventory.stateDatabasePresent &&
                !inventory.cryptoDatabasePresent
        return if (empty && (device.gateway == null || firstCommand))
            CollaborationActivationStore.FIRST_INITIALIZATION
        else CollaborationActivationStore.RECOVERY_REQUIRED
    }
    if (credential.initialized || credential.messageOperationId != credential.bootstrapOperationId)
        return CollaborationActivationStore.ORIGINAL_ACCOUNT
    val confirmed = record?.outcome as? CollaborationSetupOutcome.Bootstrapped
    if (
        firstCommand &&
            original != null &&
            confirmed != null &&
            collaborationGatewayMatchesCommand(owner, original, confirmed.gateway) &&
            credential.bootstrapOperationId == original.id &&
            credential.messageOperationId == original.id &&
            credential.matrixUserId == confirmed.gateway.matrixUserId &&
            credential.matrixDeviceId == confirmed.gateway.matrixDeviceId
    )
        return CollaborationActivationStore.FIRST_INITIALIZATION
    return CollaborationActivationStore.ORIGINAL_ACCOUNT
}

/** Readback must still describe the exact inspected private account, with a valid fingerprint. */
fun collaborationActivationPossessionMatches(
    inventory: CollaborationSdkInventory,
    possession: CollaborationSdkPossession?,
): Boolean {
    val credential = inventory.credential ?: return false
    return possession != null &&
        possession.inventory == inventory &&
        inventory.directoryPresent &&
        inventory.cryptoDatabasePresent &&
        possession.identity.matrixUserId == credential.matrixUserId &&
        possession.identity.matrixDeviceId == credential.matrixDeviceId &&
        possession.identity.fingerprint.length == 64 &&
        possession.identity.fingerprint.all { it in '0'..'9' || it in 'a'..'f' }
}
