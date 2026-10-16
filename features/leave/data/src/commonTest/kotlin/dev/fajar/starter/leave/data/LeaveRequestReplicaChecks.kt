package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.datasources.MemoryLeaveReadAdmissionDataSource
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.EncryptedLeaveReplicaRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.validateLeaveActionSnapshot
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

suspend fun checkIndependentRequestsBeforeAndAfterFeed(f: LeaveRequestReplicaFixture) {
    val first = f.snapshot(f.raw.requestFacts())
    val second = f.snapshot(f.raw.requestFacts(replicaRequestId(2)))
    f.fresh(first)
    f.fresh(second)
    assertEquals(first, f.request(first.request.id))
    assertEquals(second, f.request(second.request.id))
    assertNull(
        assertIs<AppResult.Success<LeaveReplicaDirectory>>(
                f.repository.observeDirectory(f.access).first()
            )
            .value
            .checkpoint
    )
    assertNull(f.checkpoint()?.cursor)
    assertNull(f.checkpoint()?.refreshedAt)
    f.publish(listOf(first.request, second.request))
    assertEquals(first.request, f.request(first.request.id)?.request)
    assertEquals(second.request, f.request(second.request.id)?.request)
    assertNotNull(
        assertIs<AppResult.Success<LeaveReplicaDirectory>>(
                f.repository.observeDirectory(f.access).first()
            )
            .value
            .checkpoint
    )
}

suspend fun checkDenseAtomicRequestBatch(f: LeaveRequestReplicaFixture) {
    val requests = (1..8).map { f.raw.requestFacts(replicaRequestId(it), 21, dense = true) }
    assertTrue(Json.encodeToString(requests.first().toDto()).encodeToByteArray().size > 96_000)
    f.publish(requests)
    for (request in requests) assertEquals(request, f.request(request.id)?.request)
    val page =
        f.db.replicas
            .observe(leaveReplicaScope(f.access), leaveReplicaPartition(f.access), limit = 100)
            .first()
    assertEquals(8 * 6 + 1, page.documents.size)
    assertTrue(
        page.documents.all { requireNotNull(it.payload).encodeToByteArray().size <= 128 * 1024 }
    )
    assertTrue(
        page.documents.sumOf { requireNotNull(it.payload).encodeToByteArray().size } <=
            4 * 1024 * 1024
    )
    assertTrue(page.documents.all { !requireNotNull(it.payload).contains("Family appointment") })
}

suspend fun checkHistoryProgressPreservesCurrentFactsAndActionLease(f: LeaveRequestReplicaFixture) {
    val request = f.raw.requestFacts(version = 21)
    f.publish(listOf(request))
    val original = requireNotNull(f.request())
    val checkpoint = requireNotNull(f.checkpoint())
    val work =
        assertIs<AppResult.Success<List<LeaveHistoryWork>>>(f.repository.pendingHistory(f.access))
            .value
            .single()
    assertEquals(12L, work.after)
    val page =
        LeaveHistoryPage(
            request.id,
            request.version,
            work.after,
            f.raw.historyPage(request.version, work.after),
        )
    assertEquals(AppResult.Success(true), f.repository.storeHistory(f.access, work, page))
    val stored = requireNotNull(f.request(after = work.after))
    assertEquals(original.request.copy(history = page.history), stored.request)
    assertEquals(original.fetchedAt, stored.fetchedAt)
    assertEquals(original.refreshAfter, stored.refreshAfter)
    assertEquals(original.generation, stored.generation)
    assertEquals(original.access, stored.access)
    val nextCheckpoint = requireNotNull(f.checkpoint())
    assertEquals(checkpoint.cursor, nextCheckpoint.cursor)
    assertEquals(checkpoint.refreshedAt, nextCheckpoint.refreshedAt)
    assertEquals(AppResult.Success(false), f.repository.storeHistory(f.access, work, page))
    assertEquals(stored, f.request(after = work.after))
    val next =
        assertIs<AppResult.Success<List<LeaveHistoryWork>>>(f.repository.pendingHistory(f.access))
            .value
            .single()
    assertEquals(2L, next.after)
    assertEquals(
        AppResult.Success(true),
        f.repository.storeHistory(
            f.access,
            next,
            LeaveHistoryPage(
                request.id,
                request.version,
                next.after,
                f.raw.historyPage(request.version, next.after),
            ),
        ),
    )
    assertTrue(
        assertIs<AppResult.Success<List<LeaveHistoryWork>>>(f.repository.pendingHistory(f.access))
            .value
            .isEmpty()
    )
    val renewed = f.access.copy(authorizationExpiresAt = f.raw.now + 4.days)
    assertIs<AppResult.Failed>(
        validateLeaveActionSnapshot(renewed, requireNotNull(f.request()), f.raw.now + 2.hours)
    )
    val updated = f.raw.requestFacts(version = 22)
    f.publish(listOf(updated))
    val historical = requireNotNull(f.request(after = work.after))
    assertEquals(22L, historical.request.version)
    assertEquals(page.history, historical.request.history)
    assertEquals(updated.reason, historical.request.reason)
}

