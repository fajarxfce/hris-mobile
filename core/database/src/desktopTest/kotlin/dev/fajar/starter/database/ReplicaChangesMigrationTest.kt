package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ReplicaChangesMigrationTest {
    @Test
    fun versionThirteenPreservesOriginalVersionsCiphertextTagsAndStaging() = runTest {
        val directory = Files.createTempDirectory("replica-changes-migration").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/13.json").readText()
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
                        "PRAGMA user_version = 13",
                        "INSERT INTO account_scope VALUES (1, 'migration-session', 'migration-account')",
                        "INSERT INTO replica_heads VALUES ('migration-account', 'migration/records', 'active', 'staged')",
                        "INSERT INTO replica_generations VALUES ('migration-account', 'migration/records', 'active', 12, 'original-checkpoint')",
                        "INSERT INTO replica_generations VALUES ('migration-account', 'migration/records', 'staged', 13, 'staged-checkpoint')",
                        "INSERT INTO replica_documents VALUES ('migration-account', 'migration/records', 'active', 'value', 900, '01', 'original-ciphertext', '[\"visible\"]')",
                        "INSERT INTO replica_documents VALUES ('migration-account', 'migration/records', 'active', 'deleted', 901, '02', NULL, '[]')",
                        "INSERT INTO replica_documents VALUES ('migration-account', 'migration/records', 'staged', 'value', 902, '01', 'staged-ciphertext', '[\"hidden\"]')",
                        "INSERT INTO replica_tags VALUES ('migration-account', 'migration/records', 'active', 'value', 'visible', '01')",
                        "INSERT INTO replica_tags VALUES ('migration-account', 'migration/records', 'staged', 'value', 'hidden', '01')",
                    )
            statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
        val database = createAppDatabase(directory)
        try {
            verifyReplicaChangesMigration(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
