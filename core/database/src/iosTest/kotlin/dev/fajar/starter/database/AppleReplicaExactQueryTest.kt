@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleReplicaExactQueryTest {
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

private fun openReplicaExactQueriesStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/replica-exact-query.db"))

private suspend fun withReplicaExactQueriesPath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/replica-exact-query-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
