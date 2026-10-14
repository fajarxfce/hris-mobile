package dev.fajar.starter.people.data.profile

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.profile.datasources.MemorySelfProfileReadAdmissionDataSource
import dev.fajar.starter.people.data.profile.encoding.selfProfileAdmissionPartition
import dev.fajar.starter.people.data.profile.mappers.selfProfileReplicaScope
import dev.fajar.starter.people.data.profile.repositories.EncryptedSelfProfileReplicaRepository
import dev.fajar.starter.people.domain.profile.entities.SelfProfileReadAdmission
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun checkSelfProfilePendingDenialSurvivesReauthentication(f: SelfProfileReplicaFixture) {
    f.publish(listOf(f.record))
    val store =
        object : ReplicaStore by f.db.replicas {
            override suspend fun replace(
                scope: ReplicaScope,
                partition: String,
                expected: ReplicaCheckpoint?,
                snapshot: ReplicaSnapshot?,
            ): Boolean = error("Fixture storage unavailable")
        }
    val repository = EncryptedSelfProfileReplicaRepository(store, f.cipher, f.keys, f.admissions)
    assertIs<AppResult.Failed>(
        repository.restrict(
            f.access,
            Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
        )
    )
    val next = f.access.copy(sessionId = "new-login")
    f.activate(next)
    val admission =
        assertIs<AppResult.Success<SelfProfileReadAdmission>>(repository.admission(next)).value
    assertEquals("mfa_required", admission.denial!!.code)
    assertEquals(listOf(f.record), f.snapshot(next).records)
    assertEquals(AppResult.Success(true), f.repository.admit(next, admission.revision))
    assertNull(
        assertIs<AppResult.Success<SelfProfileReadAdmission>>(repository.admission(next))
            .value
            .denial
    )
}

suspend fun checkSelfProfileReadDenialSurvivesRepositoryAndSessionReplacement(
    f: SelfProfileReplicaFixture
) {
    f.publish(listOf(f.record))
    val denial =
        Failure(
            FailureKind.AccessDenied,
            "Private diagnostics must not be stored.",
            code = "mfa_required",
        )
    assertEquals(AppResult.Success(true), f.repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<SelfProfileReadAdmission>>(f.repository.admission(f.access))
            .value
    assertEquals("mfa_required", saved.denial!!.code)
    assertNotEquals(denial.message, saved.denial!!.message)
    assertNotNull(saved.revision)
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    val restored =
        EncryptedSelfProfileReplicaRepository(
            f.db.replicas,
            f.cipher,
            f.keys,
            MemorySelfProfileReadAdmissionDataSource(),
        )
    assertEquals(saved, (restored.admission(f.access) as AppResult.Success).value)
    val nextLogin = f.access.copy(sessionId = "new-login")
    f.db.accounts.activate(nextLogin.sessionId, selfProfileReplicaScope(nextLogin).accountKey)
    assertEquals(saved, (restored.admission(nextLogin) as AppResult.Success).value)
    assertEquals(AppResult.Success(true), restored.admit(nextLogin, saved.revision))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertEquals(AppResult.Success(false), f.repository.restrict(f.access, denial))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertNull(
        (restored.admission(nextLogin.copy(companyId = selfProfileFixtureId(990)))
                as AppResult.Success)
            .value
            .denial
    )
}

suspend fun checkSelfProfileReadDenialRemainsVisibleWhenStorageFails(f: SelfProfileReplicaFixture) {
    f.publish(listOf(f.record))
    var unavailable = true
    val store =
        object : ReplicaStore by f.db.replicas {
            override suspend fun replace(
                scope: ReplicaScope,
                partition: String,
                expected: ReplicaCheckpoint?,
                snapshot: ReplicaSnapshot?,
            ): Boolean {
                if (unavailable && partition == selfProfileAdmissionPartition(f.access))
                    error("Fixture storage failure")
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedSelfProfileReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    assertIs<AppResult.Failed>(repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<SelfProfileReadAdmission>>(
                repository.observeAdmission(f.access).first()
            )
            .value
    assertEquals("mfa_required", saved.denial!!.code)
    assertIs<AppResult.Failed>(repository.admit(f.access, saved.revision))
    assertEquals(saved, (repository.admission(f.access) as AppResult.Success).value)
    assertEquals(listOf(f.record), f.snapshot().records)
    unavailable = false
    assertEquals(AppResult.Success(false), repository.admit(f.access, null))
    assertEquals(AppResult.Success(true), repository.admit(f.access, saved.revision))
    assertNull((repository.admission(f.access) as AppResult.Success).value.denial)
}

suspend fun checkSelfProfileReadDenialCannotBeClearedByAnOlderPendingAdmission(
    f: SelfProfileReplicaFixture
) = coroutineScope {
    f.publish(listOf(f.record))
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
                if (snapshot == null && partition == selfProfileAdmissionPartition(f.access)) {
                    entered.complete(Unit)
                    release.await()
                }
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedSelfProfileReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    repository.restrict(f.access, denial)
    val first = (repository.admission(f.access) as AppResult.Success).value
    val old = async { repository.admit(f.access, first.revision) }
    entered.await()
    val later = async(start = CoroutineStart.UNDISPATCHED) { repository.restrict(f.access, denial) }
    val next = (repository.admission(f.access) as AppResult.Success).value
    assertNotEquals(first.revision, next.revision)
    release.complete(Unit)
    assertEquals(AppResult.Success(false), old.await())
    assertEquals(AppResult.Success(true), later.await())
    assertEquals(next, (repository.admission(f.access) as AppResult.Success).value)
    assertEquals(AppResult.Success(false), repository.admit(f.access, first.revision))
    assertEquals(AppResult.Success(true), repository.admit(f.access, next.revision))
}
