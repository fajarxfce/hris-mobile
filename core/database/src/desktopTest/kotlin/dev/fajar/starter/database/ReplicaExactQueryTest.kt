package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ReplicaExactQueryTest {
    @Test
    fun exactIdentityAndIndexesShareOneCheckpointThroughDeletionAndRevival() = runTest {
        withReplicaExactQueriesStore { verifyReplicaExactSelections(it) }
    }

    @Test
    fun publicationOwnershipAndDisposalRetainCoherentExactRecords() = runTest {
        withReplicaExactQueriesStore { verifyReplicaExactPublication(it) }
    }

    @Test
    fun exactSelectionRejectsAmbiguityAndCapturesCallerInput() = runTest {
        withReplicaExactQueriesStore { verifyReplicaExactBounds(it) }
    }

    @Test
    fun exactRecordsSurviveRestartWhileStagingStaysHidden() = runTest {
        withReplicaExactQueriesPath { path ->
            val first = openReplicaExactQueriesStore(path)
            try {
                prepareReplicaExactRestart(first)
            } finally {
                first.close()
            }
            val second = openReplicaExactQueriesStore(path)
            try {
                verifyReplicaExactRestart(second)
            } finally {
                second.close()
            }
        }
    }
}

private suspend fun withReplicaExactQueriesStore(block: suspend (AppDatabase) -> Unit) {
    withReplicaExactQueriesPath { path ->
        val database = openReplicaExactQueriesStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openReplicaExactQueriesStore(path: java.io.File) = createAppDatabase(path)

private suspend fun withReplicaExactQueriesPath(block: suspend (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("replica-exact-query").toFile()
    try {
        block(directory)
    } finally {
        check(directory.deleteRecursively())
    }
}
