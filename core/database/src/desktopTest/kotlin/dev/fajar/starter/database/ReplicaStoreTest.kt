package dev.fajar.starter.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ReplicaStoreTest {
    @Test
    fun versionFiveMigrationRetainsTheExistingAccountAndInbox() = runTest {
        val directory = Files.createTempDirectory("replica-upgrade").toFile()
        val schema =
            Json.parseToJsonElement(
                    File("schemas/dev.fajar.starter.database.StarterDatabase/5.json").readText()
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
                        "PRAGMA user_version = 5",
                        "INSERT INTO account_scope VALUES (1, 'existing-session')",
                        "INSERT INTO inbox VALUES ('existing', 'Retained', 'Fixture', 'inbox', 1, 0)",
                    )
            statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
        }
        val database = createAppDatabase(directory)
        try {
            database.accounts.activate("existing-session")
            assertEquals("existing", database.inbox.observe().first().single().id)
            assertTrue(
                database.replicas.begin(
                    "existing-session",
                    "company/inbox",
                    "new-generation",
                    "sealed-checkpoint",
                    null,
                )
            )
            assertNotNull(database.replicas.checkpoint("existing-session", "company/inbox"))
            database.accounts.activate(null)
            assertTrue(database.inbox.observe().first().isEmpty())
            assertNull(database.replicas.checkpoint("existing-session", "company/inbox"))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun sqliteReplicationContract() = runTest {
        val directory = Files.createTempDirectory("replica-contract").toFile()
        val database = createAppDatabase(directory)
        try {
            verifyReplicaPublication(database)
            verifyReplicaAtomicity(database)
            verifyReplicaTombstones(database)
            verifyReplicaIsolation(database)
            verifyReplicaCancellation(database)
            verifyReplicaPaginationAndBounds(database)
            verifyReplicaSnapshotReplacement(database)
            verifyReplicaSnapshotCancellationAndBounds(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun restartResumesStagingWithoutExposingPartialContent() = runTest {
        val directory = Files.createTempDirectory("replica-restart").toFile()
        var database = createAppDatabase(directory)
        val handle = ReplicaHandle("session", "company/inbox", "staging")
        try {
            database.accounts.activate(handle.sessionId)
            assertTrue(
                database.replicas.begin(
                    handle.sessionId,
                    handle.partition,
                    handle.generation,
                    "bootstrap",
                    null,
                )
            )
            val document = ReplicaDocument("message", 2, "01-message", "encrypted-message")
            assertTrue(database.replicas.commit(handle, 0, "next-page", listOf(document), false))
            database.close()
            database = createAppDatabase(directory)
            database.accounts.activate(handle.sessionId)
            assertTrue(
                database.replicas
                    .observe(handle.sessionId, handle.partition)
                    .first()
                    .documents
                    .isEmpty()
            )
            assertEquals(
                "next-page",
                database.replicas.checkpoint(handle.sessionId, handle.partition)!!.payload,
            )
            assertTrue(database.replicas.commit(handle, 1, "caught-up", emptyList(), true))
            assertEquals(
                document,
                database.replicas.document(handle.sessionId, handle.partition, document.id),
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
