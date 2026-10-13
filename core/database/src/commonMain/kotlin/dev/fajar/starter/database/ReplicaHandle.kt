package dev.fajar.starter.database

import kotlinx.serialization.Serializable

@Serializable
data class ReplicaHandle(val scope: ReplicaScope, val partition: String, val generation: String)
