package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Raw exact-ID observation, including a completion that predates the subscription. */
@Serializable
data class CommandLookup(val active: CommandRecord?, val archived: CommandArchive?) {
    override fun toString() = "CommandLookup([redacted])"
}
