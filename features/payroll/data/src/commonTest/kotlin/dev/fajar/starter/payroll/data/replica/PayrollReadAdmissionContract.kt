package dev.fajar.starter.payroll.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.replica.datasources.MemoryPayrollReadAdmissionDataSource
import dev.fajar.starter.payroll.data.replica.encoding.payrollAdmissionPartition
import dev.fajar.starter.payroll.data.replica.mappers.payrollReplicaScope
import dev.fajar.starter.payroll.data.replica.repositories.EncryptedPayslipReplicaRepository
import dev.fajar.starter.payroll.domain.replica.entities.PayrollReadAdmission
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun checkPayrollReadDenialSurvivesRepositoryAndSessionReplacement(
    f: PayrollReplicaFixture
) {
    f.publish(listOf(f.slip, f.payment))
    val denial =
        Failure(
            FailureKind.AccessDenied,
            "Private diagnostics must not be stored.",
            code = "mfa_required",
        )
    assertEquals(AppResult.Success(true), f.repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<PayrollReadAdmission>>(f.repository.admission(f.access)).value
    assertEquals("mfa_required", saved.denial!!.code)
    assertNotEquals(denial.message, saved.denial!!.message)
    assertNotNull(saved.revision)
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    val restored =
        EncryptedPayslipReplicaRepository(
            f.db.replicas,
            f.cipher,
            f.keys,
            MemoryPayrollReadAdmissionDataSource(),
        )
    assertEquals(saved, (restored.admission(f.access) as AppResult.Success).value)
    val nextLogin = f.access.copy(sessionId = "new-login")
    f.db.accounts.activate(nextLogin.sessionId, payrollReplicaScope(nextLogin).accountKey)
    assertEquals(saved, (restored.admission(nextLogin) as AppResult.Success).value)
    assertEquals(AppResult.Success(true), restored.admit(nextLogin, saved.revision))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertEquals(AppResult.Success(false), f.repository.restrict(f.access, denial))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertNull(
        (restored.admission(nextLogin.copy(companyId = payrollFixtureId(990))) as AppResult.Success)
            .value
            .denial
    )
}

suspend fun checkPayrollReadDenialRemainsVisibleWhenStorageFails(f: PayrollReplicaFixture) {
    f.publish(listOf(f.slip, f.payment))
    var unavailable = true
    val store =
        object : ReplicaStore by f.db.replicas {
            override suspend fun replace(
                scope: ReplicaScope,
                partition: String,
                expected: ReplicaCheckpoint?,
                snapshot: ReplicaSnapshot?,
            ): Boolean {
                if (unavailable && partition == payrollAdmissionPartition(f.access))
                    error("Fixture storage failure")
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedPayslipReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    assertIs<AppResult.Failed>(repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<PayrollReadAdmission>>(
                repository.observeAdmission(f.access).first()
            )
            .value
    assertEquals("mfa_required", saved.denial!!.code)
    assertIs<AppResult.Failed>(repository.admit(f.access, saved.revision))
    assertEquals(saved, (repository.admission(f.access) as AppResult.Success).value)
    assertEquals(listOf(f.slip.value.summary), f.overview().page.items)
    unavailable = false
    assertEquals(AppResult.Success(false), repository.admit(f.access, null))
    assertEquals(AppResult.Success(true), repository.admit(f.access, saved.revision))
    assertNull((repository.admission(f.access) as AppResult.Success).value.denial)
}

suspend fun checkPayrollReadDenialCannotBeClearedByAnOlderPendingAdmission(
    f: PayrollReplicaFixture
) = coroutineScope {
    f.publish(listOf(f.slip, f.payment))
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
                if (snapshot == null && partition == payrollAdmissionPartition(f.access)) {
                    entered.complete(Unit)
                    release.await()
                }
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedPayslipReplicaRepository(store, f.cipher, f.keys, f.admissions)
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
