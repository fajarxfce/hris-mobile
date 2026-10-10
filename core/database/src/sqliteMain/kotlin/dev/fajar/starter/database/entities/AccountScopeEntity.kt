package dev.fajar.starter.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "account_scope")
data class AccountScopeEntity(@PrimaryKey val id: Int = 1, val sessionId: String?)
