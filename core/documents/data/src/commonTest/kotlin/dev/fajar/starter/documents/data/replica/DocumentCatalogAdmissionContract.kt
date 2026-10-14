package dev.fajar.starter.documents.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.replica.datasources.MemoryDocumentCatalogReadAdmissionDataSource
import dev.fajar.starter.documents.data.replica.encoding.documentCatalogAdmissionPartition
import dev.fajar.starter.documents.data.replica.mappers.documentCatalogReplicaScope
import dev.fajar.starter.documents.data.replica.repositories.EncryptedDocumentCatalogRepository
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogReadAdmission
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun checkCatalogPendingDenialSurvivesReauthentication(f: DocumentCatalogFixture) {
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
    val repository = EncryptedDocumentCatalogRepository(store, f.cipher, f.keys, f.admissions)
    assertIs<AppResult.Failed>(
        repository.restrict(
            f.access,
            Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
        )
    )
    val next = f.access.copy(sessionId = "new-login")
    f.activate(next)
    val admission =
        assertIs<AppResult.Success<DocumentCatalogReadAdmission>>(repository.admission(next)).value
    assertEquals("mfa_required", admission.denial!!.code)
    assertEquals(listOf(f.record.evidence), f.snapshot(next).page.items)
    assertEquals(AppResult.Success(true), f.repository.admit(next, admission.revision))
    assertNull(
        assertIs<AppResult.Success<DocumentCatalogReadAdmission>>(repository.admission(next))
            .value
            .denial
    )
}

suspend fun checkCatalogReadDenialSurvivesRepositoryAndSessionReplacement(
    f: DocumentCatalogFixture
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
        assertIs<AppResult.Success<DocumentCatalogReadAdmission>>(f.repository.admission(f.access))
            .value
    assertEquals("mfa_required", saved.denial!!.code)
    assertNotEquals(denial.message, saved.denial!!.message)
    assertNotNull(saved.revision)
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    val restored =
        EncryptedDocumentCatalogRepository(
            f.db.replicas,
            f.cipher,
            f.keys,
            MemoryDocumentCatalogReadAdmissionDataSource(),
        )
    assertEquals(saved, (restored.admission(f.access) as AppResult.Success).value)
    val nextLogin = f.access.copy(sessionId = "new-login")
    f.db.accounts.activate(nextLogin.sessionId, documentCatalogReplicaScope(nextLogin).accountKey)
    assertEquals(saved, (restored.admission(nextLogin) as AppResult.Success).value)
    assertEquals(AppResult.Success(true), restored.admit(nextLogin, saved.revision))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertEquals(AppResult.Success(false), f.repository.restrict(f.access, denial))
    assertNull((restored.admission(nextLogin) as AppResult.Success).value.denial)
    assertNull(
        (restored.admission(nextLogin.copy(companyId = catalogFixtureId(990))) as AppResult.Success)
            .value
            .denial
    )
}

suspend fun checkCatalogReadDenialRemainsVisibleWhenStorageFails(f: DocumentCatalogFixture) {
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
                if (unavailable && partition == documentCatalogAdmissionPartition(f.access))
                    error("Fixture storage failure")
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedDocumentCatalogRepository(store, f.cipher, f.keys, f.admissions)
    val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
    assertIs<AppResult.Failed>(repository.restrict(f.access, denial))
    val saved =
        assertIs<AppResult.Success<DocumentCatalogReadAdmission>>(
                repository.observeAdmission(f.access).first()
            )
            .value
    assertEquals("mfa_required", saved.denial!!.code)
    assertIs<AppResult.Failed>(repository.admit(f.access, saved.revision))
    assertEquals(saved, (repository.admission(f.access) as AppResult.Success).value)
    assertEquals(listOf(f.record.evidence), f.snapshot().page.items)
    unavailable = false
    assertEquals(AppResult.Success(false), repository.admit(f.access, null))
    assertEquals(AppResult.Success(true), repository.admit(f.access, saved.revision))
    assertNull((repository.admission(f.access) as AppResult.Success).value.denial)
}

suspend fun checkCatalogReadDenialCannotBeClearedByAnOlderPendingAdmission(
    f: DocumentCatalogFixture
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
                if (snapshot == null && partition == documentCatalogAdmissionPartition(f.access)) {
                    entered.complete(Unit)
                    release.await()
                }
                return f.db.replicas.replace(scope, partition, expected, snapshot)
            }
        }
    val repository = EncryptedDocumentCatalogRepository(store, f.cipher, f.keys, f.admissions)
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
