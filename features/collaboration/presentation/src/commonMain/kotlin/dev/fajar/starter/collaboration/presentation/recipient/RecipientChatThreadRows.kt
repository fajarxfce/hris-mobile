package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.common.result.AppResult

/** Pure bubble projection; source retirement and command delivery remain in domain owners. */
internal fun recipientChatThreadRows(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    conversation: CollaborationSpaceConversationMetadata?,
    thread: CollaborationSpaceThreadSnapshot?,
    composer: RecipientChatPresentation,
    previous: List<RecipientChatMessageRow> = emptyList(),
): AppResult<List<RecipientChatMessageRow>> {
    val captured =
        captureRecipientChatSources(
            access,
            peerAccountId,
            conversation,
            composer,
            emptyList(),
            emptyList(),
            emptyList(),
            thread,
        )
    if (captured is AppResult.Failed) return captured
    val owner = access.messageOwner()
    if (
        previous.size > 1024 ||
            previous.map { it.identity }.distinct().size != previous.size ||
            previous.any {
                it.identity.owner != owner ||
                    it.identity.peerAccountId != peerAccountId ||
                    it.bubble.key != it.identity.stableKey ||
                    it.position !in 0 until Long.MAX_VALUE - 1024 ||
                    it.canonicalIdentity?.let { canonical ->
                        canonical.owner != owner ||
                            canonical.conversationId != conversation?.id ||
                            canonical.source != it.identity.source
                    } == true
            }
    )
        return invalidThreadProjection()
    val rows =
        if (conversation == null) {
            composer.pending.map { recipientPendingRow(access, peerAccountId, it) }
        } else {
            val outgoing =
                recipientChatOutgoingMessages(
                    access,
                    peerAccountId,
                    conversation,
                    thread,
                    composer,
                    (captured as AppResult.Success).value,
                )
            if (outgoing is AppResult.Failed) return outgoing
            val joined =
                joinSpaceChatThreadRows(
                    access,
                    conversation.id,
                    thread,
                    (outgoing as AppResult.Success).value,
                )
            if (joined is AppResult.Failed) return joined
            (joined as AppResult.Success).value.map { row ->
                val identity =
                    RecipientChatMessageIdentity(owner, peerAccountId, row.identity.source)
                RecipientChatMessageRow(
                    identity,
                    row.bubble.copy(key = identity.stableKey),
                    row.identity,
                )
            }
        }
    if (rows.size > 1024 || rows.map { it.identity }.distinct().size != rows.size)
        return invalidThreadProjection()
    return AppResult.Success(positionRecipientChatRows(rows, previous))
}

/** Unknown canonical coordinates do not prevent rendering the actual captured Send gesture. */
private fun recipientPendingRow(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    item: RecipientChatPendingSubmission,
): RecipientChatMessageRow {
    val original = item.original
    val identity =
        RecipientChatMessageIdentity(
            access.messageOwner(),
            peerAccountId,
            SpaceChatMessageSource.GroupCommand(original.id),
        )
    return RecipientChatMessageRow(
        identity,
        ChatMessageRow(
            key = identity.stableKey,
            eventId = null,
            localId = null,
            timestamp = original.submittedAt.toEpochMilliseconds(),
            ownership = ChatMessageOwnership.SELF,
            kind = CollaborationMessageKind.TEXT,
            text = original.original.text,
            delivery =
                if (item.failure == null) CollaborationMessageDelivery.PENDING
                else CollaborationMessageDelivery.FAILED,
            visibilityKnown = true,
            replyToEventId = null,
            forwarded = false,
        ),
    )
}

/**
 * Route positions survive canonical association, clock skew and out-of-order status publication.
 */
private fun positionRecipientChatRows(
    rows: List<RecipientChatMessageRow>,
    previous: List<RecipientChatMessageRow>,
): List<RecipientChatMessageRow> {
    val old = previous.associateBy { it.identity }
    val positions =
        rows.associate { it.identity to (old[it.identity]?.position ?: it.position) }.toMutableMap()
    val fresh =
        rows
            .filter {
                it.identity !in old &&
                    it.bubble.delivery in
                        setOf(
                            CollaborationMessageDelivery.PENDING,
                            CollaborationMessageDelivery.FAILED,
                        )
            }
            .sortedWith(
                compareBy<RecipientChatMessageRow> { it.bubble.timestamp }.thenBy { it.bubble.key }
            )
    var end = maxOf(positions.values.maxOrNull() ?: 0, previous.maxOfOrNull { it.position } ?: 0)
    for (row in fresh) {
        end = maxOf(end + 1, row.bubble.timestamp)
        positions[row.identity] = end
    }
    return rows
        .map { it.copy(position = positions.getValue(it.identity)) }
        .sortedWith(
            compareByDescending<RecipientChatMessageRow> { it.position }.thenBy { it.bubble.key }
        )
}
