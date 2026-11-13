package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.data.inbox.InboxSourceDto
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceEditInboxOrigin
import kotlinx.serialization.Serializable

@Serializable
internal data class SpaceEditInboxOriginDto(
    val activity: InboxSourceDto,
    val publishedAtRevision: Long,
) {
    constructor(
        value: CollaborationSpaceEditInboxOrigin
    ) : this(InboxSourceDto(value.activity), value.publishedAtRevision)

    fun domain() = CollaborationSpaceEditInboxOrigin(activity.domain(), publishedAtRevision)

    override fun toString() = "SpaceEditInboxOriginDto([redacted])"
}
