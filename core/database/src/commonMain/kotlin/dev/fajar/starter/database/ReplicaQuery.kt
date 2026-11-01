package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/**
 * One indexed page, optionally narrowed by an opaque equality tag. Forward pages are ascending and
 * exclude [after]. Reverse pages are descending and exclude [before]. The two anchors are mutually
 * exclusive; reversing never loads the whole partition or changes the read limit.
 */
@Serializable
data class ReplicaQuery(
    val tag: String? = null,
    val after: String? = null,
    val limit: Int = 50,
    val before: String? = null,
)
