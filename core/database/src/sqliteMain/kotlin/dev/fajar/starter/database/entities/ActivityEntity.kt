package dev.fajar.starter.database.entities

import androidx.room.*

@Entity(tableName = "dashboard_activity")
data class ActivityEntity(
    @PrimaryKey val id: String,
    val title: String,
    val detail: String,
    val time: String,
    val position: Int,
)
