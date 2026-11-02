package dev.fajar.starter.database.entities

import androidx.room.Entity

@Entity(tableName = "work_wakes", primaryKeys = ["key"])
data class WorkWakeEntity(val key: String, val generation: String, val pending: Boolean)
