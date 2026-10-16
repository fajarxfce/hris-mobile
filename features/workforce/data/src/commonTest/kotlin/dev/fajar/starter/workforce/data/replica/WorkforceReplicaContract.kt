package dev.fajar.starter.workforce.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.data.replica.encoding.collection
import dev.fajar.starter.workforce.data.replica.mappers.*
import dev.fajar.starter.workforce.data.replica.repositories.EncryptedWorkforceReplicaRepository
import dev.fajar.starter.workforce.domain.entities.CalendarPeriod
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workforce.domain.replica.policies.initialWorkforceCheckpoint
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

suspend fun checkWorkforceBoundedHistoricalQueries(f: WorkforceReplicaFixture) {
    val records =
        (1..90).map { f.attendance(it, LocalDate(2020, 1, 1).plus(it - 1, DateTimeUnit.DAY)) }
    val rules =
        (1..90).map {
            f.schedule.copy(
                id = workforceRecordId(3000 + it),
                version = it.toLong(),
                effectiveFrom = LocalDate(2010, 1, 1).plus(it - 1, DateTimeUnit.MONTH),
            )
        }
    f.publish(records + rules + listOf(f.schedule))
    val queries = mutableListOf<List<ReplicaQuery>>()
    val store =
        object : ReplicaStore by f.db.replicas {
            override fun observeQueries(
                scope: ReplicaScope,
                partition: String,
                requests: List<ReplicaQuery>,
            ): Flow<ReplicaQueryPage> {
                queries += requests
                return f.db.replicas.observeQueries(scope, partition, requests)
            }
        }
    val repository = EncryptedWorkforceReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val february = CalendarPeriod(LocalDate(2020, 2, 1), LocalDate(2020, 2, 29))
    val saved =
        assertIs<AppResult.Success<WorkforceReplicaFacts>>(
                repository.observeFacts(f.access, f.raw.employee.id, february).first()
            )
            .value
    assertEquals(records.filter { it.day.date in february.from..february.until }, saved.attendance)
    assertEquals(listOf(rules.last()), saved.schedules)
    assertTrue(
        queries.all {
            it.size == 4 &&
                it.sumOf { query -> query.limit } <= 132 &&
                it.all { query -> query.tag != null }
        }
    )
    assertTrue(
        f.facts(CalendarPeriod(LocalDate(1999, 1, 1), LocalDate(1999, 1, 7))).schedules.isEmpty()
    )
    assertEquals(
        listOf(f.schedule),
        f.facts(CalendarPeriod(LocalDate(2000, 1, 1), LocalDate(2000, 1, 7))).schedules,
    )
    assertTrue(f.facts(february, workforceRecordId(999)).attendance.isEmpty())
    assertEquals(
        31,
        f.facts(CalendarPeriod(LocalDate(2020, 1, 1), LocalDate(2020, 1, 31))).attendance.size,
    )
}

suspend fun checkWorkforceStableIdsUpdatesAndTombstones(f: WorkforceReplicaFixture) {
    val roster =
        WorkforceRecord.Roster(
            workforceRecordId(30),
            0,
            f.raw.employee.id,
            f.raw.period.from,
            f.shift,
        )
    val holiday = WorkforceRecord.Holiday(roster.id, 0, f.raw.period.from, "Holiday", true)
    f.publish(listOf(f.schedule, roster, holiday))
    f.publish(
        listOf(roster.copy(version = 1, shift = null), holiday.copy(version = 1, active = false))
    )
    assertNull(f.facts().roster.single().shift)
    assertFalse(f.facts().holidays.single().active)
    val previous = f.checkpoint()!!
    val next = previous.copy(revision = previous.revision + 1)
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            next,
            f.directory,
            listOf(
                WorkforceReplicaEntry(SyncReference(SyncCollection.ROSTER_DAYS, roster.id, 2), null)
            ),
        ),
    )
    f.publish(listOf(roster.copy(version = 1)))
    assertTrue(f.facts().roster.isEmpty())
    assertEquals(holiday.id, f.facts().holidays.single().id)
}

