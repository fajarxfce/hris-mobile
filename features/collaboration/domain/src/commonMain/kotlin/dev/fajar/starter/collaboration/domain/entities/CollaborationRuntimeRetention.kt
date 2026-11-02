package dev.fajar.starter.collaboration.domain.entities

/** Application resource retention, never permission to acquire content or send a message. */
data class CollaborationRuntimeRetention(
    val parent: CollaborationNativeParent?,
    val foreground: CollaborationOwner?,
    val deniedCompanyId: String? = null,
) {
    override fun toString() = "CollaborationRuntimeRetention([redacted])"
}
