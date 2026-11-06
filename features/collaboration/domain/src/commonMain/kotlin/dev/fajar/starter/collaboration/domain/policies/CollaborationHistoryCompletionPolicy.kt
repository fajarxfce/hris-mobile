package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryProgress
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryReference

/** State events have no canonical message original; retain them without retrying decryption. */
fun collaborationHistoryNeedsOriginal(reference: CollaborationHistoryReference): Boolean =
    reference.event.type in setOf("m.room.encrypted", "m.room.message", "m.sticker")

/** Resolution may advance revision; an extended or replaced traversal needs a fresh scan. */
fun sameCollaborationHistoryExtent(
    before: CollaborationHistoryProgress?,
    after: CollaborationHistoryProgress?,
): Boolean =
    before?.generation == after?.generation &&
        before?.references == after?.references &&
        before?.next == after?.next
