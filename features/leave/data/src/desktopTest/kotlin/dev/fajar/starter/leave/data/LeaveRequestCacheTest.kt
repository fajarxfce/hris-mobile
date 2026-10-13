package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.EncryptedLeaveCacheRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

class LeaveRequestCacheTest {
    private val f = LeaveDataFixture()
    private val snapshot =
        LeaveRequestSnapshot(
            f.access,
            "request-generation",
            LeaveRequestSelection(f.request.id),
            f.detail.toDetails(LeaveRequestSelection(f.request.id)),
            f.now,
            f.access.authorizationExpiresAt,
        )

    @Test
    fun completeDenseRequestsRestoreFromSqliteWithoutExposingDatesReasonsOrAttachmentNames() =
        runTest {
            val folder = Files.createTempDirectory("leave-request-cache").toFile()
            val db = createAppDatabase(folder)
            val keys = MemoryCredentialStore()
            try {
                db.accounts.activate(f.access.sessionId)
                val repository =
                    EncryptedLeaveCacheRepository(db.replicas, AesGcmContentCipher(), keys)
                val days =
                    (0..365).map { index ->
                        val date = LocalDate(2026, 1, 1).plus(index, DateTimeUnit.DAY).toString()
                        f.detail.days
                            .first()
                            .copy(
                                workDate = date,
                                startsAt = "${date}T01:00:00Z",
                                endsAt = "${date}T10:00:00Z",
                            )
                    }
                val dto =
                    f.detail.copy(
                        days = days,
                        chargedDays = "366",
                        version = 9,
                        reason = "字".repeat(1000),
                        history =
                            LeavePageDto(
                                (9L downTo 0).map {
                                    LeaveChangeDto(
                                        it,
                                        "DECIDED",
                                        "PENDING",
                                        f.now.toString(),
                                        "字".repeat(1000),
                                    )
                                }
                            ),
                    )
                val dense = snapshot.copy(request = dto.toDetails(snapshot.selection))
                assertTrue(Json.encodeToString(dense.toDto()).encodeToByteArray().size > 96_000)
                assertEquals(
                    AppResult.Success(true),
                    repository.replaceRequest(f.access, null, dense),
                )
                assertEquals(AppResult.Success(dense), repository.observeRequest(f.access).first())
                val raw =
                    db.replicas
                        .observe(f.access.sessionId, leaveRequestPartition(f.access), limit = 32)
                        .first()
                assertEquals(23, raw.documents.size)
                assertTrue(raw.documents.all { it.id == it.sortKey && !it.id.contains("2026") })
                for (document in raw.documents) {
                    val payload = requireNotNull(document.payload)
                    assertFalse(payload.contains("note.pdf"))
                    assertFalse(payload.contains("workDate"))
                    assertFalse(payload.contains(f.request.id))
                }
                val restored =
                    EncryptedLeaveCacheRepository(db.replicas, AesGcmContentCipher(), keys)
                assertEquals(AppResult.Success(dense), restored.observeRequest(f.access).first())
                val next = dense.copy(generation = "new-request")
                assertEquals(
                    AppResult.Success(true),
                    repository.replaceRequest(f.access, dense.generation, next),
                )
                assertEquals(
                    AppResult.Success(false),
                    repository.replaceRequest(f.access, dense.generation, null),
                )
                assertEquals(AppResult.Success(next), repository.observeRequest(f.access).first())
            } finally {
                db.close()
                keys.close()
                folder.deleteRecursively()
            }
        }

