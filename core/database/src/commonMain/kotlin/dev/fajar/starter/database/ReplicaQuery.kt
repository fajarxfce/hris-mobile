package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** One ordered page, optionally narrowed by an opaque equality tag. */
@Serializable
data class ReplicaQuery(val tag: String? = null, val after: String? = null, val limit: Int = 50)
