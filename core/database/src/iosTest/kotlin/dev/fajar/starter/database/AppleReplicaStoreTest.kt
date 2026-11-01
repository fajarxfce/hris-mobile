@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

/** Real bundled SQLite on the simulator, isolated from the application database. */
class AppleReplicaStoreTest {
    @Test
    fun reverseIndexesRetainPublicationOwnershipCancellationAndBounds() = runTest {
        withReplicaDirectory { path ->
            val database = openReplicaDatabase(path)
            try {
                verifyReplicaReverseQueries(database)
                verifyReplicaQueryPublication(database)
                verifyReplicaQueryBounds(database)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun reverseAnchorRetainsCommittedMutationsAfterReopenAndReauthentication() = runTest {
        withReplicaDirectory { path ->
            val initial = openReplicaDatabase(path)
            try {
                verifyReplicaReverseQueries(initial)
            } finally {
                initial.close()
            }
            val resumed = openReplicaDatabase(path)
            try {
                val scope = ReplicaScope("reverse-session", "reverse-account")
                val queries = listOf(ReplicaQuery("even", before = "0300", limit = 3))
                val expected = listOf("0294", "0292", "0290")
                val retained =
                    resumed.replicas
                        .observeQueries(scope, "reverse-company/records", queries)
                        .first()
                assertEquals("changed", retained.checkpoint?.payload)
                assertEquals(expected, retained.pages.single().map { it.sortKey })
                val replacement = scope.copy(sessionId = "reverse-new-login")
                resumed.accounts.activate(replacement.sessionId, replacement.accountKey)
                assertNull(
                    resumed.replicas
                        .observeQueries(scope, "reverse-company/records", queries)
                        .first()
                        .checkpoint
                )
                assertEquals(
                    expected,
                    resumed.replicas
                        .observeQueries(replacement, "reverse-company/records", queries)
                        .first()
                        .pages
                        .single()
                        .map { it.sortKey },
                )
            } finally {
                resumed.close()
            }
        }
    }
}

private fun openReplicaDatabase(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/replica.db"))

private suspend fun withReplicaDirectory(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/replica-test-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
