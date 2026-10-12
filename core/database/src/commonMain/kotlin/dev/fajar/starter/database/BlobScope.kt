package dev.fajar.starter.database

/** Stable account/API ownership, admitted through the current session binding. */
data class BlobScope(val sessionId: String, val accountKey: String) {
    override fun toString() = "BlobScope([redacted])"
}
