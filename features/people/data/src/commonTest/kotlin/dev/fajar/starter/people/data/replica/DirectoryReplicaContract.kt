package dev.fajar.starter.people.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.replica.mappers.*
import dev.fajar.starter.people.data.replica.repositories.EncryptedDirectoryReplicaRepository
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.policies.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

suspend fun checkDirectoryPaginationRestartAndOwnership(f: DirectoryReplicaFixture) {
    val records = (1..451).map { directoryFixtureRecord(it) }
    f.publish(records)
    assertEquals(records, f.snapshot().records)
    val view = selectDirectory(f.snapshot(), DirectorySelection(query = "451"))!!
    assertEquals(records.last().employee, view.page.items.single())
    assertEquals(
        20,
        selectDirectory(f.snapshot(), DirectorySelection(departmentId = directoryFixtureId(990)))!!
            .page
            .items
            .size,
    )
    f.reopen(f.access.copy(sessionId = "new-login"))
    val next = f.access.copy(sessionId = "new-login")
    assertEquals(records, f.snapshot(next).records)
    assertNull(f.snapshot(next.copy(companyId = directoryFixtureId(9910))).checkpoint)
    val other = next.copy(sessionId = "other", accountId = directoryFixtureId(9911))
    f.activate(other)
    assertNull(f.snapshot(other).checkpoint)
    assertNull(f.snapshot(next).checkpoint)
    f.activate(next)
    assertEquals(records, f.snapshot(next).records)
}

suspend fun checkDirectoryVersionsTombstonesAndAtomicReplacement(f: DirectoryReplicaFixture) {
    f.publish(listOf(f.record))
    f.publish(
        listOf(f.record.copy(version = 2, employee = f.record.employee.copy(name = "Updated")))
    )
    f.publish(listOf(f.record.copy(version = 1)))
    assertEquals("Updated", f.snapshot().records.single().employee.name)
    var previous = f.checkpoint()!!
    var next = previous.copy(revision = previous.revision + 1)
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            next,
            listOf(
                DirectoryReplicaEntry(
                    SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, f.record.employee.id, 3),
                    null,
                )
            ),
        ),
    )
    f.publish(listOf(f.record.copy(version = 2)))
    assertTrue(f.snapshot().records.isEmpty())
    f.publish(listOf(f.record.copy(version = 4)))
    val retained = f.snapshot()
    val replacement = initialDirectoryCheckpoint(f.access, "replacement", f.now)
    assertEquals(
        AppResult.Success(true),
        f.repository.begin(replacement, f.checkpoint()!!.generation),
    )
    f.publish(listOf(directoryFixtureRecord(2)), publish = false)
    assertEquals(retained, f.snapshot())
    assertEquals(
        AppResult.Success(retained.checkpoint),
        f.repository.observePublishedCheckpoint(f.access).first(),
    )
    previous = f.checkpoint()!!
    next = previous.copy(revision = previous.revision + 1, published = true, refreshedAt = f.now)
    assertEquals(AppResult.Success(true), f.repository.commit(previous, next, emptyList()))
    assertEquals(listOf(directoryFixtureRecord(2)), f.snapshot().records)
}

suspend fun checkDirectoryEncryptionAndLostKey(f: DirectoryReplicaFixture) {
    f.publish(listOf(f.record))
    val raw =
        f.db.replicas.document(
            directoryReplicaScope(f.access),
            directoryReplicaPartition(f.access),
            "employee/${f.record.employee.id}",
        )!!
    assertFalse(raw.payload!!.contains(f.record.employee.name))
    assertFalse(raw.payload!!.contains(f.record.employee.employeeNumber))
    assertEquals(raw.id, raw.sortKey)
    assertTrue(raw.tags.isEmpty())
    val before = f.checkpoint()!!
    val malformed =
        directoryFixtureRecord(2)
            .copy(employee = directoryFixtureRecord(2).employee.copy(departmentId = null))
    assertIs<AppResult.Failed>(
        f.repository.commit(
            before,
            before.copy(revision = before.revision + 1),
            listOf(
                DirectoryReplicaEntry(
                    SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, malformed.employee.id, 0),
                    malformed,
                )
            ),
        )
    )
    assertEquals(before, f.checkpoint())
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.observe(f.access).first())
    assertIs<AppResult.Failed>(
        f.repository.begin(
            initialDirectoryCheckpoint(f.access, "lost-key", f.now),
            before.generation,
        )
    )
    assertNull(f.keys.read())
}

suspend fun checkDirectoryCancellationDuringEncryption(f: DirectoryReplicaFixture) =
    coroutineScope {
        f.publish(listOf(f.record))
        val before = f.checkpoint()!!
        val entered = CompletableDeferred<Unit>()
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        val repository =
            EncryptedDirectoryReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions)
        val job = launch {
            repository.commit(before, before.copy(revision = before.revision + 1), emptyList())
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(before, f.checkpoint())
        assertEquals(listOf(f.record), f.snapshot().records)
    }

suspend fun checkDirectoryScanRejectsMixedRevisions(f: DirectoryReplicaFixture) {
    f.publish((1..251).map { directoryFixtureRecord(it) })
    var changed = false
    val store =
        object : ReplicaStore by f.db.replicas {
            override fun observe(
                scope: ReplicaScope,
                partition: String,
                after: String?,
                limit: Int,
            ) = flow {
                if (after != null && !changed) {
                    changed = true
                    f.publish(
                        listOf(
                            directoryFixtureRecord(1, 1).let {
                                it.copy(employee = it.employee.copy(name = "Changed"))
                            }
                        )
                    )
                }
                emitAll(f.db.replicas.observe(scope, partition, after, limit))
            }
        }
    val repository = EncryptedDirectoryReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val value =
        assertIs<AppResult.Success<DirectorySnapshot>>(repository.observe(f.access).first()).value
    assertEquals(251, value.records.size)
    assertEquals("Changed", value.records.first().employee.name)
    assertEquals(f.checkpoint(), value.checkpoint)
}

suspend fun checkEmptyDirectoryDoesNotCreateAKey(f: DirectoryReplicaFixture) {
    assertNull(f.snapshot().checkpoint)
    assertNull(f.keys.read())
    f.publish(emptyList())
    assertNotNull(f.snapshot().checkpoint)
    assertTrue(f.snapshot().records.isEmpty())
}
