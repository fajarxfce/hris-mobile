package dev.fajar.starter.database

import kotlinx.serialization.Serializable

@Serializable
data class ActivityRecord(
    val id: String,
    val title: String,
    val detail: String,
    val time: String,
    val saved: Boolean = false,
)
