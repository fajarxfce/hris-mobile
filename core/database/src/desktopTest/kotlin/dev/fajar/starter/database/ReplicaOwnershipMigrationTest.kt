package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ReplicaOwnershipMigrationTest {
    @Test
    fun upgradeRetainsPublishedStagedAndDeletedRecords() = runTest {
        val directory = Files.createTempDirectory("replica-owner-migration").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/8.json").readText()
                )
                .jsonObject
                .getValue("database")
                .jsonObject
        BundledSQLiteDriver().open(directory.resolve("starter.db").absolutePath).use { connection ->
            val statements =
                schema.getValue("entities").jsonArray.flatMap { entry ->
                    val entity = entry.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    (listOf(entity.getValue("createSql")) +
                            entity["indices"]?.jsonArray.orEmpty().map {
                                it.jsonObject.getValue("createSql")
                            })
                        .map { it.jsonPrimitive.content.replace("$" + "{TABLE_NAME}", table) }
                } +
                    schema.getValue("setupQueries").jsonArray.map { it.jsonPrimitive.content } +
                    listOf(
                        "PRAGMA user_version = 8",
                        "INSERT INTO account_scope VALUES (1, 'legacy-session', 'legacy-account')",
                        "INSERT INTO replica_heads VALUES ('legacy/inbox', 'active', 'staged')",
                        "INSERT INTO replica_generations VALUES ('legacy/inbox', 'active', 'legacy-session', 2, 'active-checkpoint')",
                        "INSERT INTO replica_generations VALUES ('legacy/inbox', 'staged', 'legacy-session', 3, 'staging-checkpoint')",
                        "INSERT INTO replica_documents VALUES ('legacy/inbox', 'active', 'message', 2, '01', 'sealed-message')",
                        "INSERT INTO replica_documents VALUES ('legacy/inbox', 'staged', 'message', 3, '01', 'staged-message')",
                        "INSERT INTO replica_documents VALUES ('legacy/inbox', 'staged', 'deleted', 4, '02', NULL)",
                    )
            statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
        val database = createAppDatabase(directory)
        try {
            verifyLegacyReplicaRetention(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