suspend fun checkOldChunksAndDeletedParentsCannotResurrectRequests(f: LeaveRequestReplicaFixture) {
    val older = f.raw.requestFacts(version = 21, dense = true)
    val current = f.raw.requestFacts(version = 22)
    f.publish(listOf(current))
    f.publish(listOf(older))
    assertEquals(current, f.request()?.request)
    val previous = requireNotNull(f.checkpoint())
    val next = previous.copy(revision = previous.revision + 1)
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            next,
            f.raw.directory.toDirectory(f.access, null),
            listOf(
                LeaveReplicaEntry(
                    SyncReference(SyncCollection.LEAVE_REQUESTS, current.id, current.version),
                    null,
                )
            ),
        ),
    )
    assertNull(f.request())
    assertTrue(
        assertIs<AppResult.Success<List<LeaveHistoryWork>>>(f.repository.pendingHistory(f.access))
            .value
            .isEmpty()
    )
    f.publish(listOf(current))
    f.publish(listOf(older))
    assertNull(f.request())
    assertTrue(
        assertIs<AppResult.Success<List<LeaveHistoryWork>>>(f.repository.pendingHistory(f.access))
            .value
            .isEmpty()
    )
}

suspend fun checkRetainedOwnerAndAcquisitionCompareAndSet(f: LeaveRequestReplicaFixture) {
    f.publish(listOf(f.raw.requestFacts()))
    val original = requireNotNull(f.request())
    val token = f.writeCheckpoint()
    val replacement = f.snapshot(f.raw.requestFacts(version = 1))
    f.fresh(replacement)
    assertEquals(
        AppResult.Success(false),
        f.repository.replaceRequest(f.access, original.request.id, token, original),
    )
    val renewed =
        f.access.copy(
            sessionId = "new-login",
            sessionExpiresAt = f.raw.now + 5.days,
            authorizationExpiresAt = f.raw.now + 5.days,
        )
    f.db.accounts.activate(null)
    assertNull(f.request())
    f.db.accounts.activate(renewed.sessionId, leaveReplicaScope(renewed).accountKey)
    assertEquals(replacement, f.request(owner = renewed))
    assertEquals(
        AppResult.Success(false),
        f.repository.replaceRequest(f.access, original.request.id, f.writeCheckpoint(), original),
    )
    assertEquals(AppResult.Success(false), f.repository.clear(f.access))
    assertEquals(replacement, f.request(owner = renewed))
    val other = renewed.copy(accountId = replicaRequestId(300))
    f.db.accounts.activate(other.sessionId, leaveReplicaScope(other).accountKey)
    assertNull(f.request(owner = other))
}

suspend fun checkLateRequestEncryptionCannotPublish(f: LeaveRequestReplicaFixture) =
    coroutineScope {
        f.publish(listOf(f.raw.requestFacts(version = 21)))
        val snapshot = f.request()
        val checkpoint = f.checkpoint()
        val work =
            assertIs<AppResult.Success<List<LeaveHistoryWork>>>(
                    f.repository.pendingHistory(f.access)
                )
                .value
                .single()
        for (historyOnly in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var lastPlaintext: ByteArray? = null
            val cipher =
                object : ContentCipher by f.cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        lastPlaintext = plaintext
                        entered.complete(Unit)
                        return withContext(NonCancellable) {
                            release.await()
                            f.cipher.encrypt(key, associatedData, plaintext)
                        }
                    }
                }
            val repository =
                EncryptedLeaveReplicaRepository(
                    f.db.replicas,
                    cipher,
                    f.keys,
                    f.legacyKeys,
                    MemoryLeaveReadAdmissionDataSource(),
                )
            val expected = f.writeCheckpoint()
            val pending = async {
                if (historyOnly)
                    repository.storeHistory(
                        f.access,
                        work,
                        LeaveHistoryPage(
                            work.requestId,
                            work.requestVersion,
                            work.after,
                            f.raw.historyPage(work.requestVersion, work.after),
                        ),
                    )
                else
                    repository.replaceRequest(
                        f.access,
                        f.raw.request.id,
                        expected,
                        f.snapshot(f.raw.requestFacts(version = 22)),
                    )
            }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
            assertTrue(requireNotNull(lastPlaintext).all { it == 0.toByte() })
            assertEquals(checkpoint, f.checkpoint())
            assertEquals(snapshot, f.request())
            assertNull(f.request(after = work.after))
        }
    }
