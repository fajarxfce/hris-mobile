package dev.fajar.starter.collaboration.presentation.spacethread.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatQuotePreview
import dev.fajar.starter.common.result.AppResult

@Composable
internal fun SpaceChatQuotePreview(
    snapshot: CollaborationSpaceThreadSnapshot?,
    eventId: String,
    modifier: Modifier = Modifier,
) {
    val thread = snapshot as? CollaborationSpaceThreadSnapshot.Bound
    val original =
        thread?.history?.page?.messages?.firstOrNull {
            eventId in it.eventIds && !it.redacted && !it.locallyHidden
        }
    val quote =
        original?.let {
            (it.latestEdit?.edit?.text ?: it.text)?.let { text ->
                CollaborationQuotedMessage(eventId, it.sender, text)
            }
        }
    val participants = snapshot?.participants
    val currentSelf = (participants?.selectedIdentity as? AppResult.Success)?.value?.matrixUserId
    val originalSelf = (participants?.originalIdentity as? AppResult.Success)?.value?.matrixUserId
    val peer = (participants?.contact as? AppResult.Success)?.value?.displayName
    ChatQuotePreview(
        quote,
        quote?.sender?.takeIf { it == currentSelf || it == originalSelf },
        peer,
        modifier,
    )
}
