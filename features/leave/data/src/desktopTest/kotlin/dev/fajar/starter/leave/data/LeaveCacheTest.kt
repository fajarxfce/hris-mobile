package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.EncryptedLeaveCacheRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class LeaveCacheTest {
    private val f = LeaveDataFixture()
    private val snapshot =
        LeaveOverview(
            f.access,
            "generation",
            f.directory.toDirectory(f.access, null),
            LeaveSelection(f.employee.id, 2026),
            f.balances.toBalances(f.employee.id, 2026, null),
            f.requests.toRequests(f.employee.id, null, null),
            f.now,
            f.access.authorizationExpiresAt,
        )

    @Test
    fun sqliteStoresCiphertextAndReplacementPublishesOneCompleteViewAtomically() = runTest {
        val directory = Files.createTempDirectory("leave-cache").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(f.access.sessionId)
            val repository = EncryptedLeaveCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repository.replace(f.access, null, snapshot))
            assertEquals(AppResult.Success(snapshot), repository.observe(f.access).first())
            val raw =
                db.replicas
                    .observeDocument(f.access.sessionId, leavePartition(f.access), "overview")
                    .first()
            assertEquals(1, raw.documents.size)
            assertFalse(raw.documents.single().payload!!.contains("Annual leave"))
            assertFalse(raw.documents.single().payload!!.contains("employeeNumber"))
            assertFalse(raw.checkpoint!!.payload.contains("2026-10-12"))
            val replacement = snapshot.copy(generation = "replacement")
            assertEquals(
                AppResult.Success(true),
                repository.replace(f.access, snapshot.generation, replacement),
            )
            assertEquals(
                AppResult.Success(false),
                repository.replace(
                    f.access,
                    snapshot.generation,
                    snapshot.copy(generation = "late"),
                ),
            )
            val restored = EncryptedLeaveCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(replacement), restored.observe(f.access).first())
            assertEquals(
                AppResult.Success(false),
                restored.replace(f.access, snapshot.generation, null),
            )
            assertEquals(
                AppResult.Success(true),
                restored.replace(f.access, replacement.generation, null),
            )
            assertEquals(AppResult.Success(null), restored.observe(f.access).first())
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun encryptedContentCannotMoveToAnotherOwnerAudienceOrCompany() = runTest {
        val directory = Files.createTempDirectory("leave-owner").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(f.access.sessionId)
            val repository = EncryptedLeaveCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            repository.replace(f.access, null, snapshot)
            for (wrong in
                listOf(
                    f.access.copy(accountId = "other-account"),
                    f.access.copy(audience = "https://another.example.test/"),
                )) {
                assertEquals(
                    FailureKind.Storage,
                    assertIs<AppResult.Failed>(repository.observe(wrong).first()).failure.kind,
                )
            }
            assertEquals(
                AppResult.Success(null),
                repository.observe(f.access.copy(companyId = "other-company")).first(),
            )
            keys.write(null)
            assertIs<AppResult.Failed>(repository.observe(f.access).first())
            assertIs<AppResult.Failed>(
                repository.replace(
                    f.access,
                    snapshot.generation,
                    snapshot.copy(generation = "replacement"),
                )
            )
            assertNull(keys.read())
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aLateEncryptedWriteCannotRecreateThePreviousAccountsPartition() = runTest {
        val directory = Files.createTempDirectory("leave-late-write").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        val cipher = AesGcmContentCipher()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pendingCipher =
            object : ContentCipher by cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    entered.complete(Unit)
                    release.await()
                    return cipher.encrypt(key, associatedData, plaintext)
                }
            }
        try {
            db.accounts.activate(f.access.sessionId)
            val repository = EncryptedLeaveCacheRepository(db.replicas, pendingCipher, keys)
            val pending = async { repository.replace(f.access, null, snapshot) }
            entered.await()
            db.accounts.activate("new-session")
            release.complete(Unit)
            assertEquals(AppResult.Success(false), pending.await())
            assertEquals(AppResult.Success(null), repository.observe(f.access).first())
        } finally {
            release.complete(Unit)
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellationDuringEncryptionCannotPublishEvenIfTheCipherCompletesLate() = runTest {
        val directory = Files.createTempDirectory("leave-cancel").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        val cipher = AesGcmContentCipher()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pendingCipher =
            object : ContentCipher by cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray =
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        cipher.encrypt(key, associatedData, plaintext)
                    }
            }
        try {
            db.accounts.activate(f.access.sessionId)
            val repository = EncryptedLeaveCacheRepository(db.replicas, pendingCipher, keys)
            val pending = async { repository.replace(f.access, null, snapshot) }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            pending.join()
            assertEquals(AppResult.Success(null), repository.observe(f.access).first())
        } finally {
            release.complete(Unit)
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }
}
