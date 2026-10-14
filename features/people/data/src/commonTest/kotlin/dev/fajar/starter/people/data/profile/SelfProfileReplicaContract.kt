package dev.fajar.starter.people.data.profile

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.profile.mappers.*
import dev.fajar.starter.people.data.profile.repositories.EncryptedSelfProfileReplicaRepository
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate

suspend fun checkSelfProfilePaginationRestartAndOwnership(f: SelfProfileReplicaFixture) {
    val records = (1..200).map { selfProfileFixtureRecord(it) }
    f.publish(records)
    assertEquals(records, f.snapshot().records)
    val view = selectSelfProfile(f.snapshot(), selfProfileFixtureId(200))!!
    assertEquals(records.last().profile, view.profile)
    assertEquals(200, view.employments.size)
    f.reopen(f.access.copy(sessionId = "new-login"))
    val next = f.access.copy(sessionId = "new-login")
    assertEquals(records, f.snapshot(next).records)
    assertNull(f.snapshot(next.copy(companyId = selfProfileFixtureId(9910))).checkpoint)
    val other = next.copy(sessionId = "other", accountId = selfProfileFixtureId(9911))
    f.activate(other)
    assertNull(f.snapshot(other).checkpoint)
    assertNull(f.snapshot(next).checkpoint)
    f.activate(next)
    assertEquals(records, f.snapshot(next).records)
}

suspend fun checkSelfProfileVersionsTombstonesAndAtomicReplacement(f: SelfProfileReplicaFixture) {
    f.publish(listOf(f.record))
    f.publish(
        listOf(
            f.record.copy(
                version = 2,
                profile =
                    f.record.profile.copy(
                        person =
                            f.record.profile.person.copy(
                                birthDate = LocalDate(1991, 2, 3),
                                version = 8,
                            )
                    ),
            )
        )
    )
    f.publish(listOf(f.record.copy(version = 1)))
    assertEquals(LocalDate(1991, 2, 3), f.snapshot().records.single().profile.person.birthDate)
    var previous = f.checkpoint()!!
    var next = previous.copy(revision = previous.revision + 1)
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            next,
            listOf(
                SelfProfileReplicaEntry(
                    SyncReference(SyncCollection.SELF_PROFILES, f.record.id, 3),
                    null,
                )
            ),
        ),
    )
    f.publish(listOf(f.record.copy(version = 2)))
    assertTrue(f.snapshot().records.isEmpty())
    f.publish(listOf(f.record.copy(version = 4)))
    val retained = f.snapshot()
    val replacement = initialSelfProfileCheckpoint(f.access, "replacement", f.now)
    assertEquals(
        AppResult.Success(true),
        f.repository.begin(replacement, f.checkpoint()!!.generation),
    )
    f.publish(listOf(selfProfileFixtureRecord(2)), publish = false)
    assertEquals(retained, f.snapshot())
    previous = f.checkpoint()!!
    next = previous.copy(revision = previous.revision + 1, published = true, refreshedAt = f.now)
    assertEquals(AppResult.Success(true), f.repository.commit(previous, next, emptyList()))
    assertEquals(listOf(selfProfileFixtureRecord(2)), f.snapshot().records)
}

suspend fun checkSelfProfileEncryptionAndLostKey(f: SelfProfileReplicaFixture) {
    f.publish(listOf(f.record))
    val raw =
        f.db.replicas.document(
            selfProfileReplicaScope(f.access),
            selfProfileReplicaPartition(f.access),
            "profile/${f.record.id}",
        )!!
    assertFalse(raw.payload!!.contains(f.record.profile.person.legalName))
    assertFalse(raw.payload!!.contains(f.record.profile.person.email!!))
    assertFalse(raw.payload!!.contains(f.record.profile.person.birthDate.toString()))
    assertFalse(raw.payload!!.contains(f.record.profile.details.employee.employeeNumber))
    assertEquals(raw.id, raw.sortKey)
    assertTrue(raw.tags.isEmpty())
    val before = f.checkpoint()!!
    val nextRecord = selfProfileFixtureRecord(2)
    val malformed =
        nextRecord.copy(
            profile =
                nextRecord.profile.copy(
                    person = nextRecord.profile.person.copy(accountId = selfProfileFixtureId(9912))
                )
        )
    assertIs<AppResult.Failed>(
        f.repository.commit(
            before,
            before.copy(revision = before.revision + 1),
            listOf(
                SelfProfileReplicaEntry(
                    SyncReference(SyncCollection.SELF_PROFILES, malformed.id, 0),
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
            initialSelfProfileCheckpoint(f.access, "lost-key", f.now),
            before.generation,
        )
    )
    assertNull(f.keys.read())
}

suspend fun checkSelfProfileCancellationDuringEncryption(f: SelfProfileReplicaFixture) =
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
            EncryptedSelfProfileReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions)
        val job = launch {
            repository.commit(before, before.copy(revision = before.revision + 1), emptyList())
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(before, f.checkpoint())
        assertEquals(listOf(f.record), f.snapshot().records)
    }

suspend fun checkSelfProfileScanRejectsMixedRevisions(f: SelfProfileReplicaFixture) {
    f.publish((1..200).map { selfProfileFixtureRecord(it) })
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
                            selfProfileFixtureRecord(1, 1).let {
                                it.copy(
                                    profile =
                                        it.profile.copy(
                                            person =
                                                it.profile.person.copy(
                                                    birthDate = LocalDate(1991, 2, 3),
                                                    version = 8,
                                                )
                                        )
                                )
                            }
                        )
                    )
                }
                emitAll(f.db.replicas.observe(scope, partition, after, limit))
            }
        }
    val repository = EncryptedSelfProfileReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val value =
        assertIs<AppResult.Success<SelfProfileSnapshot>>(repository.observe(f.access).first()).value
    assertEquals(200, value.records.size)
    assertEquals(LocalDate(1991, 2, 3), value.records.first().profile.person.birthDate)
    assertEquals(f.checkpoint(), value.checkpoint)
}

suspend fun checkEmptySelfProfileDoesNotCreateAKey(f: SelfProfileReplicaFixture) {
    assertNull(f.snapshot().checkpoint)
    assertNull(f.keys.read())
    f.publish(emptyList())
    assertNotNull(f.snapshot().checkpoint)
    assertTrue(f.snapshot().records.isEmpty())
}
