package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class CommandBatchTest {
    @Test fun replayAndArchives() = withDatabase { verifyBatchReplayAndArchives(it) }

    @Test fun conflictsRollBack() = withDatabase { verifyBatchConflictRollsBack(it) }

    @Test fun ownershipAndBounds() = withDatabase { verifyBatchOwnershipAndBounds(it) }

    @Test fun capacity() = withDatabase { verifyBatchCapacity(it) }

    @Test fun racesAndCancellation() = withDatabase { verifyBatchRacesAndCancellation(it) }

    @Test
    fun replaySurvivesReopenAndSessionReplacement() = runTest {
        val directory = Files.createTempDirectory("command-batch-reopen").toFile()
        try {
            val first = createAppDatabase(directory)
            try {
                verifyBatchReplayAndArchives(first)
            } finally {
                first.close()
            }
            val reopened = createAppDatabase(directory)
            try {
                verifyBatchAfterReopen(reopened)
            } finally {
                reopened.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val directory = Files.createTempDirectory("command-batch").toFile()
        val database = createAppDatabase(directory)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
