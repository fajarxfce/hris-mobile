package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.CollaborationInboxPage

/** A pending local detail read does not erase already acquired previews of this same owner/page. */
internal fun ChatInboxState.withPage(incoming: CollaborationInboxPage): ChatInboxState {
    val currentIds = incoming.list.items.map { it.conversation.id }.toSet()
    val nextPreviews =
        when {
            incoming.previewFailure != null -> emptyMap()
            incoming.previewsLoaded -> incoming.previews.filterKeys { it in currentIds }
            owner == incoming.access -> previews.filterKeys { it in currentIds }
            else -> emptyMap()
        }
    return copy(
        page = incoming.list,
        owner = incoming.access,
        previews = nextPreviews,
        previewFailure = incoming.previewFailure,
        loading = false,
        failure = null,
    )
}