    @Test
    fun requestAndOverviewPublicationStaySeparateAndShareOneRetainedKey() = runTest {
        val folder = Files.createTempDirectory("leave-request-overview").toFile()
        val db = createAppDatabase(folder)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(f.access.sessionId)
            val repository = EncryptedLeaveCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            val overview =
                LeaveOverview(
                    f.access,
                    "overview",
                    f.directory.toDirectory(f.access, null),
                    LeaveSelection(f.employee.id, 2026),
                    f.balances.toBalances(f.employee.id, 2026, null),
                    f.requests.toRequests(f.employee.id, null, null),
                    f.now,
                    f.access.authorizationExpiresAt,
                )
            coroutineScope {
                val detailWrite = async { repository.replaceRequest(f.access, null, snapshot) }
                val overviewWrite = async { repository.replace(f.access, null, overview) }
                assertEquals(AppResult.Success(true), detailWrite.await())
                assertEquals(AppResult.Success(true), overviewWrite.await())
            }
            assertEquals(AppResult.Success(snapshot), repository.observeRequest(f.access).first())
            assertEquals(AppResult.Success(overview), repository.observe(f.access).first())
            repository.replace(f.access, overview.generation, null)
            assertEquals(AppResult.Success(snapshot), repository.observeRequest(f.access).first())
            keys.write(null)
            assertIs<AppResult.Failed>(repository.replace(f.access, null, overview))
            assertNull(keys.read())
            assertIs<AppResult.Failed>(repository.observeRequest(f.access).first())
        } finally {
            db.close()
            keys.close()
            folder.deleteRecursively()
        }
    }

    @Test
    fun missingRecordsAndCrossOwnerCiphertextCannotBecomePartialSuccess() = runTest {
        val folder = Files.createTempDirectory("leave-request-integrity").toFile()
        val db = createAppDatabase(folder)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(f.access.sessionId)
            val repository = EncryptedLeaveCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            repository.replaceRequest(f.access, null, snapshot)
            for (wrong in
                listOf(
                    f.access.copy(accountId = f.employee.id),
                    f.access.copy(audience = "https://another.example.test/"),
                )) assertIs<AppResult.Failed>(repository.observeRequest(wrong).first())
            assertEquals(
                AppResult.Success(null),
                repository.observeRequest(f.access.copy(companyId = f.employee.id)).first(),
            )
            val raw =
                db.replicas
                    .observe(f.access.sessionId, leaveRequestPartition(f.access), limit = 32)
                    .first()
            val incomplete =
                object : ReplicaStore by db.replicas {
                    override fun observe(
                        sessionId: String,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> =
                        flowOf(raw.copy(documents = raw.documents.filter { it.id != "day/00" }))
                }
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(
                        EncryptedLeaveCacheRepository(incomplete, AesGcmContentCipher(), keys)
                            .observeRequest(f.access)
                            .first()
                    )
                    .failure
                    .kind,
            )
        } finally {
            db.close()
            keys.close()
            folder.deleteRecursively()
        }
    }

    @Test
    fun cancellationAndAccountReplacementCannotPublishACompletedLateCipher() = runTest {
        for (replaceAccount in listOf(false, true)) {
            val folder = Files.createTempDirectory("leave-request-late").toFile()
            val db = createAppDatabase(folder)
            val keys = MemoryCredentialStore()
            val cipher = AesGcmContentCipher()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val buffers = mutableListOf<ByteArray>()
            val pendingCipher =
                object : ContentCipher by cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray =
                        withContext(NonCancellable) {
                            buffers += key
                            buffers += plaintext
                            entered.complete(Unit)
                            release.await()
                            cipher.encrypt(key, associatedData, plaintext)
                        }
                }
            try {
                db.accounts.activate(f.access.sessionId)
                val repository = EncryptedLeaveCacheRepository(db.replicas, pendingCipher, keys)
                val pending = async { repository.replaceRequest(f.access, null, snapshot) }
                entered.await()
                if (replaceAccount) db.accounts.activate("replacement-session")
                else pending.cancel()
                release.complete(Unit)
                if (replaceAccount) assertEquals(AppResult.Success(false), pending.await())
                else pending.join()
                assertEquals(AppResult.Success(null), repository.observeRequest(f.access).first())
                assertTrue(
                    buffers.isNotEmpty() &&
                        buffers.all { buffer -> buffer.all { it == 0.toByte() } }
                )
            } finally {
                release.complete(Unit)
                db.close()
                keys.close()
                folder.deleteRecursively()
            }
        }
    }
}
