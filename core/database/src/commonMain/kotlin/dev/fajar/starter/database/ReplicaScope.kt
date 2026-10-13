package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/**
 * A request belongs to the current session. Account-owned facts survive replacing that session; a
 * null account key explicitly selects temporary, session-owned storage.
 */
@Serializable
data class ReplicaScope(val sessionId: String, val accountKey: String? = null) {
    override fun toString() = "ReplicaScope([redacted])"
}
