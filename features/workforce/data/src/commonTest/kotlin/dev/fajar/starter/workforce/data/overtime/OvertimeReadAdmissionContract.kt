package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.datasources.MemoryOvertimeReadAdmissionDataSource
import dev.fajar.starter.workforce.data.overtime.encoding.overtimeAdmissionPartition
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaScope
import dev.fajar.starter.workforce.data.overtime.repositories.EncryptedOvertimeReplicaRepository
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeReadAdmission
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun checkOvertimeDenialSurvivesStorageCleanupAndLogin(f: OvertimeReplicaFixture) {
    f.publish(listOf(f.raw.facts(f.raw.request.id)))
    val denial = Failure(FailureKind.AccessDenied, "Private diagnostic", code = "mfa_required")
    assertEquals(AppResult.Success(true), f.repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<OvertimeReadAdmission>>(f.repository.admission(f.access)).value
    assertEquals("mfa_required", saved.denial!!.code)
    assertNotEquals(denial.message, saved.denial!!.message)
    assertNotNull(saved.revision)
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    val restored =
        EncryptedOvertimeReplicaRepository(
            f.db.replicas,
            f.cipher,
            f.keys,
            f.legacyKeys,
            MemoryOvertimeReadAdmissionDataSource(),
            f.keyCreation,
        )
    assertEquals(saved, (restored.admission(f.access) as AppResult.Success).value)
    val login = f.access.copy(sessionId = "new-login")
    f.db.accounts.activate(login.sessionId, overtimeReplicaScope(login).accountKey)
    assertEquals(saved, (restored.admission(login) as AppResult.Success).value)
    assertNull(
        (restored.admission(login.copy(companyId = overtimeReplicaId(999))) as AppResult.Success)
            .value
            .denial
    )
    assertEquals(AppResult.Success(true), restored.admit(login, saved.revision))
    assertNull((restored.admission(login) as AppResult.Success).value.denial)
    assertEquals(AppResult.Success(false), f.repository.restrict(f.access, denial))
    assertNull((restored.admission(login) as AppResult.Success).value.denial)
}

suspend fun checkOvertimeDenialRemainsKnownAfterFailedStorage(f: OvertimeReplicaFixture) {
    f.publish(listOf(f.raw.facts(f.raw.request.id)))
    val facts = f.request()
    var unavailable = true
    val store =
        object : ReplicaStore by f.db.replicas {
            override suspend fun replace(
                scope: ReplicaScope,
                partition: String,
                expected: ReplicaCheckpoint?,
                snapshot: ReplicaSnapshot?,
            ): Boolean {
                if (unavailable && partition == overtimeAdmissionPartition(f.access))
                    error("Fixture storage failure")
                return f.db.replicas.replace(scope, partition, expected, snapshot)
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
    val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    assertIs<AppResult.Failed>(repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<OvertimeReadAdmission>>(
                repository.observeAdmission(f.access).first()
            )
            .value
    assertEquals("mfa_required", saved.denial!!.code)
    assertEquals(saved, (f.repository.admission(f.access) as AppResult.Success).value)
    assertIs<AppResult.Failed>(repository.admit(f.access, saved.revision))
    assertEquals(saved, (repository.admission(f.access) as AppResult.Success).value)
    assertEquals(facts, f.request())
    val login = f.access.copy(sessionId = "replacement-login")
    f.db.accounts.activate(login.sessionId, overtimeReplicaScope(login).accountKey)
    assertEquals(saved, (repository.admission(login) as AppResult.Success).value)
    assertNull(
        (repository.admission(login.copy(companyId = overtimeReplicaId(999))) as AppResult.Success)
            .value
            .denial
    )
    unavailable = false
    assertEquals(AppResult.Success(false), repository.admit(login, null))
    assertEquals(AppResult.Success(true), repository.admit(login, saved.revision))
    assertNull((repository.admission(login) as AppResult.Success).value.denial)
}

suspend fun checkLateOvertimeAdmissionCannotClearANewerDenial(f: OvertimeReplicaFixture) =
    coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.db.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    if (snapshot == null && partition == overtimeAdmissionPartition(f.access)) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return f.db.replicas.replace(scope, partition, expected, snapshot)
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
        val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
        repository.restrict(f.access, denial)
        val first = (repository.admission(f.access) as AppResult.Success).value
        val old = async { repository.admit(f.access, first.revision) }
        entered.await()
        val newer =
            async(start = CoroutineStart.UNDISPATCHED) { repository.restrict(f.access, denial) }
        try {
            val next = (repository.admission(f.access) as AppResult.Success).value
            assertNotEquals(first.revision, next.revision)
            release.complete(Unit)
            assertEquals(AppResult.Success(false), old.await())
            assertEquals(AppResult.Success(true), newer.await())
            assertEquals(next, (repository.admission(f.access) as AppResult.Success).value)
            assertEquals(AppResult.Success(false), repository.admit(f.access, first.revision))
            assertEquals(AppResult.Success(true), repository.admit(f.access, next.revision))
        } finally {
            release.complete(Unit)
            old.cancel()
            newer.cancel()
        }
    }

suspend fun checkCancelledOvertimeDenialRetainsTheProcessGate(f: OvertimeReplicaFixture) =
    coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray =
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        f.cipher.encrypt(key, associatedData, plaintext)
                    }
            }
        val repository =
            EncryptedOvertimeReplicaRepository(
                f.db.replicas,
                cipher,
                f.keys,
                f.legacyKeys,
                f.admissions,
                f.keyCreation,
            )
        val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
        val pending = async { repository.restrict(f.access, denial) }
        entered.await()
        try {
            pending.cancel()
            release.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
            assertNull(
                f.db.replicas.checkpoint(
                    overtimeReplicaScope(f.access),
                    overtimeAdmissionPartition(f.access),
                )
            )
            val saved = (repository.admission(f.access) as AppResult.Success).value
            assertEquals("mfa_required", saved.denial!!.code)
            assertEquals(AppResult.Success(true), repository.admit(f.access, saved.revision))
        } finally {
            release.complete(Unit)
            pending.cancel()
        }
    }

suspend fun checkOvertimeAdmissionAuthenticatesItsCompanyAndRetainsALostKey(
    f: OvertimeReplicaFixture
) {
    val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    f.repository.restrict(f.access, denial)
    val raw =
        requireNotNull(
            f.db.replicas.checkpoint(
                overtimeReplicaScope(f.access),
                overtimeAdmissionPartition(f.access),
            )
        )
    assertFalse(raw.payload.contains(denial.message))
    val other = f.access.copy(companyId = overtimeReplicaId(999))
    assertTrue(
        f.db.replicas.replace(
            overtimeReplicaScope(other),
            overtimeAdmissionPartition(other),
            null,
            ReplicaSnapshot(raw.handle.generation, raw.payload, emptyList()),
        )
    )
    assertIs<AppResult.Failed>(f.repository.admission(other))
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.restrict(f.access, denial))
    assertNull(f.keys.read())
    val persisted =
        f.db.replicas.checkpoint(
            overtimeReplicaScope(f.access),
            overtimeAdmissionPartition(f.access),
        )
    assertEquals(raw, persisted)
}
