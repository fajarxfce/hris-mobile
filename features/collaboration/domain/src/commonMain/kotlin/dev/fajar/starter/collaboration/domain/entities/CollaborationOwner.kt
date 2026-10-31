package dev.fajar.starter.collaboration.domain.entities

/** Local request lifetime is distinct from the remote native parent and retained account facts. */
data class CollaborationOwner(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val companyId: String,
    val nativeSessionId: String,
) {
    override fun toString() = "CollaborationOwner([redacted])"
}

enum class CollaborationNativePlatform {
    ANDROID,
    IOS,
}
