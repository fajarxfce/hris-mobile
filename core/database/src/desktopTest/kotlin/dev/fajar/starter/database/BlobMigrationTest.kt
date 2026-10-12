package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class BlobMigrationTest {
    @Test
    fun versionSevenMigrationPreservesBoundCommands() = runTest {
        val directory = Files.createTempDirectory("blob-migration").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/7.json").readText()
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
                        "PRAGMA user_version = 7",
                        "INSERT INTO account_scope (id, sessionId, accountKey) VALUES (1, 'session', 'account')",
                        "INSERT INTO durable_commands (accountKey, partition, id, resourceId, sortKey, request, state, revision) VALUES ('account', 'company/commands', 'operation', 'resource', 'order', 'sealed-request', 'sealed-state', 4)",
                    )
            statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
        val database = createAppDatabase(directory)
        try {
            val original =
                CommandRecord(
                    "company/commands",
                    "operation",
                    "resource",
                    "order",
                    "sealed-request",
                    "sealed-state",
                    4,
                )
            assertEquals(
                original,
                database.commands.get(
                    CommandScope("session", "account"),
                    original.partition,
                    original.id,
                ),
            )
            assertFalse(database.blobs.containsRecords())
            val scope = BlobScope("session", "account")
            val file = BlobRecord("company/files", "file", 3, "sealed-metadata")
            assertEquals(BlobInsertResult.Stored, database.blobs.insert(scope, file, 10, 1))
            assertTrue(
                database.blobs.append(
                    scope,
                    file.partition,
                    file.id,
                    0,
                    BlobChunkRecord(0, 3, "sealed-block"),
                )
            )
            assertEquals(
                original,
                database.commands.get(
                    CommandScope("session", "account"),
                    original.partition,
                    original.id,
                ),
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
