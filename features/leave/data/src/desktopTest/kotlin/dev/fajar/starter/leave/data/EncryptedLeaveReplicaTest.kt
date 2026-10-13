package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.EncryptedLeaveReplicaRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.initialLeaveCheckpoint
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EncryptedLeaveReplicaTest {
    @Test
    fun indexedQueriesReadEverySavedYearAndStatusWithIndependentPages() = runTest {
        withReplica { f ->
            val requests =
                (1..25).map { index ->
                    val request =
                        f.request.value.withFacts(
                            id = id(index),
                            submittedAt = f.raw.now + index.seconds,
                            status =
                                if (index % 2 == 0) LeaveStatus.APPROVED else LeaveStatus.PENDING,
                        )
                    entry(LeaveReplicaRecord.Request(request))
                }
            val balances =
                (1..25).map { index ->
                    entry(
                        f.balance.copy(
                            value =
                                f.balance.value.copy(
                                    accountId = id(100 + index),
                                    typeId = id(200 + index),
                                    typeCode = "TYPE_$index",
                                )
                        )
                    )
                }
            val historic =
                entry(
                    f.balance.copy(value = f.balance.value.copy(accountId = id(400), year = 2025))
                )
            f.seed(requests.take(20) + balances.take(20), publish = false)
            assertNull(
                assertIs<AppResult.Success<LeaveReplicaDirectory>>(
                        f.repository.observeDirectory(f.access).first()
                    )
                    .value
                    .checkpoint
            )
            f.commit(requests.drop(20) + balances.drop(20) + historic)
            val first = f.page()
            assertEquals(20, first.requests.items.size)
            assertEquals(20, first.balances.items.size)
            val second =
                f.page(
                    f.selection.copy(
                        requestAfter = first.requests.nextCursor,
                        balanceAfter = first.balances.nextCursor,
                    )
                )
            assertEquals(5, second.requests.items.size)
            assertEquals(5, second.balances.items.size)
            assertNull(second.requests.nextCursor)
            val selected = f.page(f.selection.copy(year = 2025, status = LeaveStatus.APPROVED))
            assertEquals(1, selected.balances.items.size)
            assertEquals(12, selected.requests.items.size)
            assertTrue(selected.requests.items.all { it.status == LeaveStatus.APPROVED })
            assertEquals(first.checkpoint, selected.checkpoint)
            val raw =
                f.db.replicas
                    .observe(
                        leaveReplicaScope(f.access),
                        leaveReplicaPartition(f.access),
                        limit = 100,
                    )
                    .first()
            assertTrue(raw.documents.all { it.payload?.contains("Annual leave") != true })
            val tags = raw.documents.flatMap { it.tags }
            assertTrue(tags.isNotEmpty() && tags.all { Regex("[a-f0-9]{64}").matches(it) })
        }
    }

    @Test
    fun statusUpdatesRemoveOldIndexEntriesAndTombstonesPreventOlderResurrection() = runTest {
        withReplica { f ->
            f.seed(listOf(entry(f.request), entry(f.balance)))
            val approved =
                f.request.copy(
                    value = f.request.value.withFacts(version = 2, status = LeaveStatus.APPROVED)
                )
            f.commit(listOf(entry(approved)))
            assertTrue(
                f.page(f.selection.copy(status = LeaveStatus.PENDING)).requests.items.isEmpty()
            )
            assertEquals(
                approved.value.toSummary(),
                f.page(f.selection.copy(status = LeaveStatus.APPROVED)).requests.items.single(),
            )
            f.commit(
                listOf(
                    LeaveReplicaEntry(
                        SyncReference(SyncCollection.LEAVE_REQUESTS, approved.value.id, 2),
                        null,
                    )
                )
            )
            f.commit(listOf(entry(approved)))
            f.commit(listOf(entry(f.request)))
            assertTrue(f.page().requests.items.isEmpty())
            val raw =
                f.db.replicas.document(
                    leaveReplicaScope(f.access),
                    leaveReplicaPartition(f.access),
                    "request/${approved.value.id}",
                )
            assertNull(raw)
            assertTrue(
                f.page(f.selection.copy(status = LeaveStatus.APPROVED)).requests.items.isEmpty()
            )
        }
    }

    @Test
    fun freshLoginAndStagedReplacementKeepOwnedFactsWhileOldSessionsAndOtherOwnersCannotReadOrWrite() =
        runTest {
            withReplica { f ->
                f.seed(listOf(entry(f.request), entry(f.balance)))
                val original = f.page()
                f.db.accounts.activate(null)
                assertNull(
                    assertIs<AppResult.Success<LeaveReplicaDirectory>>(
                            f.repository.observeDirectory(f.access).first()
                        )
                        .value
                        .checkpoint
                )
                val renewed = f.access.copy(sessionId = "new-login")
                f.db.accounts.activate(renewed.sessionId, leaveReplicaScope(renewed).accountKey)
                assertEquals(
                    original,
                    assertIs<AppResult.Success<LeaveReplicaPage>>(
                            f.repository.observe(renewed, f.selection).first()
                        )
                        .value,
                )
                assertEquals(
                    AppResult.Success(false),
                    f.repository.commit(
                        f.current,
                        f.current.copy(revision = 2),
                        f.directory,
                        emptyList(),
                    ),
                )
                assertEquals(AppResult.Success(false), f.repository.clear(f.access))
                val replacement = initialLeaveCheckpoint(renewed, "replacement", f.raw.now)
                assertEquals(
                    AppResult.Success(true),
                    f.repository.begin(replacement, f.current.generation),
                )
                assertEquals(
                    original,
                    assertIs<AppResult.Success<LeaveReplicaPage>>(
                            f.repository.observe(renewed, f.selection).first()
                        )
                        .value,
                )
                val other = renewed.copy(accountId = id(500))
                f.db.accounts.activate(other.sessionId, leaveReplicaScope(other).accountKey)
                assertNull(
                    assertIs<AppResult.Success<LeaveReplicaDirectory>>(
                            f.repository.observeDirectory(other).first()
                        )
                        .value
                        .checkpoint
                )
            }
        }

    @Test
    fun reopeningTheDatabaseKeepsThePublishedAccountGenerationAndItsFilterIndexes() = runTest {
        val directory = Files.createTempDirectory("leave-replica-reopen").toFile()
        val keys = MemoryCredentialStore()
        var db = createAppDatabase(directory)
        try {
            val f = Fixture(db, keys)
            db.accounts.activate(f.access.sessionId, leaveReplicaScope(f.access).accountKey)
            f.seed(listOf(entry(f.request), entry(f.balance)))
            val expected = f.page()
            db.close()
            db = createAppDatabase(directory)
            val restored =
                EncryptedLeaveReplicaRepository(db.replicas, AesGcmContentCipher(), keys, keys)
            assertEquals(
                expected,
                assertIs<AppResult.Success<LeaveReplicaPage>>(
                        restored.observe(f.access, f.selection).first()
                    )
                    .value,
            )
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun ciphertextAuthenticatesIndexTagsAndOrderingEvenWhenRawRowsAreReinserted() = runTest {
        for (tamperTags in listOf(true, false)) withReplica { f ->
            f.seed(listOf(entry(f.request), entry(f.balance)))
            val scope = leaveReplicaScope(f.access)
            val partition = leaveReplicaPartition(f.access)
            val original = f.db.replicas.observe(scope, partition).first()
            val documents =
                original.documents.map {
                    if (!it.id.startsWith("request/")) it
                    else if (tamperTags) it.copy(tags = it.tags + "tampered")
                    else it.copy(sortKey = "r/0000000000000000000/${f.request.value.id}")
                }
            assertTrue(f.db.replicas.clear(scope, partition))
            assertTrue(f.db.replicas.begin(scope, partition, f.current.generation, "initial", null))
            assertTrue(
                f.db.replicas.commit(
                    ReplicaHandle(scope, partition, f.current.generation),
                    0,
                    original.checkpoint!!.payload,
                    documents,
                    true,
                )
            )
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(f.repository.observe(f.access, f.selection).first())
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun lostKeysCannotBeReplacedWhileAnotherAccountHasRetainedRecords() = runTest {
        withReplica { f ->
            f.seed(listOf(entry(f.request)))
            f.keys.write(null)
            val other = f.access.copy(sessionId = "other-login", accountId = id(600))
            f.db.accounts.activate(other.sessionId, leaveReplicaScope(other).accountKey)
            val initial = initialLeaveCheckpoint(other, "other", f.raw.now)
            assertIs<AppResult.Failed>(f.repository.begin(initial, null))
            assertNull(f.keys.read())
        }
    }

    @Test
    fun encryptionFailureAndCancelledLateEncryptionLeaveContentAndCheckpointUnchanged() = runTest {
        for (cancelled in listOf(false, true)) withReplica { f ->
            f.seed(listOf(entry(f.request), entry(f.balance)))
            val saved = f.page()
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val real = AesGcmContentCipher()
            val cipher =
                object : ContentCipher by real {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        entered.complete(Unit)
                        if (!cancelled) error("Owned encryption probe")
                        return withContext(NonCancellable) {
                            gate.await()
                            real.encrypt(key, associatedData, plaintext)
                        }
                    }
                }
            val repository = EncryptedLeaveReplicaRepository(f.db.replicas, cipher, f.keys, f.keys)
            val writing = async {
                repository.commit(
                    f.current,
                    f.current.copy(revision = 2),
                    f.directory,
                    listOf(entry(f.request.copy(value = f.request.value.withFacts(version = 1)))),
                )
            }
            entered.await()
            if (cancelled) {
                writing.cancel()
                gate.complete(Unit)
                writing.join()
                assertTrue(writing.isCancelled)
            } else assertIs<AppResult.Failed>(writing.await())
            assertEquals(saved, f.page())
            assertEquals(AppResult.Success(f.current), f.repository.checkpoint(f.access))
        }
    }

    private suspend fun withReplica(block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("leave-replica").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            val fixture = Fixture(db, keys)
            db.accounts.activate(
                fixture.access.sessionId,
                leaveReplicaScope(fixture.access).accountKey,
            )
            block(fixture)
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    private class Fixture(val db: AppDatabase, val keys: MemoryCredentialStore) {
        val raw = LeaveDataFixture()
        val access = raw.access
        val selection = LeaveSelection(raw.employee.id, 2026)
        val directory = raw.directory.toDirectory(access, null)
        val request = raw.detail.toReplicaRequest(raw.request.id)
        val balance =
            LeaveReplicaRecord.Balance(
                raw.employee.id,
                raw.balances.toBalances(raw.employee.id, 2026, null).items.single(),
            )
        val repository =
            EncryptedLeaveReplicaRepository(db.replicas, AesGcmContentCipher(), keys, keys)
        var current = initialLeaveCheckpoint(access, "generation", raw.now)

        suspend fun seed(entries: List<LeaveReplicaEntry>, publish: Boolean = true) {
            assertEquals(AppResult.Success(true), repository.begin(current, null))
            commit(entries, publish)
        }

        suspend fun commit(entries: List<LeaveReplicaEntry>, publish: Boolean = true) {
            val batches = entries.chunked(8).ifEmpty { listOf(emptyList()) }
            for ((index, batch) in batches.withIndex()) {
                val next =
                    current.copy(
                        revision = current.revision + 1,
                        phase = LeaveSyncPhase.CHANGES,
                        cursor = "cursor",
                        published = current.published || (publish && index == batches.lastIndex),
                        refreshedAt =
                            if (publish && index == batches.lastIndex) raw.now
                            else current.refreshedAt,
                    )
                assertEquals(
                    AppResult.Success(true),
                    repository.commit(current, next, directory, batch),
                )
                current = next
            }
        }

        suspend fun page(selection: LeaveSelection = this.selection) =
            assertIs<AppResult.Success<LeaveReplicaPage>>(
                    repository.observe(access, selection).first()
                )
                .value
    }
}

private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

private fun entry(record: LeaveReplicaRecord) =
    LeaveReplicaEntry(
        when (record) {
            is LeaveReplicaRecord.Request ->
                SyncReference(SyncCollection.LEAVE_REQUESTS, record.value.id, record.value.version)
            is LeaveReplicaRecord.Balance ->
                SyncReference(
                    SyncCollection.LEAVE_BALANCES,
                    record.value.accountId,
                    record.value.version,
                )
        },
        record,
        LeaveDataFixture().now,
    )

private fun LeaveRequestDetails.withFacts(
    id: String = this.id,
    version: Long = this.version,
    status: LeaveStatus = this.status,
    submittedAt: kotlin.time.Instant = this.submittedAt,
): LeaveRequestDetails =
    copy(
        id = id,
        version = version,
        status = status,
        submittedAt = submittedAt,
        history =
            LeavePage(
                listOf(
                    history.items
                        .first()
                        .copy(version = version, status = status, recordedAt = submittedAt)
                )
            ),
    )
