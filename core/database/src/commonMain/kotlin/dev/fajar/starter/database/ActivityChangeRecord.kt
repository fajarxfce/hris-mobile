package dev.fajar.starter.database

import kotlinx.serialization.Serializable

@Serializable
data class ActivityChangeRecord(val operationId: String, val activityId: String, val saved: Boolean)
