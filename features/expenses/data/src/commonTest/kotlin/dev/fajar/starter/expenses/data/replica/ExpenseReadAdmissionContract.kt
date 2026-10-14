package dev.fajar.starter.expenses.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.mappers.toSummary
import dev.fajar.starter.expenses.data.replica.datasources.MemoryExpenseReadAdmissionDataSource
import dev.fajar.starter.expenses.data.replica.encoding.expenseAdmissionPartition
import dev.fajar.starter.expenses.data.replica.mappers.expenseReplicaScope
import dev.fajar.starter.expenses.data.replica.repositories.EncryptedExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.replica.entities.ExpenseReadAdmission
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun checkExpenseReadDenialSurvivesRepositoryAndSessionReplacement(
    f: ExpenseReplicaFixture
) {
    f.publish(listOf(f.claim, f.payment))
    val denial =
        Failure(
            FailureKind.AccessDenied,
            "Private diagnostics must not be stored.",
            code = "mfa_required",
        )
    assertEquals(AppResult.Success(true), f.repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<ExpenseReadAdmission>>(f.repository.admission(f.access)).value
    assertEquals("mfa_required", saved.denial!!.code)
    assertNotEquals(denial.message, saved.denial!!.message)
    assertNotNull(saved.revision)
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    val restored =
        EncryptedExpenseReplicaRepository(
            f.db.replicas,
            f.cipher,
            f.keys,
            MemoryExpenseReadAdmissionDataSource(),
        )
    assertEquals(saved, (restored.admission(f.access) as AppResult.Success).value)
    val nextLogin = f.access.copy(sessionId = "new-login")
    f.db.accounts.activate(nextLogin.sessionId, expenseReplicaScope(nextLogin).accountKey)
    assertEquals(saved, (restored.admission(nextLogin) as AppResult.Success).value)
    assertEquals(AppResult.Success(true), restored.admit(nextLogin, saved.revision))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertEquals(AppResult.Success(false), f.repository.restrict(f.access, denial))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertNull(
        (restored.admission(nextLogin.copy(companyId = expenseFixtureId(990))) as AppResult.Success)
            .value
            .denial
    )
}

suspend fun checkExpenseReadDenialRemainsVisibleWhenStorageFails(f: ExpenseReplicaFixture) {
    f.publish(listOf(f.claim, f.payment))
    var unavailable = true
    val store =
        object : ReplicaStore by f.db.replicas {
            override suspend fun replace(
                scope: ReplicaScope,
                partition: String,
                expected: ReplicaCheckpoint?,
                snapshot: ReplicaSnapshot?,
            ): Boolean {
                if (unavailable && partition == expenseAdmissionPartition(f.access))
                    error("Fixture storage failure")
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedExpenseReplicaRepository(store, f.cipher, f.keys, f.admissions)
    val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    assertIs<AppResult.Failed>(repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<ExpenseReadAdmission>>(
                repository.observeAdmission(f.access).first()
            )
            .value
    assertEquals("mfa_required", saved.denial!!.code)
    assertIs<AppResult.Failed>(repository.admit(f.access, saved.revision))
    assertEquals(saved, (repository.admission(f.access) as AppResult.Success).value)
    assertEquals(listOf(f.raw.summary.toSummary()), f.overview().page.items)
    unavailable = false
    assertEquals(AppResult.Success(false), repository.admit(f.access, null))
    assertEquals(AppResult.Success(true), repository.admit(f.access, saved.revision))
    assertNull((repository.admission(f.access) as AppResult.Success).value.denial)
}

suspend fun checkExpenseReadDenialCannotBeClearedByAnOlderPendingAdmission(
    f: ExpenseReplicaFixture
) = coroutineScope {
    f.publish(listOf(f.claim, f.payment))
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
                if (snapshot == null && partition == expenseAdmissionPartition(f.access)) {
                    entered.complete(Unit)
                    release.await()
                }
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedExpenseReplicaRepository(store, f.cipher, f.keys, f.admissions)
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
