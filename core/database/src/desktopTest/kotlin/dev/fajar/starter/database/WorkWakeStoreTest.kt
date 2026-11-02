package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class WorkWakeStoreTest {
    @Test
    fun collapseAndAcknowledgmentKeepTheNewestGeneration() = runTest {
        withDatabase { verifyWorkWakeGenerations(it.workWakes) }
    }

    @Test
    fun concurrentNewKeysCannotExceedCapacityButExistingKeysCanAdvance() = runTest {
        withDatabase { verifyWorkWakeCapacity(it.workWakes) }
    }

    @Test
    fun anOldCompletionCannotEraseAConcurrentWake() = runTest {
        withDatabase { verifyWorkWakeAcknowledgmentRace(it.workWakes) }
    }

    @Test
    fun schedulingMetadataSurvivesAccountReplacementWithoutCarryingAccountIdentity() = runTest {
        withDatabase { verifyWorkWakeGlobalOwnership(it) }
    }

    @Test
    fun cancellationAndInvalidBoundsCannotCreateAWake() = runTest {
        withDatabase { verifyWorkWakeCancellationAndBounds(it.workWakes) }
    }

    @Test
    fun pendingAndAcknowledgedGenerationsSurviveClosingAndReopeningTheDatabase() = runTest {
        val directory = Files.createTempDirectory("work-wake-reopen").toFile()
        var database = createAppDatabase(directory)
        val pending = WorkWakeRecord("chat.recovery", "original", true)
        try {
            assertEquals(pending, database.workWakes.request(pending.key, pending.generation, 1))
            database.close()
            database = createAppDatabase(directory)
            assertEquals(pending, database.workWakes.get(pending.key))
            assertTrue(database.workWakes.acknowledge(pending.key, pending.generation))
            database.close()
            database = createAppDatabase(directory)
            assertEquals(pending.copy(pending = false), database.workWakes.get(pending.key))
            assertEquals(
                pending.copy(pending = false),
                database.workWakes.request(pending.key, pending.generation, 1),
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val directory = Files.createTempDirectory("work-wake-contract").toFile()
        val database = createAppDatabase(directory)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