suspend fun checkWorkforceReplacementAndAccountOwnership(f: WorkforceReplicaFixture) {
    f.publish(listOf(f.schedule, f.attendance(1, f.raw.period.from)))
    val first = f.facts()
    val initial = initialWorkforceCheckpoint(f.access, "replacement", f.raw.now)
    assertEquals(
        AppResult.Success(true),
        f.repository.begin(initial, first.checkpoint!!.generation),
    )
    f.publish(listOf(f.schedule.copy(version = 3, days = emptyMap())), publish = false)
    assertEquals(first, f.facts())
    assertEquals(
        AppResult.Success(first.checkpoint),
        f.repository.observePublishedCheckpoint(f.access).first(),
    )
    f.publish(emptyList())
    assertTrue(f.facts().attendance.isEmpty())
    assertTrue(f.facts().schedules.single().days.isEmpty())
    val retained = f.facts().schedules
    val oldWriter = f.checkpoint()!!
    val nextLogin = f.access.copy(sessionId = "next-login")
    f.db.accounts.activate(nextLogin.sessionId, workforceReplicaScope(nextLogin).accountKey)
    assertEquals(retained, f.facts(owner = nextLogin).schedules)
    assertEquals(
        AppResult.Success(false),
        f.repository.commit(
            oldWriter,
            oldWriter.copy(revision = oldWriter.revision + 1),
            f.directory,
            emptyList(),
        ),
    )
    val other = nextLogin.copy(accountId = workforceRecordId(555), sessionId = "other-account")
    f.db.accounts.activate(other.sessionId, workforceReplicaScope(other).accountKey)
    assertNull(f.facts(owner = other).checkpoint)
    assertEquals(AppResult.Success(null), f.repository.observePublishedCheckpoint(other).first())
    f.db.accounts.activate(nextLogin.sessionId, workforceReplicaScope(nextLogin).accountKey)
    assertEquals(3, f.facts(owner = nextLogin).schedules.single().version)
}

suspend fun checkWorkforceAuthenticatedRecordsAndMissingKey(f: WorkforceReplicaFixture) {
    f.publish(listOf(f.schedule, f.attendance(1, f.raw.period.from)))
    for (component in listOf("payload", "sort", "tags", "id", "version")) {
        val store =
            object : ReplicaStore by f.db.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) =
                    f.db.replicas.observeQueries(scope, partition, queries).map { page ->
                        page.copy(
                            pages =
                                page.pages.mapIndexed { index, rows ->
                                    if (index != 0) rows
                                    else
                                        rows.map { row ->
                                            when (component) {
                                                "payload" ->
                                                    row.copy(payload = Base64.encode(ByteArray(32)))
                                                "sort" -> row.copy(sortKey = row.sortKey + "x")
                                                "tags" -> row.copy(tags = emptySet())
                                                "id" -> row.copy(id = row.id + "x")
                                                else -> row.copy(version = row.version + 1)
                                            }
                                        }
                                }
                        )
                    }
            }
        val repository = EncryptedWorkforceReplicaRepository(store, f.cipher, f.keys, f.admissions)
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(
                    repository.observeFacts(f.access, f.raw.employee.id, f.raw.period).first()
                )
                .failure
                .kind,
        )
    }
    f.keys.write(null)
    assertIs<AppResult.Failed>(
        f.repository.observeFacts(f.access, f.raw.employee.id, f.raw.period).first()
    )
    assertIs<AppResult.Failed>(
        f.repository.begin(
            initialWorkforceCheckpoint(f.access, "new-key", f.raw.now),
            f.checkpointGeneration(),
        )
    )
    assertNull(f.keys.read())
}

private suspend fun WorkforceReplicaFixture.checkpointGeneration() =
    db.replicas
        .checkpoint(workforceReplicaScope(access), workforceReplicaPartition(access))!!
        .handle
        .generation

suspend fun checkWorkforceCancelledEncryptionCannotPublish(f: WorkforceReplicaFixture) =
    coroutineScope {
        f.publish(listOf(f.schedule))
        val previous = f.checkpoint()!!
        val before = f.facts()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val keys = mutableListOf<ByteArray>()
        val plaintexts = mutableListOf<ByteArray>()
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    keys += key
                    plaintexts += plaintext
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    return f.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        val repository =
            EncryptedWorkforceReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions)
        val pending = async {
            repository.commit(
                previous,
                previous.copy(revision = previous.revision + 1),
                f.directory,
                listOf(
                    WorkforceReplicaEntry(
                        SyncReference(f.schedule.collection(), f.schedule.id, 3),
                        f.schedule.copy(version = 3),
                    )
                ),
            )
        }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(before, f.facts())
        assertTrue(keys.isNotEmpty() && keys.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(plaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
    }

suspend fun checkWorkforceFactsWithoutAnyReplicaDoNotRequireAContentKey(
    f: WorkforceReplicaFixture
) {
    assertNull(f.facts().checkpoint)
    assertNull(f.keys.read())
    val initial = initialWorkforceCheckpoint(f.access, "staging", f.raw.now)
    assertEquals(AppResult.Success(true), f.repository.begin(initial, null))
    assertNull(f.facts().checkpoint)
    assertNull(
        (f.repository.observeDirectory(f.access).first() as AppResult.Success).value.checkpoint
    )
}
