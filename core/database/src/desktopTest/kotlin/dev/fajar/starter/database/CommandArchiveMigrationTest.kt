package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CommandArchiveMigrationTest {
    private val scope = CommandScope("session", "account")
    private val original =
        CommandRecord(
            "company/commands",
            "operation",
            "resource",
            "order",
            "sealed-request",
            "sealed-state",
        )

    @Test
    fun versionTenMigrationPreservesBoundCommandsAndCanArchiveThem() = runTest {
        val directory = Files.createTempDirectory("command-archive-migration").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/10.json").readText()
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
                        "PRAGMA user_version = 10",
                        "INSERT INTO account_scope (id, sessionId, accountKey) VALUES (1, 'session', 'account')",
                        "INSERT INTO durable_commands (accountKey, partition, id, resourceId, sortKey, request, state, revision) VALUES ('account', 'company/commands', 'operation', 'resource', 'order', 'sealed-request', 'sealed-state', 0)",
                    )
            statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
        val database = createAppDatabase(directory)
        try {
            assertEquals(original, database.commands.get(scope, original.partition, original.id))
            assertNull(database.commands.archived(scope, original.partition, original.id))
            assertTrue(database.commands.archive(scope, original, "sealed-proof"))
            assertEquals(
                CommandArchive(original, "sealed-proof"),
                database.commands.archived(scope, original.partition, original.id),
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun abortBetweenArchiveCreationAndPendingDeletionRollsBackBoth() = runTest {
        val directory = Files.createTempDirectory("command-archive-rollback").toFile()
        val database = createAppDatabase(directory)
        fun execute(sql: String) =
            BundledSQLiteDriver().open(directory.resolve("starter.db").absolutePath).use {
                connection ->
                connection.prepare(sql).use { it.step() }
            }
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            database.commands.insert(scope, original)
            execute(
                "CREATE TRIGGER reject_archive_delete BEFORE DELETE ON durable_commands BEGIN SELECT RAISE(ABORT, 'fixture interruption'); END"
            )
            assertFails { database.commands.archive(scope, original, "sealed-proof") }
            assertEquals(original, database.commands.get(scope, original.partition, original.id))
            assertNull(database.commands.archived(scope, original.partition, original.id))
            execute("DROP TRIGGER reject_archive_delete")
            assertTrue(database.commands.archive(scope, original, "sealed-proof"))
            assertNull(database.commands.get(scope, original.partition, original.id))
            assertEquals(
                CommandArchive(original, "sealed-proof"),
                database.commands.archived(scope, original.partition, original.id),
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun completionAndInsertionFenceSurviveDatabaseReopeningAndReauthentication() = runTest {
        val directory = Files.createTempDirectory("command-archive-restart").toFile()
        var database = createAppDatabase(directory)
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            database.commands.insert(scope, original)
            assertTrue(
                database.commands.update(
                    scope,
                    original.partition,
                    original.id,
                    0,
                    "sealed-completed",
                )
            )
            assertTrue(
                database.commands.archive(
                    scope,
                    original.copy(revision = 1, state = "sealed-completed"),
                    "sealed-proof",
                )
            )
            database.close()
            database = createAppDatabase(directory)
            val resumed = scope.copy(sessionId = "new-session")
            database.accounts.activate(resumed.sessionId, resumed.accountKey)
            assertNull(database.commands.archived(scope, original.partition, original.id))
            assertEquals(
                CommandArchive(
                    original.copy(state = "sealed-completed", revision = 1),
                    "sealed-proof",
                ),
                database.commands.archived(resumed, original.partition, original.id),
            )
            assertEquals(
                CommandInsertResult.AlreadyStored,
                database.commands.insert(resumed, original),
            )
            assertNull(database.commands.get(resumed, original.partition, original.id))
            assertTrue(database.commands.containsRecords())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
