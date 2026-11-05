package dev.fajar.starter.collaboration.domain.entities

/** Owned message identity, without its body. A logical ID may have multiple provider aliases. */
data class CollaborationMessageReference(
    val sender: String,
    val eventId: String,
    val logicalId: String? = null,
) {
    override fun toString() = "CollaborationMessageReference([redacted])"
}

const val COLLABORATION_LOCAL_DELETE_LIMIT = 50
const val COLLABORATION_VISIBILITY_READ_LIMIT = 256
