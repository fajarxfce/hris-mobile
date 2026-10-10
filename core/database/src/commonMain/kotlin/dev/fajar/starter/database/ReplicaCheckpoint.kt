package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Payload is an authenticated ciphertext, never a plaintext server cursor. */
@Serializable
data class ReplicaCheckpoint(
    val handle: ReplicaHandle,
    val revision: Long,
    val payload: String,
    val published: Boolean,
)
