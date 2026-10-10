package dev.fajar.starter.database.entities

import androidx.room.*

@Entity(tableName = "dashboard_outbox", indices = [Index(value = ["operationId"], unique = true)])
data class ActivityChangeEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val operationId: String,
    val activityId: String,
    val saved: Boolean,
)
