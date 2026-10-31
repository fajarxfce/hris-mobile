package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class FileCollaborationSdkStoreTest {
    @Test
    fun concurrentOwnersCannotOpenTheSameStoreAndCloseNeverRemovesItsFiles() = runTest {
        withRoot { root ->
            val first = FileCollaborationSdkStoreSource(root).acquire("a".repeat(64))
            try {
                assertFalse(first.initiallyOccupied)
                assertFailsWith<CollaborationSdkStoreBusyException> {
                    FileCollaborationSdkStoreSource(root).acquire("a".repeat(64))
                }
                File(first.databasePath, "retained.sqlite").writeText("fixture ciphertext")
            } finally {
                first.close()
            }
            first.close()
            FileCollaborationSdkStoreSource(root).acquire("a".repeat(64)).use { reopened ->
                assertTrue(reopened.initiallyOccupied)
                assertEquals(
                    "fixture ciphertext",
                    File(reopened.databasePath, "retained.sqlite").readText(),
                )
            }
        }
    }

    @Test
    fun separateStoreIdentitiesHaveIndependentLocksAndDirectories() = runTest {
        withRoot { root ->
            FileCollaborationSdkStoreSource(root).acquire("a".repeat(64)).use { first ->
                FileCollaborationSdkStoreSource(root).acquire("b".repeat(64)).use { second ->
                    assertNotEquals(first.databasePath, second.databasePath)
                    File(first.cachePath, "retained-cache").writeText("ciphertext")
                    assertFalse(second.initiallyOccupied)
                    assertFalse(second.toString().contains(root.path))
                }
            }
        }
    }

    @Test
    fun malformedPathsAndSymlinkedDirectoriesCannotEscapeThePrivateRoot() = runTest {
        withRoot { root ->
            val source = FileCollaborationSdkStoreSource(root)
            listOf("../escape", "a".repeat(63), "A".repeat(64), "/" + "a".repeat(64)).forEach {
                assertFailsWith<IllegalArgumentException> { source.acquire(it) }
            }
            val outside = Files.createTempDirectory("collaboration-outside").toFile()
            try {
                Files.createSymbolicLink(File(root, "a".repeat(64)).toPath(), outside.toPath())
                assertFailsWith<IllegalArgumentException> { source.acquire("a".repeat(64)) }
                assertTrue(outside.listFiles()!!.isEmpty())
            } finally {
                File(root, "a".repeat(64)).delete()
                outside.deleteRecursively()
            }
        }
    }

    @Test
    fun failedDirectoryPreparationReleasesTheAcquiredFileLock() = runTest {
        withRoot { root ->
            val directory = File(root, "a".repeat(64)).apply { mkdirs() }
            val obstruction =
                File(directory, "database").apply { writeText("retained invalid file") }
            assertFailsWith<IllegalStateException> {
                FileCollaborationSdkStoreSource(root).acquire("a".repeat(64))
            }
            assertEquals("retained invalid file", obstruction.readText())
            assertTrue(obstruction.delete())
            FileCollaborationSdkStoreSource(root).acquire("a".repeat(64)).close()
        }
    }

    @Test
    fun cancellationBeforeAcquisitionCannotLeaveAStoreOwner() = runTest {
        withRoot { root ->
            val pending =
                async(start = CoroutineStart.LAZY) {
                    FileCollaborationSdkStoreSource(root).acquire("a".repeat(64))
                }
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            FileCollaborationSdkStoreSource(root).acquire("a".repeat(64)).close()
        }
    }

    @Test
    fun cancellationAtTheDispatcherHandoffReleasesTheAlreadyAcquiredLock() = runTest {
        withRoot { root ->
            val scheduled = StandardTestDispatcher(testScheduler)
            lateinit var pending: Deferred<CollaborationSdkStoreLease>
            val io =
                object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        scheduled.dispatch(
                            context,
                            Runnable {
                                block.run()
                                pending.cancel()
                            },
                        )
                    }
                }
            pending = async { FileCollaborationSdkStoreSource(root, io).acquire("a".repeat(64)) }
            assertFailsWith<CancellationException> { pending.await() }
            assertTrue(File(root, "a".repeat(64) + "/owner.lock").isFile)
            FileCollaborationSdkStoreSource(root).acquire("a".repeat(64)).close()
        }
    }

    private suspend fun withRoot(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("collaboration-sdk-store").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
