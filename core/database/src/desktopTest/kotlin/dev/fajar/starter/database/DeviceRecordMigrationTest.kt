package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class DeviceRecordMigrationTest {
    @Test
    fun versionTwelveMigrationPreservesCommandsAndWakesWhileAddingDeviceRecords() = runTest {
        val directory = Files.createTempDirectory("device-record-migration").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/12.json").readText()
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
                        "PRAGMA user_version = 12",
                        "INSERT INTO work_wakes (`key`, generation, pending) VALUES ('chat.recovery', 'first', 1)",
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
            val wake = WorkWakeRecord("chat.recovery", "first", true)
            assertEquals(wake, database.workWakes.get(wake.key))
            assertEquals(wake, database.workWakes.request(wake.key, wake.generation, 1))
            val record = DeviceRecord("input", "first", "original", "sealed-input")
            assertNull(database.deviceRecords.get(record.namespace, record.id))
            assertEquals(record, database.deviceRecords.retain(record, 1))
            assertEquals(record, database.deviceRecords.get(record.namespace, record.id))
            assertEquals(wake, database.workWakes.get(wake.key))
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
