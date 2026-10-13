package dev.fajar.starter.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Retains existing session projections; account-owned partitions use a separate composite key. */
internal object ReplicaOwnershipMigration : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE replica_heads_owned (ownerKey TEXT NOT NULL, partition TEXT NOT NULL, activeGeneration TEXT, stagingGeneration TEXT, PRIMARY KEY(ownerKey, partition))"
        )
        connection.execSQL(
            "CREATE TABLE replica_generations_owned (ownerKey TEXT NOT NULL, partition TEXT NOT NULL, generation TEXT NOT NULL, revision INTEGER NOT NULL, checkpoint TEXT NOT NULL, PRIMARY KEY(ownerKey, partition, generation))"
        )
        connection.execSQL(
            "CREATE TABLE replica_documents_owned (ownerKey TEXT NOT NULL, partition TEXT NOT NULL, generation TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL, sortKey TEXT NOT NULL, payload TEXT, PRIMARY KEY(ownerKey, partition, generation, id), FOREIGN KEY(ownerKey, partition, generation) REFERENCES replica_generations_owned(ownerKey, partition, generation) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        connection.execSQL(
            "INSERT INTO replica_heads_owned SELECT '', partition, activeGeneration, stagingGeneration FROM replica_heads"
        )
        connection.execSQL(
            "INSERT INTO replica_generations_owned SELECT '', partition, generation, revision, checkpoint FROM replica_generations"
        )
        connection.execSQL(
            "INSERT INTO replica_documents_owned SELECT '', partition, generation, id, version, sortKey, payload FROM replica_documents"
        )
        connection.execSQL("DROP TABLE replica_documents")
        connection.execSQL("DROP TABLE replica_generations")
        connection.execSQL("DROP TABLE replica_heads")
        connection.execSQL("ALTER TABLE replica_heads_owned RENAME TO replica_heads")
        connection.execSQL("ALTER TABLE replica_generations_owned RENAME TO replica_generations")
        connection.execSQL("ALTER TABLE replica_documents_owned RENAME TO replica_documents")
        connection.execSQL(
            "CREATE UNIQUE INDEX index_replica_documents_ownerKey_partition_generation_sortKey ON replica_documents(ownerKey, partition, generation, sortKey)"
        )
    }
}
