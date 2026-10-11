package dev.fajar.starter.database

/** The application binds a stable account/API key to the currently admitted session. */
data class CommandScope(val sessionId: String, val accountKey: String) {
    override fun toString() = "CommandScope([redacted])"
}
