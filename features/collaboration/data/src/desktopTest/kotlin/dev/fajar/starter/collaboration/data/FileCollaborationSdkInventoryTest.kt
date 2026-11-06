package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationSdkInventoryRepository
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AtomicCredentialStore
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class FileCollaborationSdkInventoryTest {
    private val id = "a".repeat(64)

    @Test
    fun repositoryHoldsTheOriginalFileLockThroughoutCredentialReadingAndReleasesOnCancellation() =
        runTest {
            withRoot { root ->
                val server = IdentityServer(SETUP_BASE, "Fixture")
                val endpoint = CollaborationSdkEndpoint("https://hris.example.test/")
                val scope = collaborationSdkScope(setupOwner, SETUP_DEVICE, server, endpoint)
                val stores = FileCollaborationSdkStoreSource(root)
                stores.acquire(scope.storeId).close()
                val before = snapshot(root)
                var closed = 0
                var cancelRead = false
                val entered = CompletableDeferred<Unit>()
                val repository =
                    StoredCollaborationSdkInventoryRepository(
                        FileCollaborationSdkInventorySource(root),
                        { name ->
                            assertEquals(scope.credentialRecordName, name)
                            object : AtomicCredentialStore {
                                override val persistent = true

                                override suspend fun read(): String? {
                                    assertFailsWith<CollaborationSdkStoreBusyException> {
                                        stores.acquire(scope.storeId)
                                    }
                                    if (cancelRead) {
                                        entered.complete(Unit)
                                        awaitCancellation()
                                    }
                                    return null
                                }

                                override suspend fun write(value: String?) =
                                    error("Unexpected credential write")

                                override suspend fun writeIfAbsent(value: String): String =
                                    error("Unexpected enrollment")

                                override fun close() {
                                    closed++
                                }
                            }
                        },
                        server,
                        endpoint,
                    )
                assertIs<AppResult.Success<*>>(repository.inspect(setupOwner, SETUP_DEVICE))
                assertEquals(1, closed)
                cancelRead = true
                val pending = async { repository.inspect(setupOwner, SETUP_DEVICE) }
                entered.await()
                pending.cancel()
                assertFailsWith<CancellationException> { pending.await() }
                assertEquals(2, closed)
                stores.acquire(scope.storeId).close()
                assertEquals(before, snapshot(root))
            }
        }

    @Test
    fun absentRootOrStoreIsNotCreated() = runTest {
        withRoot { root ->
            val absent = File(root, "absent")
            assertNull(FileCollaborationSdkInventorySource(absent).inspect(id))
            assertFalse(absent.exists())
            assertNull(FileCollaborationSdkInventorySource(root).inspect(id))
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun cacheAndEmptyFilesDoNotStandInForTheTwoOriginalDatabases() = runTest {
        withRoot { root ->
            FileCollaborationSdkStoreSource(root).acquire(id).use {
                File(it.cachePath, "matrix-sdk-crypto.sqlite3").writeText("cache fixture")
                File(it.databasePath, "matrix-sdk-state.sqlite3").createNewFile()
                File(it.databasePath, "matrix-sdk-crypto.sqlite3")
                    .writeText("file metadata fixture")
            }
            val before = snapshot(root)
            val lease = assertNotNull(FileCollaborationSdkInventorySource(root).inspect(id))
            assertFalse(lease.stateDatabasePresent)
            assertTrue(lease.cryptoDatabasePresent)
            assertFalse(lease.toString().contains(root.path))
            assertFalse(lease.toString().contains(id))
            lease.close()
            lease.close()
            assertEquals(before, snapshot(root))
        }
    }

    @Test
    fun inventoryAndRuntimeShareOneExclusiveOwnerButDifferentStoresRemainIndependent() = runTest {
        withRoot { root ->
            val stores = FileCollaborationSdkStoreSource(root)
            val inventory = FileCollaborationSdkInventorySource(root)
            stores.acquire(id).use {
                assertFailsWith<CollaborationSdkStoreBusyException> { inventory.inspect(id) }
            }
            assertNotNull(inventory.inspect(id)).use {
                assertFailsWith<CollaborationSdkStoreBusyException> { stores.acquire(id) }
                assertFailsWith<CollaborationSdkStoreBusyException> { inventory.inspect(id) }
                stores.acquire("b".repeat(64)).close()
            }
            stores.acquire(id).close()
        }
    }

    @Test
    fun missingLockIsAnIncompleteStoreAndInspectionNeverRepairsIt() = runTest {
        withRoot { root ->
            val directory = File(root, id).apply { mkdirs() }
            val source = FileCollaborationSdkInventorySource(root)
            assertFailsWith<IllegalStateException> { source.inspect(id) }
            assertTrue(directory.listFiles()!!.isEmpty())
            File(directory, "owner.lock").createNewFile()
            assertNotNull(source.inspect(id)).use {
                assertFalse(it.cryptoDatabasePresent)
                assertFalse(it.stateDatabasePresent)
            }
            assertFalse(File(directory, "database").exists())
            assertFalse(File(directory, "cache").exists())
        }
    }

    @Test
    fun malformedAndRedirectedPathsFailWithoutTouchingTheDestination() = runTest {
        withRoot { root ->
            val source = FileCollaborationSdkInventorySource(root)
            for (invalid in listOf("../escape", "a".repeat(63), "A".repeat(64))) {
                assertFailsWith<IllegalArgumentException> { source.inspect(invalid) }
            }
            withRoot { outside ->
                val link = File(root, id)
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                assertFailsWith<IllegalArgumentException> { source.inspect(id) }
                assertTrue(outside.listFiles()!!.isEmpty())
                link.delete()
                FileCollaborationSdkStoreSource(root).acquire(id).close()
                val crypto = File(root, "$id/database/matrix-sdk-crypto.sqlite3")
                val target = File(outside, "original").apply { writeText("unchanged") }
                Files.createSymbolicLink(crypto.toPath(), target.toPath())
                assertFailsWith<IllegalArgumentException> { source.inspect(id) }
                assertEquals("unchanged", target.readText())
                crypto.delete()
                // Failure after locking must release both channel and lock.
                FileCollaborationSdkStoreSource(root).acquire(id).close()
            }
        }
    }

    @Test
    fun obstructedDatabasePathIsNotReportedAsAbsentAndTheLockIsReleased() = runTest {
        withRoot { root ->
            val directory = File(root, id).apply { mkdirs() }
            File(directory, "owner.lock").createNewFile()
            val obstruction = File(directory, "database").apply { writeText("retained") }
            assertFailsWith<IllegalStateException> {
                FileCollaborationSdkInventorySource(root).inspect(id)
            }
            assertEquals("retained", obstruction.readText())
            obstruction.delete()
            assertNotNull(FileCollaborationSdkInventorySource(root).inspect(id)).close()
        }
    }

    @Test
    fun cancellationAtReturnReleasesTheExistingLeaseWithoutChangingFiles() = runTest {
        withRoot { root ->
            FileCollaborationSdkStoreSource(root).acquire(id).close()
            val before = snapshot(root)
            val scheduled = StandardTestDispatcher(testScheduler)
            lateinit var pending: Deferred<CollaborationSdkInventoryLease?>
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
            pending = async { FileCollaborationSdkInventorySource(root, io).inspect(id) }
            assertFailsWith<CancellationException> { pending.await() }
            FileCollaborationSdkStoreSource(root).acquire(id).close()
            assertEquals(before, snapshot(root))
        }
    }

    private fun snapshot(root: File): Map<String, List<Byte>?> =
        root.walkTopDown().associate {
            it.relativeTo(root).path to if (it.isFile) it.readBytes().toList() else null
        }

    private suspend fun withRoot(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("sdk-inventory-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
