package dev.fajar.starter.database.entities

import androidx.room.Entity

@Entity(tableName = "device_records", primaryKeys = ["namespace", "id"])
data class DeviceRecordEntity(
    val namespace: String,
    val id: String,
    val generation: String,
    val payload: String,
)
