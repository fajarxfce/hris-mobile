package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.data.overtime.encoding.encodeOvertimeCheckpoint
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.data.overtime.repositories.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

suspend fun checkOvertimeRangeQueriesAndIndependentDetails(f: OvertimeReplicaFixture) {
    val records =
        (1..45).map { n ->
            f.raw.facts(
                overtimeReplicaId(n),
                date =
                    if (n <= 20) LocalDate(2026, 10, 12)
                    else if (n <= 37) LocalDate(2026, 10, 13) else LocalDate(2026, 11, 1),
            )
        }
    f.publish(records)
    val queries = mutableListOf<List<ReplicaQuery>>()
    val store =
        object : ReplicaStore by f.db.replicas {
            override fun observeQueries(
                scope: ReplicaScope,
                partition: String,
                requests: List<ReplicaQuery>,
            ): kotlinx.coroutines.flow.Flow<ReplicaQueryPage> {
                queries += requests
                return f.db.replicas.observeQueries(scope, partition, requests)
            }
        }
    val repository =
        EncryptedOvertimeReplicaRepository(
            store,
            f.cipher,
            f.keys,
            f.legacyKeys,
            f.admissions,
            f.keyCreation,
        )
    val selection =
        OvertimeSelection(
            f.raw.base.employee.id,
            CalendarPeriod(LocalDate(2026, 10, 12), LocalDate(2026, 10, 13)),
        )
    val first =
        assertIs<AppResult.Success<OvertimeReplicaPage>>(
                repository.observe(f.access, selection).first()
            )
            .value
    val second =
        assertIs<AppResult.Success<OvertimeReplicaPage>>(
                repository
                    .observe(f.access, selection.copy(requestAfter = first.requests.nextCursor))
                    .first()
            )
            .value
    assertEquals(
        records.take(37).map { it.request.id },
        (first.requests.items + second.requests.items).map { it.id },
    )
    assertNull(second.requests.nextCursor)
    assertTrue(queries.all { it.size == 1 && it.single().limit == 21 })
    val empty =
        assertIs<AppResult.Success<OvertimeReplicaPage>>(
                repository
                    .observe(f.access, selection.copy(status = OvertimeStatus.APPROVED))
                    .first()
            )
            .value
    assertTrue(empty.requests.items.isEmpty())
    assertEquals(records.first(), f.request(records.first().request.id)!!.value.details)
    assertEquals(records.last(), f.request(records.last().request.id)!!.value.details)
    assertEquals(records.first(), f.request(records.first().request.id)!!.value.details)
}

suspend fun checkOvertimeIndividualReadsBeforeAndAfterFeed(f: OvertimeReplicaFixture) {
    val one = f.snapshot(f.raw.facts())
    val two = f.snapshot(f.raw.facts(overtimeReplicaId(2)))
    f.fresh(one)
    f.fresh(two)
    assertNull(
        (f.repository.observeDirectory(f.access).first() as AppResult.Success).value.checkpoint
    )
    assertEquals(one, f.request(one.value.selection.requestId))
    assertEquals(two, f.request(two.value.selection.requestId))
    assertNull(
        (f.repository.observePublishedCheckpoint(f.access).first() as AppResult.Success)
            .value
            ?.refreshedAt
    )
    f.publish(listOf(one.value.details, two.value.details))
    assertNotNull(
        (f.repository.observeDirectory(f.access).first() as AppResult.Success).value.checkpoint
    )
    assertEquals(one.value.details, f.request()!!.value.details)
    val checkpoint = f.writeCheckpoint()
    f.fresh(f.snapshot(two.value.details))
    assertEquals(
        AppResult.Success(false),
        f.repository.replaceRequest(f.access, one.value.selection.requestId, checkpoint, one),
    )
    assertEquals(two.value.details, f.request(two.value.selection.requestId)!!.value.details)
}

suspend fun checkOvertimeHistoryProgressAndOriginalReviewLease(f: OvertimeReplicaFixture) {
    val details = f.raw.facts(version = 24, dense = true)
    f.publish(listOf(details))
    val current = f.request()!!
    val pending =
        (f.repository.pendingHistory(f.access).let {
                assertIs<AppResult.Success<List<OvertimeHistoryWork>>>(it)
            })
            .value
            .single()
    assertEquals(15L, pending.after)
    val page =
        OvertimeHistoryPage(
            details.request.id,
            24,
            pending.after,
            f.raw.history(details.request, pending.after, true),
        )
    assertEquals(AppResult.Success(true), f.repository.storeHistory(f.access, pending, page))
    assertEquals(AppResult.Success(false), f.repository.storeHistory(f.access, pending, page))
    val older = f.request(after = pending.after)!!
    assertEquals(page.history, older.value.details.history)
    assertEquals(current.value.details.request, older.value.details.request)
    assertEquals(current.fetchedAt, older.fetchedAt)
    assertEquals(current.refreshAfter, older.refreshAfter)
    assertEquals(current.access.authorizationExpiresAt, older.access.authorizationExpiresAt)
    val next = (f.repository.pendingHistory(f.access) as AppResult.Success).value.single()
    assertEquals(5L, next.after)
    f.fresh(f.snapshot(f.raw.facts(version = 25)))
    assertEquals(
        AppResult.Success(false),
        f.repository.storeHistory(
            f.access,
            next,
            OvertimeHistoryPage(
                details.request.id,
                24,
                next.after,
                f.raw.history(details.request, next.after),
            ),
        ),
    )
    assertEquals(25L, f.request(after = pending.after)!!.value.details.request.version)
    assertEquals(page.history, f.request(after = pending.after)!!.value.details.history)
}

