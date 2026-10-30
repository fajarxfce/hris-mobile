package dev.fajar.starter.documents.data.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.repositories.EncryptedDocumentMetadataAdmissionRepository
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import kotlin.test.*
import kotlinx.coroutines.*

private val metadataDenial =
    Failure(FailureKind.AccessDenied, "PRIVATE-DETAIL", code = "mfa_required")

suspend fun checkMetadataAdmissionRestartAndResourceIsolation(f: DocumentMetadataStorageFixture) {
    f.append(2)
    assertEquals(AppResult.Success(true), f.admissions.restrict(f.access, f.id, metadataDenial))
    val stored =
        assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                f.admissions.current(f.access, f.id)
            )
            .value
    assertEquals("mfa_required", stored.denial?.code)
    assertFalse(stored.toString().contains("PRIVATE-DETAIL"))
    val renewed = f.access.copy(sessionId = "renewed")
    f.reopen(renewed)
    assertEquals(
        stored,
        assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                f.admissions.current(renewed, f.id)
            )
            .value,
    )
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                f.admissions.current(renewed, renewed.companyId)
            )
            .value
            .denial
    )
    assertEquals(AppResult.Success(false), f.admissions.admit(renewed, f.id, null))
    assertEquals(AppResult.Success(true), f.admissions.admit(renewed, f.id, stored.revision))
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                f.admissions.current(renewed, f.id)
            )
            .value
            .denial
    )
    assertEquals(3, f.snapshot(owner = renewed).history.items.size)
}

suspend fun checkMetadataPendingAndFailedAdmissionWritesRemainClosed(
    f: DocumentMetadataStorageFixture
) = coroutineScope {
    val entered = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    val failing =
        object : ReplicaStore by f.db.replicas {
            override suspend fun replace(
                scope: ReplicaScope,
                partition: String,
                expected: ReplicaCheckpoint?,
                snapshot: ReplicaSnapshot?,
            ): Boolean {
                entered.complete(Unit)
                resume.await()
                error("fixture storage unavailable")
            }
        }
    val admissions =
        EncryptedDocumentMetadataAdmissionRepository(failing, f.cipher, f.keys, f.buffer)
    val task = async { admissions.restrict(f.access, f.id, metadataDenial) }
    entered.await()
    val retained =
        assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                admissions.current(f.access, f.id)
            )
            .value
    assertEquals("mfa_required", retained.denial?.code)
    resume.complete(Unit)
    assertIs<AppResult.Failed>(task.await())
    assertEquals(
        retained,
        assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                admissions.current(f.access, f.id)
            )
            .value,
    )
    val renewed = f.access.copy(sessionId = "renewed")
    f.activate(renewed)
    assertEquals(
        retained,
        assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                f.admissions.current(renewed, f.id)
            )
            .value,
    )
    assertEquals(AppResult.Success(true), f.admissions.admit(renewed, f.id, retained.revision))
}

suspend fun checkMetadataOlderAdmissionCannotClearLaterDenial(f: DocumentMetadataStorageFixture) =
    coroutineScope {
        assertEquals(AppResult.Success(true), f.admissions.restrict(f.access, f.id, metadataDenial))
        val first =
            assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                    f.admissions.current(f.access, f.id)
                )
                .value
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val pending =
            object : ReplicaStore by f.db.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    if (snapshot == null) {
                        entered.complete(Unit)
                        resume.await()
                    }
                    return f.db.replicas.replace(scope, partition, expected, snapshot)
                }
            }
        val admissions =
            EncryptedDocumentMetadataAdmissionRepository(pending, f.cipher, f.keys, f.buffer)
        val task = async { admissions.admit(f.access, f.id, first.revision) }
        entered.await()
        assertEquals(
            AppResult.Success(true),
            f.admissions.restrict(
                f.access,
                f.id,
                metadataDenial.copy(code = "document_access_denied"),
            ),
        )
        resume.complete(Unit)
        assertEquals(AppResult.Success(false), task.await())
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Success<DocumentMetadataReadAdmission>>(
                    admissions.current(f.access, f.id)
                )
                .value
                .denial
                ?.code,
        )
    }
