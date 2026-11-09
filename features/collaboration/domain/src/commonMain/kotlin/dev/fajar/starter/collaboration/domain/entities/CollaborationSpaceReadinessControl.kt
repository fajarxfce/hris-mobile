package dev.fajar.starter.collaboration.domain.entities

/** Committed organization publication hint; it grants no room, key, message or display access. */
data class CollaborationSpaceReadinessControl(
    val eventId: String,
    val spaceId: String,
    val accountId: String,
    val nativeSessionId: String,
    val deviceId: String,
    val publicationRevision: Long,
    val publishedAtEpochMillis: Long,
) {
    override fun toString() = "CollaborationSpaceReadinessControl([redacted])"
}

/** Local receipt ownership survives short token expiry and same-family reauthentication. */
data class CollaborationSpaceReadinessScope(
    val parent: CollaborationNativeParent,
    val spaceId: String,
    val deviceId: String,
) {
    override fun toString() = "CollaborationSpaceReadinessScope([redacted])"
}

/** The installed logical runtime supplies the source; packet fields cannot choose it. */
data class CollaborationSpaceReadinessSignal(
    val source: CollaborationSpaceRuntimeReady,
    val control: CollaborationSpaceReadinessControl,
) {
    override fun toString() = "CollaborationSpaceReadinessSignal([redacted])"
}
