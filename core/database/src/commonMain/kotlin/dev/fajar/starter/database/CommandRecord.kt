package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Opaque authenticated ciphertext. Only the state envelope and revision can change. */
@Serializable
data class CommandRecord(
    val partition: String,
    val id: String,
    val resourceId: String,
    val sortKey: String,
    val request: String,
    val state: String,
    val revision: Long = 0,
) {
    override fun toString() = "CommandRecord([redacted])"
}
