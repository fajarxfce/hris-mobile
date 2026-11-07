package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Complete active inventory only; archives never consume or appear in this local work set. */
fun validCollaborationSpaceConversationCommandInventory(
    access: CollaborationSpaceLocalAccess,
    records: List<CollaborationSpaceConversationCommandRecord>,
): Boolean =
    records.size <= 128 &&
        records.all { validCollaborationSpaceConversationCommandRecord(access, it) } &&
        records.map { it.command.id }.distinct().size == records.size &&
        records
            .map { record ->
                when (val command = record.command) {
                    is CollaborationSpaceConversationCommand.Open -> "open/" + command.peerAccountId
                    is CollaborationSpaceConversationCommand.Room ->
                        "room/" + command.intent.conversationId
                }
            }
            .distinct()
            .size == records.size
