package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class CommandPreventionTest {
    @Test fun identityAndOwnership() = withDatabase { verifyAbsentArchiveIdentityAndOwnership(it) }

    @Test
    fun conflictsAndCapacity() = withDatabase { verifyAbsentArchiveActiveConflictAndCapacity(it) }

    private fun withDatabase(block: suspend (AppDatabase) -> Unit) = runTest {
        val directory = Files.createTempDirectory("command-prevention").toFile()
        val database = createAppDatabase(directory)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
