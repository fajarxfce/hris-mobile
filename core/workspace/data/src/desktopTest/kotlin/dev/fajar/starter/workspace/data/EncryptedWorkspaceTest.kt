package dev.fajar.starter.workspace.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.workspace.data.datasources.WorkspaceRemoteDataSource
import dev.fajar.starter.workspace.data.repositories.EncryptedWorkspaceRepository
import dev.fajar.starter.workspace.domain.entities.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EncryptedWorkspaceTest {
    private val now = Instant.parse("2026-10-11T00:00:00Z")
    private val server = IdentityServer("https://hris.example.invalid/", "Fixture")
    private val workspace =
        Workspace(
            "10000000-0000-4000-8000-000000000001",
            "20000000-0000-4000-8000-000000000001",
            server.audience,
            CompanyMembership(
                "30000000-0000-4000-8000-000000000001",
                "Private company",
                "CO",
                "Asia/Jakarta",
            ),
            setOf("people.self.read"),
            WorkspaceAdmission(
                "40000000-0000-4000-8000-000000000001",
                "50000000-0000-4000-8000-000000000001",
                now,
                now + 1.hours,
            ),
            policy =
                CompanyPolicySnapshot(
                    CompanyPolicy(
                        1,
                        setOf(CompanyModule.PEOPLE),
                        CompanyMinimumBuilds(2, 0, 0),
                        null,
                        false,
                        now,
                        now + 60.seconds,
                    ),
                    now,
                ),
        )
    private val remote =
        object : WorkspaceRemoteDataSource {
            override suspend fun clientPolicy(sessionId: String, companyId: String) =
                error("Local storage must not acquire network authority.")

            override suspend fun permissions(sessionId: String, companyId: String) =
                error("Local storage must not acquire network authority.")
        }

    @Test
    fun legacySelectionsRemainReadableWithoutInventingAnAvailabilityPolicy() = runTest {
        val directory = Files.createTempDirectory("workspace-legacy").toFile()
        val database = createAppDatabase(directory)
        try {
            database.accounts.activate(workspace.sessionId)
            val repository =
                EncryptedWorkspaceRepository(
                    database.replicas,
                    remote,
                    AesGcmContentCipher(),
                    MemoryCredentialStore(),
                    server,
                )
            val legacy = workspace.copy(policy = null)
            assertEquals(
                AppResult.Success(true),
                repository.compareAndSet(workspace.sessionId, null, legacy),
            )
            val restored =
                assertIs<AppResult.Success<Workspace?>>(repository.current(workspace.sessionId))
                    .value
            assertEquals(legacy, restored)
            assertNull(restored!!.policy)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun encryptedSelectionSurvivesReopeningAndLogoutRemovesTheDerivedRecord() = runTest {
        val directory = Files.createTempDirectory("workspace-reopen").toFile()
        var database = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            database.accounts.activate(workspace.sessionId)
            var repository =
                EncryptedWorkspaceRepository(
                    database.replicas,
                    remote,
                    AesGcmContentCipher(),
                    keys,
                    server,
                )
            assertEquals(
                AppResult.Success(true),
                repository.compareAndSet(workspace.sessionId, null, workspace),
            )
            val raw = database.replicas.checkpoint(workspace.sessionId, "workspace/selected")!!
            assertFalse(raw.payload.contains(workspace.company.name))
            assertFalse(raw.payload.contains("people.self.read"))
            database.close()
            database = createAppDatabase(directory)
            database.accounts.activate(workspace.sessionId)
            repository =
                EncryptedWorkspaceRepository(
                    database.replicas,
                    remote,
                    AesGcmContentCipher(),
                    keys,
                    server,
                )
            assertEquals(
                AppResult.Success(workspace),
                repository.observe(workspace.sessionId).first(),
            )
            database.accounts.activate(null)
            assertEquals(AppResult.Success(null), repository.current(workspace.sessionId))
            database.accounts.activate("another-session")
            assertEquals(
                AppResult.Success(false),
                repository.compareAndSet(workspace.sessionId, null, workspace),
            )
            assertEquals(AppResult.Success(null), repository.current("another-session"))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun independentRepositoryOwnersCannotOverwriteTheAcceptedChoiceOrClearANewerOne() = runTest {
        withDatabase { database, keys ->
            val first =
                EncryptedWorkspaceRepository(
                    database.replicas,
                    remote,
                    AesGcmContentCipher(),
                    keys,
                    server,
                )
            val second =
                EncryptedWorkspaceRepository(
                    database.replicas,
                    remote,
                    AesGcmContentCipher(),
                    keys,
                    server,
                )
            first.compareAndSet(workspace.sessionId, null, workspace)
            val candidates =
                listOf(first, second).mapIndexed { index, repository ->
                    async {
                        repository.compareAndSet(
                            workspace.sessionId,
                            workspace,
                            workspace.copy(
                                id = "10000000-0000-4000-8000-00000000000${index + 2}",
                                company = workspace.company.copy(name = "Company ${index + 2}"),
                            ),
                        )
                    }
                }
            assertEquals(1, candidates.count { (it.await() as AppResult.Success).value })
            assertEquals(
                AppResult.Success(false),
                first.compareAndSet(workspace.sessionId, workspace, null),
            )
            val accepted =
                assertIs<AppResult.Success<Workspace?>>(second.current(workspace.sessionId)).value!!
            assertNotEquals(workspace.id, accepted.id)
            assertEquals(
                AppResult.Success(true),
                first.compareAndSet(workspace.sessionId, accepted, null),
            )
            assertEquals(AppResult.Success(null), second.current(workspace.sessionId))
        }
    }

    @Test
    fun keyLossAndCopiedCiphertextFailClosedWithoutCreatingReplacementKeys() = runTest {
        withDatabase { database, keys ->
            val repository =
                EncryptedWorkspaceRepository(
                    database.replicas,
                    remote,
                    AesGcmContentCipher(),
                    keys,
                    server,
                )
            repository.compareAndSet(workspace.sessionId, null, workspace)
            val key = keys.read()
            val original = database.replicas.checkpoint(workspace.sessionId, "workspace/selected")!!
            keys.write(null)
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(repository.current(workspace.sessionId)).failure.kind,
            )
            assertIs<AppResult.Failed>(
                repository.compareAndSet(workspace.sessionId, workspace, workspace)
            )
            assertNull(keys.read())
            keys.write(key)
            assertEquals(AppResult.Success(workspace), repository.current(workspace.sessionId))
            assertTrue(
                database.replicas.replace(
                    workspace.sessionId,
                    "workspace/selected",
                    original,
                    ReplicaSnapshot("copied-generation", original.payload, emptyList()),
                )
            )
            assertIs<AppResult.Failed>(repository.observe(workspace.sessionId).first())
        }
    }

    @Test
    fun aLateEncryptedWriteCannotRecreateThePreviousAccountsScope() = runTest {
        withDatabase { database, keys ->
            val cipher = AesGcmContentCipher()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed =
                object : ContentCipher by cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return cipher.encrypt(key, associatedData, plaintext)
                    }
                }
            val repository =
                EncryptedWorkspaceRepository(database.replicas, remote, delayed, keys, server)
            val pending = async { repository.compareAndSet(workspace.sessionId, null, workspace) }
            entered.await()
            database.accounts.activate("another-session")
            release.complete(Unit)
            assertEquals(AppResult.Success(false), pending.await())
            assertEquals(AppResult.Success(null), repository.current("another-session"))
        }
    }

    @Test
    fun cancellationBeforeStorageAdmissionPropagatesAndLeavesNoSelection() = runTest {
        withDatabase { database, keys ->
            val cipher = AesGcmContentCipher()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed =
                object : ContentCipher by cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return cipher.encrypt(key, associatedData, plaintext)
                    }
                }
            val repository =
                EncryptedWorkspaceRepository(database.replicas, remote, delayed, keys, server)
            val pending = async { repository.compareAndSet(workspace.sessionId, null, workspace) }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(AppResult.Success(null), repository.current(workspace.sessionId))
        }
    }

    private suspend fun withDatabase(
        test: suspend CoroutineScope.(AppDatabase, MemoryCredentialStore) -> Unit
    ) = coroutineScope {
        val directory = Files.createTempDirectory("workspace-storage").toFile()
        val database = createAppDatabase(directory)
        try {
            database.accounts.activate(workspace.sessionId)
            test(database, MemoryCredentialStore())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