suspend fun checkOvertimeDeletedParentsAndStatusIndexes(f: OvertimeReplicaFixture) {
    val planned = f.raw.facts()
    f.publish(listOf(planned))
    val selection =
        OvertimeSelection(
            planned.request.employeeId,
            overtimeMonth(planned.request.workDate),
            OvertimeStatus.PLANNED,
        )
    f.publish(listOf(f.raw.facts(version = 12)))
    assertTrue(
        (f.repository.observe(f.access, selection).first() as AppResult.Success)
            .value
            .requests
            .items
            .isEmpty()
    )
    val pending = (f.repository.pendingHistory(f.access) as AppResult.Success).value.single()
    f.repository.storeHistory(
        f.access,
        pending,
        OvertimeHistoryPage(
            planned.request.id,
            12,
            pending.after,
            f.raw.history(f.raw.facts(version = 12).request, pending.after),
        ),
    )
    val previous = f.checkpoint()!!
    val next = previous.copy(revision = previous.revision + 1)
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            next,
            f.directory,
            listOf(
                OvertimeReplicaEntry(
                    SyncReference(SyncCollection.OVERTIME_REQUESTS, planned.request.id, 13),
                    null,
                )
            ),
        ),
    )
    assertNull(f.request())
    assertNull(f.request(after = pending.after))
    assertTrue((f.repository.pendingHistory(f.access) as AppResult.Success).value.isEmpty())
    f.publish(listOf(planned))
    assertNull(f.request())
}

suspend fun checkOvertimeStagingProtectsPublishedFacts(f: OvertimeReplicaFixture) {
    f.publish(listOf(f.raw.facts()))
    val old = f.request()!!
    val previous = f.checkpoint()!!
    val staged = initialOvertimeCheckpoint(f.access, "replacement", f.raw.base.now)
    assertEquals(AppResult.Success(true), f.repository.begin(staged, previous.generation))
    assertEquals(old, f.request())
    assertEquals(
        AppResult.Success(previous),
        f.repository.observePublishedCheckpoint(f.access).first(),
    )
    assertEquals(
        AppResult.Success(false),
        f.repository.replaceRequest(
            f.access,
            f.raw.request.id,
            f.writeCheckpoint(),
            f.snapshot(f.raw.facts(version = 1)),
        ),
    )
    assertEquals(old, f.request())
    f.publish(emptyList())
    assertNull(f.request())
}

suspend fun checkOvertimeRetainedOwnershipAndLateWriters(f: OvertimeReplicaFixture) {
    val first = f.snapshot(f.raw.facts())
    f.fresh(first)
    val oldCheckpoint = f.writeCheckpoint()
    val renewed =
        f.access.copy(
            sessionId = "second-login",
            sessionExpiresAt = f.access.sessionExpiresAt + 4.days,
        )
    f.db.accounts.activate(renewed.sessionId, overtimeReplicaScope(renewed).accountKey)
    val saved = f.request(owner = renewed)!!
    assertEquals(first.value, saved.value)
    assertEquals(first.fetchedAt, saved.fetchedAt)
    assertEquals(first.access.sessionId, saved.access.sessionId)
    assertIs<AppResult.Success<Unit>>(validateStoredOvertime(renewed, saved))
    assertIs<AppResult.Failed>(validateOvertimeActionSnapshot(renewed, saved, f.raw.base.now))
    assertEquals(
        AppResult.Success(false),
        f.repository.replaceRequest(f.access, f.raw.request.id, oldCheckpoint, first),
    )
    val foreign = renewed.copy(accountId = "99999999-9999-9999-9999-999999999999")
    f.db.accounts.activate("foreign", overtimeReplicaScope(foreign).accountKey)
    assertNull(f.request(owner = foreign.copy(sessionId = "foreign")))
    f.db.accounts.activate(renewed.sessionId, overtimeReplicaScope(renewed).accountKey)
    assertEquals(first.value, f.request(owner = renewed)!!.value)
}

