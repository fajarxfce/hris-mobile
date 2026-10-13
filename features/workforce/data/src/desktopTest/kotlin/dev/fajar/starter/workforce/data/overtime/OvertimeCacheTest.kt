package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.workforce.data.mappers.toDirectory
import dev.fajar.starter.workforce.data.overtime.encoding.overtimePartition
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class OvertimeCacheTest {
    private val f = OvertimeDataFixture()
    private val overview =
        OvertimeSnapshot(
            f.access,
            "first",
            OvertimeOverview(
                f.base.directory.toDirectory(f.access, null),
                OvertimeSelection(f.base.employee.id, f.base.period),
                f.page.toRequests(f.base.employee.id, f.base.period, null, null),
            ),
            f.base.now,
            f.access.authorizationExpiresAt,
        )
    private val details =
        OvertimeSnapshot(
            f.access,
            "first",
            OvertimeRequestView(f.selection, f.detail.toDetails(f.selection)),
            f.base.now,
            f.access.authorizationExpiresAt,
        )

    @Test
    fun freshFactsPersistIndependentlyOfAnExpiredAuthorizationDeadline() = runTest {
        withDatabase { db, keys ->
            val repo = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), keys)
            val access =
                f.access.copy(
                    authorizationExpiresAt = f.base.now - 1.hours,
                    sessionExpiresAt = f.base.now - 1.hours,
                )
            val savedList = overview.copy(access = access, refreshAfter = f.base.now + 1.hours)
            val savedDetail = details.copy(access = access, refreshAfter = f.base.now + 1.hours)
            assertEquals(AppResult.Success(true), repo.replace(access, null, savedList))
            assertEquals(AppResult.Success(true), repo.replaceRequest(access, null, savedDetail))
            assertEquals(AppResult.Success(savedList), repo.observe(access).first())
            assertEquals(AppResult.Success(savedDetail), repo.observeRequest(access).first())
        }
    }

    @Test
    fun separateReadViewsRestoreEncryptedFactsAndRejectStaleReplacement() = runTest {
        withDatabase { db, keys ->
            val repo = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repo.replace(f.access, null, overview))
            assertEquals(AppResult.Success(true), repo.replaceRequest(f.access, null, details))
            val restored = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(overview), restored.observe(f.access).first())
            assertEquals(AppResult.Success(details), restored.observeRequest(f.access).first())
            val raw =
                db.replicas
                    .observeDocument(
                        ReplicaScope(f.access.sessionId),
                        overtimePartition(f.access, "request"),
                        "request",
                    )
                    .first()
            assertFalse(raw.documents.single().payload!!.contains(f.request.reason))
            assertFalse(raw.documents.single().payload!!.contains("employeeName"))
            assertEquals(
                AppResult.Success(true),
                restored.replace(f.access, "first", overview.copy(generation = "second")),
            )
            assertEquals(AppResult.Success(false), restored.replace(f.access, "first", null))
            assertEquals(AppResult.Success(details), restored.observeRequest(f.access).first())
        }
    }

    @Test
    fun accountAudienceCompanyAndViewAreBoundToTheCiphertext() = runTest {
        withDatabase { db, keys ->
            val repo = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), keys)
            repo.replaceRequest(f.access, null, details)
            for (access in
                listOf(
                    f.access.copy(accountId = "99999999-9999-9999-9999-999999999999"),
                    f.access.copy(audience = "https://other.example.test/"),
                )) {
                assertIs<AppResult.Failed>(repo.observeRequest(access).first())
            }
            assertEquals(
                AppResult.Success(null),
                repo
                    .observeRequest(
                        f.access.copy(companyId = "99999999-9999-9999-9999-999999999999")
                    )
                    .first(),
            )
            val raw =
                db.replicas
                    .observeDocument(
                        ReplicaScope(f.access.sessionId),
                        overtimePartition(f.access, "request"),
                        "request",
                    )
                    .first()
            assertTrue(
                db.replicas.replace(
                    ReplicaScope(f.access.sessionId),
                    overtimePartition(f.access, "overview"),
                    null,
                    ReplicaSnapshot(
                        "first",
                        raw.checkpoint!!.payload,
                        listOf(raw.documents.single().copy(id = "overview", sortKey = "overview")),
                    ),
                )
            )
            assertIs<AppResult.Failed>(repo.observe(f.access).first())
            assertEquals(AppResult.Success(details), repo.observeRequest(f.access).first())
        }
    }

    @Test
    fun aMissingWorkspaceKeyNeverGeneratesAReplacement() = runTest {
        withDatabase { db, keys ->
            val repo = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), keys)
            repo.replace(f.access, null, overview)
            val absent = MemoryCredentialStore()
            try {
                val reader = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), absent)
                assertIs<AppResult.Failed>(reader.observe(f.access).first())
                assertIs<AppResult.Failed>(reader.replaceRequest(f.access, null, details))
                assertNull(absent.read())
                assertEquals(AppResult.Success(overview), repo.observe(f.access).first())
            } finally {
                absent.close()
            }
        }
    }

    @Test
    fun onlyOneCompetingPublicationCanReplaceTheObservedGeneration() = runTest {
        withDatabase { db, keys ->
            val first = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), keys)
            val second = LegacyOvertimeCacheWriter(db.replicas, AesGcmContentCipher(), keys)
            first.replaceRequest(f.access, null, details)
            val results =
                awaitAll(
                    async {
                        first.replaceRequest(f.access, "first", details.copy(generation = "a"))
                    },
                    async {
                        second.replaceRequest(f.access, "first", details.copy(generation = "b"))
                    },
                )
            assertEquals(1, results.count { it == AppResult.Success(true) })
            assertEquals(1, results.count { it == AppResult.Success(false) })
        }
    }

    @Test
    fun cancellationAndAccountReplacementCannotPublishLateEncryption() = runTest {
        for (cancel in listOf(false, true)) withDatabase { db, keys ->
            val cipher = AesGcmContentCipher()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val plaintextBuffers = mutableListOf<ByteArray>()
            val keyBuffers = mutableListOf<ByteArray>()
            val delayed =
                object : ContentCipher by cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        plaintextBuffers += plaintext
                        keyBuffers += key
                        val encrypted = cipher.encrypt(key, associatedData, plaintext)
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return encrypted
                    }
                }
            val repo = LegacyOvertimeCacheWriter(db.replicas, delayed, keys)
            val running = async { repo.replaceRequest(f.access, null, details) }
            entered.await()
            if (cancel) running.cancel() else db.accounts.activate("replacement")
            release.complete(Unit)
            if (cancel) assertFailsWith<CancellationException> { running.await() }
            else assertEquals(AppResult.Success(false), running.await())
            assertTrue(plaintextBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
            assertTrue(keyBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
            assertEquals(
                AppResult.Success(null),
                LegacyOvertimeCacheWriter(db.replicas, cipher, keys)
                    .observeRequest(f.access)
                    .first(),
            )
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase, MemoryCredentialStore) -> Unit) {
        val directory = Files.createTempDirectory("overtime-cache").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(f.access.sessionId)
            val key = AesGcmContentCipher().generateKey()
            try {
                keys.write(Base64.encode(key))
            } finally {
                key.fill(0)
            }
            block(db, keys)
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }
}
