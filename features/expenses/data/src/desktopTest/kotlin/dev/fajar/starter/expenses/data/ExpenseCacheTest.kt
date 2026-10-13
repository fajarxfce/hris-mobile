package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.encoding.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.repositories.EncryptedExpenseCacheRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ExpenseCacheTest {
    private val f = ExpenseDataFixture()
    private val selection = ExpenseSelection(f.employee.id, kotlinx.datetime.YearMonth(2026, 10))
    private val overview =
        ExpenseSnapshot(
            f.access,
            "first",
            ExpenseOverview(
                f.directory.toDirectory(f.access, null),
                selection,
                f.page.toClaims(selection),
            ),
            f.now,
            f.access.readUntil,
        )
    private val details =
        ExpenseSnapshot(
            f.access,
            "first",
            ExpenseView(
                f.claim.toClaim(f.claim.id),
                null,
                f.history.toHistory(null),
                null,
                emptyList(),
            ),
            f.now,
            f.access.readUntil,
        )

    @Test
    fun largeViewsStayInsideDatabaseRecordLimitsAndRejectIncompleteOrReorderedChunks() = runTest {
        withDatabase { db, keys ->
            val cipher = AesGcmContentCipher()
            val key = Base64.decode(requireNotNull(keys.read()))
            val bytes = ByteArray(EXPENSE_CACHE_MAXIMUM_BYTES) { (it % 251).toByte() }
            try {
                val encoded = encodeExpenseCache(bytes, f.access, "large", "details", cipher, key)
                assertEquals(EXPENSE_CACHE_MAXIMUM_CHUNKS, encoded.documents.size)
                assertTrue(encoded.documents.all { it.payload!!.length <= 131_072 })
                assertTrue(
                    db.replicas.replace(
                        f.access.sessionId,
                        expensePartition(f.access, "details"),
                        null,
                        encoded,
                    )
                )
                val page =
                    db.replicas
                        .observe(
                            f.access.sessionId,
                            expensePartition(f.access, "details"),
                            limit = 17,
                        )
                        .first()
                val restored = decodeExpenseCache(page, f.access, "details", cipher, key)
                try {
                    assertContentEquals(bytes, restored)
                } finally {
                    restored.fill(0)
                }
                for (altered in
                    listOf(
                        page.copy(documents = page.documents.dropLast(1)),
                        page.copy(documents = page.documents.reversed()),
                        page.copy(documents = page.documents.dropLast(1) + page.documents.first()),
                    )) assertFails { decodeExpenseCache(altered, f.access, "details", cipher, key) }
                assertFails { decodeExpenseCache(page, f.access, "overview", cipher, key) }
            } finally {
                key.fill(0)
                bytes.fill(0)
            }
        }
    }

    @Test
    fun encryptedViewsRestoreExactFinancialFactsIndependently() = runTest {
        withDatabase { db, keys ->
            val repo = EncryptedExpenseCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repo.replace(f.access, null, overview))
            assertEquals(AppResult.Success(true), repo.replaceDetails(f.access, null, details))
            val restored = EncryptedExpenseCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(overview), restored.observe(f.access).first())
            assertEquals(AppResult.Success(details), restored.observeDetails(f.access).first())
            val raw =
                db.replicas
                    .observe(f.access.sessionId, expensePartition(f.access, "details"), limit = 17)
                    .first()
            assertFalse(raw.documents.single().payload!!.contains(f.summary.employeeName))
            assertFalse(raw.documents.single().payload!!.contains("totalAmount"))
            assertFalse(raw.documents.single().payload!!.contains(f.summary.totalAmount))
            assertEquals(AppResult.Success(true), restored.replace(f.access, "first", null))
            assertEquals(AppResult.Success(null), restored.observe(f.access).first())
            assertEquals(AppResult.Success(details), restored.observeDetails(f.access).first())
        }
    }

    @Test
    fun ciphertextBindsAccountAudienceCompanyAndViewOwnership() = runTest {
        withDatabase { db, keys ->
            val repo = EncryptedExpenseCacheRepository(db.replicas, AesGcmContentCipher(), keys)
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
            val repo = EncryptedExpenseCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            repo.replace(f.access, null, overview)
            val absent = MemoryCredentialStore()
            try {
                val other =
                    EncryptedExpenseCacheRepository(db.replicas, AesGcmContentCipher(), absent)
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
            val first = EncryptedExpenseCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            val second = EncryptedExpenseCacheRepository(db.replicas, AesGcmContentCipher(), keys)
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
            val repo = EncryptedExpenseCacheRepository(db.replicas, delayed, keys)
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
                EncryptedExpenseCacheRepository(db.replicas, cipher, keys)
                    .observeDetails(f.access)
                    .first(),
            )
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase, MemoryCredentialStore) -> Unit) {
        val directory = Files.createTempDirectory("expense-cache").toFile()
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
