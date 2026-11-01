package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Immutable encrypted completion evidence, outside pending-command capacity. */
@Serializable
data class CommandArchive(val record: CommandRecord, val evidence: String) {
    override fun toString() = "CommandArchive([redacted])"
}
