package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Raw queue metadata; binary content is stored separately and never loaded by list observation. */
@Serializable
data class TransferRecord(
    val id: String,
    val direction: String,
    val name: String,
    val mediaType: String,
    val size: Long,
    val createdAtEpochMillis: Long,
    val resourceId: String? = null,
    val resourceVersion: String? = null,
    val offset: Long = 0,
    val storedBytes: Long = 0,
    val status: String = "Staging",
    val version: Long = 0,
    val failureKind: String? = null,
)
