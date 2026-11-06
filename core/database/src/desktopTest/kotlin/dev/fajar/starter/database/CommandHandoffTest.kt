package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class CommandHandoffTest {
    @Test fun replayAndArchives() = withDatabase { verifyCommandHandoffPreservesBoth(it) }

    @Test fun conflictsRollBack() = withDatabase { verifyCommandHandoffConflictsAndRollback(it) }

    @Test fun ownershipAndBounds() = withDatabase { verifyCommandHandoffOwnershipAndBounds(it) }

    @Test fun capacity() = withDatabase { verifyCommandHandoffAtCapacity(it) }

    @Test fun competingHandoffsAndStateUpdate() = withDatabase { verifyCommandHandoffRaces(it) }

    @Test fun concurrentSessionReplacement() = withDatabase { verifyCommandHandoffSessionRace(it) }

    @Test
    fun observationNeverExposesAHalfTransition() = withDatabase {
        verifyCommandHandoffObservation(it)
    }

    @Test fun cancellation() = withDatabase { verifyCommandHandoffCancellation(it) }

    @Test
    fun replaySurvivesReopenAndSessionReplacement() = runTest {
        val directory = Files.createTempDirectory("command-handoff-reopen").toFile()
        try {
            val first = createAppDatabase(directory)
            try {
                verifyCommandHandoffPreservesBoth(first)
            } finally {
                first.close()
            }
            val reopened = createAppDatabase(directory)
            try {
                verifyCommandHandoffAfterReopen(reopened)
            } finally {
                reopened.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val directory = Files.createTempDirectory("command-handoff").toFile()
        val database = createAppDatabase(directory)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
