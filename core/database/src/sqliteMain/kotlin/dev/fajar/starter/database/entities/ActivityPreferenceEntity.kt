package dev.fajar.starter.database.entities

import androidx.room.*

@Entity(tableName = "activity_preferences")
data class ActivityPreferenceEntity(@PrimaryKey val activityId: String, val saved: Boolean)
