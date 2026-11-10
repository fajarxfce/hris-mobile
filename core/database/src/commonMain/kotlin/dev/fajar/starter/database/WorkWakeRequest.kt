package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/**
 * Scheduling metadata committed with a new durable mutation. Callers own registered keys and fresh,
 * non-reused generations. Reusing the current generation fails even while it is pending: an old
 * consumer must never acknowledge a newer mutation. Contains no account or document content.
 */
@Serializable data class WorkWakeRequest(val key: String, val generation: String, val maxKeys: Int)

internal fun validateWorkWakeRequest(wake: WorkWakeRequest?) {
    if (wake == null) return
    validateWorkWakeKey(wake.key)
    validateWorkWakeGeneration(wake.generation)
    require(wake.maxKeys in 1..128)
}
