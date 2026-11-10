package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** The cursor retains the original local ordering, including failed attempts. */
fun validCollaborationSpaceMessageRecoveryPosition(
    value: CollaborationSpaceMessageRecoveryPosition
): Boolean =
    value.createdAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L &&
        validCollaborationSpaceConversationId(value.messageId)

fun compareCollaborationSpaceMessageRecoveryPositions(
    left: CollaborationSpaceMessageRecoveryPosition,
    right: CollaborationSpaceMessageRecoveryPosition,
): Int = compareValuesBy(left, right, { it.createdAt }, { it.messageId })

fun validCollaborationSpaceMessageRecoverySweep(
    value: CollaborationSpaceMessageRecoverySweep
): Boolean {
    if (value.after?.let(::validCollaborationSpaceMessageRecoveryPosition) == false) return false
    val claim = value.pending ?: return true
    return !value.reachedEnd &&
        validCollaborationSpaceMessageRecoveryPosition(claim.position) &&
        validCollaborationSpaceConversationId(claim.conversationId) &&
        (value.after == null ||
            compareCollaborationSpaceMessageRecoveryPositions(claim.position, value.after) > 0)
}

/** Complete bounded local facts choose one attempt. They provide no delivery authority. */
fun claimCollaborationSpaceRecoveryMessage(
    access: CollaborationSpaceLocalAccess,
    sweep: CollaborationSpaceMessageRecoverySweep,
    records: List<CollaborationSpaceMessageRecord>,
): CollaborationSpaceMessageRecoverySweep? {
    if (
        !validCollaborationSpaceMessageRecoverySweep(sweep) ||
            sweep.pending != null ||
            sweep.reachedEnd
    )
        return null
    val inventory = collaborationSpaceMessageInventory(access, records) ?: return null
    val selected =
        inventory.firstOrNull { record ->
            val position =
                CollaborationSpaceMessageRecoveryPosition(record.intent.createdAt, record.intent.id)
            record.state !is CollaborationMessageState.Sent &&
                record.state != CollaborationMessageState.Cancelled &&
                (sweep.after == null ||
                    compareCollaborationSpaceMessageRecoveryPositions(position, sweep.after) > 0)
        }
    return sweep.copy(
        pending =
            selected?.let {
                CollaborationSpaceMessageRecoveryClaim(
                    CollaborationSpaceMessageRecoveryPosition(it.intent.createdAt, it.intent.id),
                    it.intent.conversationId,
                )
            },
        reachedEnd = selected == null,
    )
}