suspend fun checkOvertimeCiphertextAndIndexAuthentication(f: OvertimeReplicaFixture) {
    f.publish(listOf(f.raw.facts()))
    val scope = overtimeReplicaScope(f.access)
    val partition = overtimeReplicaPartition(f.access)
    val page =
        f.db.replicas.observeDocument(scope, partition, "summary/" + f.raw.request.id).first()
    val document = page.documents.single()
    assertFalse(document.payload!!.contains(f.raw.request.reason))
    assertFalse(document.payload!!.contains("employeeName"))
    val raw = page.checkpoint!!
    val key = Base64.decode(requireNotNull(f.keys.read()))
    val checkpoint =
        try {
            encodeOvertimeCheckpoint(
                f.cipher,
                key,
                f.checkpoint()!!.copy(revision = raw.revision + 1),
            )
        } finally {
            key.fill(0)
        }
    assertTrue(
        f.db.replicas.commit(
            raw.handle,
            raw.revision,
            checkpoint,
            listOf(document.copy(sortKey = "r/2026-10-13/" + f.raw.request.id)),
            true,
        )
    )
    val selection = OvertimeSelection(f.raw.base.employee.id, overtimeMonth(LocalDate(2026, 10, 1)))
    assertIs<AppResult.Failed>(f.repository.observe(f.access, selection).first())
    assertNotNull(f.request())
    val missing = MemoryCredentialStore()
    try {
        val replacement =
            EncryptedOvertimeReplicaRepository(
                f.db.replicas,
                f.cipher,
                missing,
                f.legacyKeys,
                f.admissions,
                f.keyCreation,
            )
        assertIs<AppResult.Failed>(
            replacement.replaceRequest(
                f.access,
                f.raw.request.id,
                (replacement.requestCheckpoint(f.access) as AppResult.Success).value,
                f.snapshot(f.raw.facts()),
            )
        )
        assertNull(missing.read())
    } finally {
        missing.close()
    }
}

suspend fun checkOvertimeCancelledEncryptionCannotPublish(f: OvertimeReplicaFixture) =
    coroutineScope {
        for (cancel in listOf(true, false)) {
            f.activate()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val buffers = mutableListOf<ByteArray>()
            val delayed =
                object : ContentCipher by f.cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        buffers += key
                        buffers += plaintext
                        val encrypted = f.cipher.encrypt(key, associatedData, plaintext)
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return encrypted
                    }
                }
            val repository =
                EncryptedOvertimeReplicaRepository(
                    f.db.replicas,
                    delayed,
                    f.keys,
                    f.legacyKeys,
                    f.admissions,
                    f.keyCreation,
                )
            val running = async {
                repository.replaceRequest(
                    f.access,
                    f.raw.request.id,
                    f.writeCheckpoint(),
                    f.snapshot(f.raw.facts()),
                )
            }
            entered.await()
            if (cancel) running.cancel()
            else f.db.accounts.activate("replacement", "another-account")
            release.complete(Unit)
            if (cancel) assertFailsWith<CancellationException> { running.await() }
            else assertEquals(AppResult.Success(false), running.await())
            assertTrue(buffers.all { bytes -> bytes.all { it == 0.toByte() } })
            f.activate()
            assertNull(f.request())
        }
    }

suspend fun checkOvertimeLegacyReadOnlyMigration(f: OvertimeReplicaFixture) {
    val legacyKey = f.cipher.generateKey()
    try {
        f.legacyKeys.write(Base64.encode(legacyKey))
    } finally {
        legacyKey.fill(0)
    }
    val writer = LegacyOvertimeCacheWriter(f.db.replicas, f.cipher, f.legacyKeys)
    val details = f.raw.facts(version = 12)
    val after = 3L
    val legacy =
        f.snapshot(details)
            .copy(
                value =
                    OvertimeRequestView(
                        f.raw.selection.copy(historyAfter = after),
                        details.copy(history = f.raw.history(details.request, after)),
                    )
            )
    assertEquals(AppResult.Success(true), writer.replaceRequest(f.access, null, legacy))
    assertEquals(legacy.value, f.request(after = after)!!.value)
    f.publish(listOf(details))
    assertEquals(details.request, f.request(after = after)!!.value.details.request)
    assertEquals(legacy.value.details.history, f.request(after = after)!!.value.details.history)
    val work = (f.repository.pendingHistory(f.access) as AppResult.Success).value.single()
    assertEquals(
        AppResult.Success(true),
        f.repository.storeHistory(
            f.access,
            work,
            OvertimeHistoryPage(
                details.request.id,
                12,
                after,
                f.raw.history(details.request, after),
            ),
        ),
    )
    assertEquals(details, f.request()!!.value.details)
}
