package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.presentation.thread.invalidThreadProjection
import dev.fajar.starter.common.result.*

/**
 * Bounded display projection. It never acknowledges, deletes, retries or copies durable messages.
 */
internal fun spaceChatThreadRows(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    thread: CollaborationSpaceThreadSnapshot?,
    composer: SpaceChatComposerPresentation,
    previous: List<SpaceChatMessageRow> = emptyList(),
): AppResult<List<SpaceChatMessageRow>> {
    val owner = access.messageOwner()
    if (
        previous.size > 1024 ||
            previous.map { it.identity }.distinct().size != previous.size ||
            previous.any {
                it.identity.owner != owner ||
                    it.identity.conversationId != conversationId ||
                    it.position !in 0 until Long.MAX_VALUE - 1024
            }
    )
        return invalidThreadProjection()
    if (
        thread != null &&
            (thread.conversation.id != conversationId ||
                thread.conversation.spaceId != access.spaceId ||
                access.accountId !in
                    listOf(thread.conversation.firstAccountId, thread.conversation.secondAccountId))
    )
        return invalidThreadProjection()
    if (
        thread is CollaborationSpaceThreadSnapshot.Unresolved &&
            thread.access.messageOwner() != owner
    )
        return invalidThreadProjection()
    val bound = thread as? CollaborationSpaceThreadSnapshot.Bound
    val history = bound?.history
    if (
        history != null &&
            (history.access.messageOwner() != owner ||
                history.conversationId != conversationId ||
                history.page.messages.size > 500 ||
                history.page.messages.any { !validCollaborationHistoryMessage(it) } ||
                (history.page.roomId != history.location.roomId &&
                    history.page.messages.isNotEmpty()))
    )
        return invalidThreadProjection()
    val outgoing = spaceChatOutgoingMessages(access, conversationId, thread, composer)
    if (outgoing is AppResult.Failed) return outgoing
    val commands = (outgoing as AppResult.Success).value
    val rows = mutableListOf<SpaceChatMessageRow>()
    val consumed = mutableSetOf<SpaceChatMessageIdentity>()
    if (history != null && bound != null)
        for (message in history.page.messages) {
            val matches = commands.filter { it.matchesHistory(history.location.roomId, message) }
            if (matches.size > 1) return invalidThreadProjection()
            val matching = matches.singleOrNull()
            matching?.let { consumed += it.identity }
            // A hidden original must not reappear from its independently observed command ledger.
            if (!message.locallyHidden)
                rows += spaceChatHistoryRow(history, bound.participants, message, matching)
        }
    for (command in commands) {
        if (
            command.identity !in consumed &&
                command.displayWhenUnmatched &&
                command.state != CollaborationMessageState.Cancelled
        )
            rows += command.pendingRow()
    }
    if (
        rows.size > 1024 ||
            rows.map { it.identity }.distinct().size != rows.size ||
            rows.map { it.bubble.key }.distinct().size != rows.size
    )
        return invalidThreadProjection()
    return AppResult.Success(positionSpaceChatRows(rows, commands, previous))
}

/**
 * New pending gestures follow the visible end even with clock skew. Exact confirmation keeps that
 * placement; changing a delivery icon cannot reorder an earlier gesture. Timestamps stay real.
 */
private fun positionSpaceChatRows(
    rows: List<SpaceChatMessageRow>,
    commands: List<SpaceChatOutgoingMessage>,
    previous: List<SpaceChatMessageRow>,
): List<SpaceChatMessageRow> {
    val old = previous.associateBy { it.identity }
    val pending =
        commands.filter { it.state !is CollaborationMessageState.Sent }.map { it.identity }.toSet()
    val fresh =
        rows
            .filter { it.identity in pending && it.identity !in old }
            .sortedWith(
                compareBy<SpaceChatMessageRow> { it.bubble.timestamp }.thenBy { it.bubble.key }
            )
    val positions =
        rows.associate { it.identity to (old[it.identity]?.position ?: it.position) }.toMutableMap()
    var end = maxOf(positions.values.maxOrNull() ?: 0, previous.maxOfOrNull { it.position } ?: 0)
    for (row in fresh) {
        end = maxOf(end + 1, row.bubble.timestamp)
        positions[row.identity] = end
    }
    return rows
        .map { it.copy(position = positions.getValue(it.identity)) }
        .sortedWith(
            compareByDescending<SpaceChatMessageRow> { it.position }.thenBy { it.bubble.key }
        )
}
