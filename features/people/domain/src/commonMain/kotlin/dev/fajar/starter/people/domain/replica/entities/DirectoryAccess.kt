package dev.fajar.starter.people.domain.replica.entities

/** Stored ownership is independent of a request's credentials and their expiry. */
data class DirectoryAccess(
    val sessionId: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val timezone: String,
    val permissions: Set<String>,
) {
    override fun toString() = "DirectoryAccess([redacted])"
}
