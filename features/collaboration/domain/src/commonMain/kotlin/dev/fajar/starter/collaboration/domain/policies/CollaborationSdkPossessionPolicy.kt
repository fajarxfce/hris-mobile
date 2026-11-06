package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationSdkPossession
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLegacySdk

/** Local provenance and exact original keys; no server receipt or runtime handover is inferred. */
fun validCollaborationSdkPossession(
    original: CollaborationSpaceLegacySdk,
    possession: CollaborationSdkPossession,
): Boolean =
    validCollaborationSdkInventory(original, possession.inventory) &&
        possession.inventory.directoryPresent &&
        possession.inventory.cryptoDatabasePresent &&
        possession.inventory.credential != null &&
        possession.identity == original.identity
