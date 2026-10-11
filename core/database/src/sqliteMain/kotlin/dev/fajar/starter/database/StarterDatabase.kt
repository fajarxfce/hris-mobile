package dev.fajar.starter.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import dev.fajar.starter.database.dao.AccountCacheDao
import dev.fajar.starter.database.dao.CommandDao
import dev.fajar.starter.database.dao.DashboardDao
import dev.fajar.starter.database.dao.InboxDao
import dev.fajar.starter.database.dao.ReplicaDao
import dev.fajar.starter.database.dao.TransferDao
import dev.fajar.starter.database.entities.*
import dev.fajar.starter.database.entities.InboxEntity

@Database(
    entities =
        [
            AccountScopeEntity::class,
            InboxEntity::class,
            DashboardEntity::class,
            ActivityEntity::class,
            ActivityPreferenceEntity::class,
            ActivityChangeEntity::class,
            TransferEntity::class,
            TransferChunkEntity::class,
            ReplicaHeadEntity::class,
            ReplicaGenerationEntity::class,
            ReplicaDocumentEntity::class,
            CommandEntity::class,
        ],
    version = 7,
    exportSchema = true,
    autoMigrations =
        [
            AutoMigration(from = 1, to = 2),
            AutoMigration(from = 2, to = 3),
            AutoMigration(from = 3, to = 4),
            AutoMigration(from = 4, to = 5),
            AutoMigration(from = 5, to = 6),
            AutoMigration(from = 6, to = 7),
        ],
)
@ConstructedBy(StarterDatabaseConstructor::class)
abstract class StarterDatabase : RoomDatabase() {
    abstract fun accountCacheDao(): AccountCacheDao

    abstract fun inboxDao(): InboxDao

    abstract fun dashboardDao(): DashboardDao

    abstract fun transferDao(): TransferDao

    abstract fun replicaDao(): ReplicaDao

    abstract fun commandDao(): CommandDao
}

@Suppress("KotlinNoActualForExpect")
expect object StarterDatabaseConstructor : RoomDatabaseConstructor<StarterDatabase> {
    override fun initialize(): StarterDatabase
}
