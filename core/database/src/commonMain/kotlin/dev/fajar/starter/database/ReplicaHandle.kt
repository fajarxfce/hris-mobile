package dev.fajar.starter.database

import kotlinx.serialization.Serializable

@Serializable
data class ReplicaHandle(val sessionId: String, val partition: String, val generation: String)
