package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/**
 * One indexed page, optionally narrowed by an opaque equality tag. Forward pages are ascending and
 * exclude [after]. Reverse pages are descending and exclude [before]. An exact [id] selects at most
 * one live document independently of its sort key, and cannot be combined with a tag or anchor. All
 * selections share the published checkpoint when passed to ReplicaStore.observeQueries.
 */
@Serializable
data class ReplicaQuery(
    val tag: String? = null,
    val after: String? = null,
    val limit: Int = 50,
    val before: String? = null,
    val id: String? = null,
)
