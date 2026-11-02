package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Application-global scheduling metadata only; never credentials, identity or message content. */
@Serializable
data class WorkWakeRecord(val key: String, val generation: String, val pending: Boolean)

/** Raw atomic storage. The caller owns registered task keys, generation creation and scheduling. */
interface WorkWakeStore {
    suspend fun get(key: String): WorkWakeRecord?

    /**
     * Retains a fresh, non-reused caller generation as pending. Repeating the current generation
     * returns its existing state, including an acknowledgment. Null means the key capacity is full.
     */
    suspend fun request(key: String, generation: String, maxKeys: Int): WorkWakeRecord?

    /** Acknowledges only the still-pending exact generation; a competing wake cannot be erased. */
    suspend fun acknowledge(key: String, generation: String): Boolean
}

internal fun validateWorkWakeKey(key: String) {
    require(
        key.length in 1..128 &&
            key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
    )
}

internal fun validateWorkWakeGeneration(generation: String) {
    require(
        generation.length in 1..128 &&
            generation.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-" }
    )
}
