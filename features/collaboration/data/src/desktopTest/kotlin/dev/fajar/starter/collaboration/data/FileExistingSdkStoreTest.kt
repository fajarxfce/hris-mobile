package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class FileExistingSdkStoreTest {
    @Test
    fun missingRootAndNamespaceNeverCreateAnyFiles() = runTest {
        withRoot { root ->
            val missing = File(root, "absent")
            assertNull(FileCollaborationSdkStoreSource(missing).acquireExisting(STORE))
            assertFalse(missing.exists())
            assertNull(FileCollaborationSdkStoreSource(root).acquireExisting(STORE))
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun originalPairAndSidecarsRemainUnderTheSameExclusiveLock() = runTest {
        withRoot { root ->
            seed(root)
            val before = snapshot(root)
            val source = FileCollaborationSdkStoreSource(root)
            val lease = assertNotNull(source.acquireExisting(STORE))
            assertTrue(lease.initiallyOccupied)
            assertFailsWith<CollaborationSdkStoreBusyException> { source.acquire(STORE) }
            assertFailsWith<CollaborationSdkStoreBusyException> { source.acquireExisting(STORE) }
            assertFailsWith<CollaborationSdkStoreBusyException> {
                FileCollaborationSdkInventorySource(root).inspect(STORE)
            }
            lease.close()
            lease.close()
            assertEquals(before, snapshot(root))
            assertNotNull(source.acquireExisting(STORE)).close()
            assertEquals(before, snapshot(root))
        }
    }

    @Test
    fun partialStoresFailWithoutRepairAndReleaseTheirLock() = runTest {
        for (path in
            listOf(
                "owner.lock",
                "database/matrix-sdk-state.sqlite3",
                "database/matrix-sdk-crypto.sqlite3",
                "cache",
            )) {
            withRoot { root ->
                seed(root)
                val removed = File(root, "$STORE/$path")
                assertTrue(
                    if (removed.isDirectory) removed.deleteRecursively() else removed.delete()
                )
                val before = snapshot(root)
                val source = FileCollaborationSdkStoreSource(root)
                repeat(2) {
                    assertFailsWith<IllegalStateException> { source.acquireExisting(STORE) }
                }
                assertEquals(before, snapshot(root))
                assertFalse(removed.exists())
            }
        }
    }

    @Test
    fun emptyOrSymlinkedDatabaseCannotBecomeAnExistingStore() = runTest {
        withRoot { root ->
            seed(root)
            val crypto = File(root, "$STORE/database/matrix-sdk-crypto.sqlite3")
            crypto.writeBytes(byteArrayOf())
            assertFailsWith<IllegalStateException> {
                FileCollaborationSdkStoreSource(root).acquireExisting(STORE)
            }
            val outside = File(root, "outside").apply { writeText("unrelated ciphertext") }
            assertTrue(crypto.delete())
            Files.createSymbolicLink(crypto.toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> {
                FileCollaborationSdkStoreSource(root).acquireExisting(STORE)
            }
            assertEquals("unrelated ciphertext", outside.readText())
            Files.delete(crypto.toPath())
        }
    }

    @Test
    fun cancellationAtReturnReleasesTheOriginalLeaseWithoutChangingFiles() = runTest {
        withRoot { root ->
            seed(root)
            val before = snapshot(root)
            val scheduled = StandardTestDispatcher(testScheduler)
            lateinit var pending: Deferred<CollaborationSdkStoreLease?>
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
            pending = async { FileCollaborationSdkStoreSource(root, io).acquireExisting(STORE) }
            assertFailsWith<CancellationException> { pending.await() }
            assertNotNull(FileCollaborationSdkStoreSource(root).acquireExisting(STORE)).close()
            assertEquals(before, snapshot(root))
        }
    }

    private suspend fun seed(root: File) {
        FileCollaborationSdkStoreSource(root).acquire(STORE).use { lease ->
            for (name in
                listOf(
                    "matrix-sdk-state.sqlite3",
                    "matrix-sdk-crypto.sqlite3",
                    "matrix-sdk-crypto.sqlite3-wal",
                    "matrix-sdk-crypto.sqlite3-shm",
                )) File(lease.databasePath, name).writeText("original $name")
            File(lease.cachePath, "retained").writeText("original media")
        }
    }

    private fun snapshot(root: File) =
        root
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(root).path to it.readBytes().toList() }

    private suspend fun withRoot(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("existing-sdk-store").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val STORE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
