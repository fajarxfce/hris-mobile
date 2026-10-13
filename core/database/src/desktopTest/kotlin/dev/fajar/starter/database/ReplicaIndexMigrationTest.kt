package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ReplicaIndexMigrationTest {
    @Test
    fun accountReplicaSurvivesIndexMigrationWithoutRelabelingItsCiphertext() = runTest {
        val directory = Files.createTempDirectory("replica-index-migration").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/9.json").readText()
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
                        "PRAGMA user_version = 9",
                        "INSERT INTO account_scope VALUES (1, 'indexed-session', 'indexed-account')",
                        "INSERT INTO replica_heads VALUES ('indexed-account', 'indexed/records', 'active', 'staged')",
                        "INSERT INTO replica_generations VALUES ('indexed-account', 'indexed/records', 'active', 2, 'active-cursor')",
                        "INSERT INTO replica_generations VALUES ('indexed-account', 'indexed/records', 'staged', 3, 'staging-cursor')",
                        "INSERT INTO replica_documents VALUES ('indexed-account', 'indexed/records', 'active', 'record', 2, '01', 'original-ciphertext')",
                        "INSERT INTO replica_documents VALUES ('indexed-account', 'indexed/records', 'staged', 'record', 3, '01', 'staged-ciphertext')",
                        "INSERT INTO replica_documents VALUES ('indexed-account', 'indexed/records', 'staged', 'deleted', 4, '02', NULL)",
                    )
            statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
        val database = createAppDatabase(directory)
        try {
            verifyReplicaIndexMigration(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
