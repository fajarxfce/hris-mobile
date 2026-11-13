package dev.fajar.starter.collaboration.data.identity

import kotlinx.serialization.Serializable

/** Authenticated continuation over existing metadata indexes; never a presentation sort key. */
@Serializable
internal data class CollaborationSpaceConversationCatalogPosition(
    val generation: String,
    val revision: Long,
    val after: String,
)
