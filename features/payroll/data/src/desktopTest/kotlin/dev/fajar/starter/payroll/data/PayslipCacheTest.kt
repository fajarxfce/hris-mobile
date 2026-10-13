package dev.fajar.starter.payroll.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.encoding.payslipPartition
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.data.repositories.EncryptedPayslipCacheRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class PayslipCacheTest {
    private val f = PayslipDataFixture()
    private val overview =
        PayslipSnapshot(
            f.access,
            "first",
            PayslipOverview(PayslipSelection(2026), f.page.toPage(PayslipSelection(2026))),
            f.now,
            f.access.authorizationExpiresAt,
        )
    private val details =
        PayslipSnapshot(
            f.access,
            "first",
            PayslipView(f.detail.toPayslip(f.summary.id), f.payments.toPayments(f.summary.id)),
            f.now,
            f.access.authorizationExpiresAt,
        )

    @Test
    fun freshFactsPersistIndependentlyOfAnExpiredAuthorizationDeadline() = runTest {
        withDatabase { db, keys ->
            val repo = EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            val access =
                f.access.copy(
                    authorizationExpiresAt = f.now - 1.hours,
                    sessionExpiresAt = f.now - 1.hours,
                )
            val savedList = overview.copy(access = access, refreshAfter = f.now + 1.hours)
            val savedDetail = details.copy(access = access, refreshAfter = f.now + 1.hours)
            assertEquals(AppResult.Success(true), repo.replace(access, null, savedList))
            assertEquals(AppResult.Success(true), repo.replaceDetails(access, null, savedDetail))
            assertEquals(AppResult.Success(savedList), repo.observe(access).first())
            assertEquals(AppResult.Success(savedDetail), repo.observeDetails(access).first())
        }
    }

    @Test
    fun encryptedViewsRestoreExactFinancialFactsIndependently() = runTest {
        withDatabase { db, keys ->
            val repo = EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repo.replace(f.access, null, overview))
            assertEquals(AppResult.Success(true), repo.replaceDetails(f.access, null, details))
            val restored = EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(overview), restored.observe(f.access).first())
            assertEquals(AppResult.Success(details), restored.observeDetails(f.access).first())
            val raw =
                db.replicas
                    .observeDocument(
                        ReplicaScope(f.access.sessionId),
                        payslipPartition(f.access, "details"),
                        "details",
                    )
                    .first()
            assertFalse(raw.documents.single().payload!!.contains(f.summary.employeeName))
            assertFalse(raw.documents.single().payload!!.contains("taxableGross"))
            assertFalse(raw.documents.single().payload!!.contains(f.summary.takeHome))
            assertEquals(AppResult.Success(true), restored.replace(f.access, "first", null))
            assertEquals(AppResult.Success(null), restored.observe(f.access).first())
            assertEquals(AppResult.Success(details), restored.observeDetails(f.access).first())
        }
    }

    @Test
    fun ciphertextBindsAccountAudienceCompanyAndViewOwnership() = runTest {
        withDatabase { db, keys ->
            val repo = EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            repo.replaceDetails(f.access, null, details)
            for (access in
                listOf(
                    f.access.copy(accountId = "99999999-9999-9999-9999-999999999999"),
                    f.access.copy(audience = "https://other.example.test/"),
                )) {
                assertIs<AppResult.Failed>(repo.observeDetails(access).first())
            }
            assertEquals(
                AppResult.Success(null),
                repo
                    .observeDetails(
                        f.access.copy(companyId = "99999999-9999-9999-9999-999999999999")
                    )
                    .first(),
            )
            assertEquals(AppResult.Success(null), repo.observe(f.access).first())
            db.accounts.activate("replacement")
            assertEquals(AppResult.Success(null), repo.observeDetails(f.access).first())
        }
    }

    @Test
    fun aMissingKeyNeverReplacesTheWorkspaceKeyOrExistingCiphertext() = runTest {
        withDatabase { db, keys ->
            val repo = EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            repo.replace(f.access, null, overview)
            val absent = MemoryCredentialStore()
            try {
                val other =
                    EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), absent)
                assertIs<AppResult.Failed>(other.observe(f.access).first())
                assertIs<AppResult.Failed>(other.replaceDetails(f.access, null, details))
                assertNull(absent.read())
                assertEquals(AppResult.Success(overview), repo.observe(f.access).first())
            } finally {
                absent.close()
            }
        }
    }

    @Test
    fun onlyOneConcurrentPublicationCanReplaceTheObservedGeneration() = runTest {
        withDatabase { db, keys ->
            val first = EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            val second = EncryptedPayslipCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            first.replaceDetails(f.access, null, details)
            val results =
                awaitAll(
                    async {
                        first.replaceDetails(f.access, "first", details.copy(generation = "a"))
                    },
                    async {
                        second.replaceDetails(f.access, "first", details.copy(generation = "b"))
                    },
                )
            assertEquals(1, results.count { it == AppResult.Success(true) })
            assertEquals(1, results.count { it == AppResult.Success(false) })
            assertEquals(AppResult.Success(false), first.replaceDetails(f.access, "first", null))
        }
    }

    @Test
    fun cancellationAndAccountReplacementRejectLateEncryptionAndEraseBuffers() = runTest {
        for (cancel in listOf(false, true)) withDatabase { db, keys ->
            val cipher = AesGcmContentCipher()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val plaintext = mutableListOf<ByteArray>()
            val secrets = mutableListOf<ByteArray>()
            val delayed =
                object : ContentCipher by cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        bytes: ByteArray,
                    ): ByteArray {
                        plaintext += bytes
                        secrets += key
                        val encoded = cipher.encrypt(key, associatedData, bytes)
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return encoded
                    }
                }
            val repo = EncryptedPayslipCacheRepository(db.replicas, delayed, keys)
            val request = async { repo.replaceDetails(f.access, null, details) }
            entered.await()
            if (cancel) request.cancel() else db.accounts.activate("replacement")
            release.complete(Unit)
            if (cancel) assertFailsWith<CancellationException> { request.await() }
            else assertEquals(AppResult.Success(false), request.await())
            assertTrue(plaintext.all { bytes -> bytes.all { it == 0.toByte() } })
            assertTrue(secrets.all { bytes -> bytes.all { it == 0.toByte() } })
            assertEquals(
                AppResult.Success(null),
                EncryptedPayslipCacheRepository(db.replicas, cipher, keys)
                    .observeDetails(f.access)
                    .first(),
            )
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase, MemoryCredentialStore) -> Unit) {
        val directory = Files.createTempDirectory("payslip-cache").toFile()
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
