package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CommandStoreTest {
    @Test
    fun immutableCommandsUseAtomicRevisionChecks() = runTest {
        withDatabase(::verifyCommandAtomicity)
    }

    @Test
    fun commandsSurviveLogoutButRequireTheSameAdmittedAccount() = runTest {
        withDatabase(::verifyCommandOwnership)
    }

    @Test fun commandPagesAndStorageAreBounded() = runTest { withDatabase(::verifyCommandBounds) }

    @Test
    fun cancellationLeavesAnAbsentOrCompleteTransaction() = runTest {
        withDatabase(::verifyCommandCancellation)
    }

    @Test
    fun restartRestoresTheOriginalCommandAndOutcome() = runTest {
        val directory = Files.createTempDirectory("command-restart").toFile()
        var database = createAppDatabase(directory)
        val scope = CommandScope("session", "account")
        val entry =
            CommandRecord(
                "company/inbox",
                "operation",
                "item",
                "order",
                "sealed-request",
                "sealed-pending",
            )
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, entry))
            assertTrue(
                database.commands.update(scope, entry.partition, entry.id, 0, "sealed-uncertain")
            )
            database.close()
            database = createAppDatabase(directory)
            val newLogin = scope.copy(sessionId = "replacement-login")
            database.accounts.activate(newLogin.sessionId, newLogin.accountKey)
            assertEquals(
                entry.copy(state = "sealed-uncertain", revision = 1),
                database.commands.get(newLogin, entry.partition, entry.id),
            )
            assertNull(database.commands.get(scope, entry.partition, entry.id))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun versionSixMigrationPreservesCacheAndLeavesCommandsUnbound() = runTest {
        val directory = Files.createTempDirectory("command-migration").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/6.json").readText()
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
                        "PRAGMA user_version = 6",
                        "INSERT INTO account_scope VALUES (1, 'existing-session')",
                        "INSERT INTO inbox VALUES ('existing', 'Retained', 'Fixture', 'inbox', 1, 0)",
                    )
            statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
        val database = createAppDatabase(directory)
        try {
            database.accounts.activate("existing-session")
            assertEquals("existing", database.inbox.observe().first().single().id)
            assertFalse(database.commands.containsRecords())
            val scope = CommandScope("existing-session", "verified-account")
            val entry =
                CommandRecord(
                    "company/inbox",
                    "operation",
                    "item",
                    "order",
                    "sealed-request",
                    "sealed-pending",
                )
            assertEquals(CommandInsertResult.ScopeChanged, database.commands.insert(scope, entry))
            database.accounts.activate(scope.sessionId, scope.accountKey)
            assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, entry))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}

private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
    val directory = Files.createTempDirectory("command-contract").toFile()
    val database = createAppDatabase(directory)
    try {
        block(database)
    } finally {
        database.close()
        directory.deleteRecursively()
    }
}
