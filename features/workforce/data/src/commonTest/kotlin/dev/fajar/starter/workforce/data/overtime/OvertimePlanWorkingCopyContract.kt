package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaScope
import dev.fajar.starter.workforce.data.overtime.working.encoding.overtimePlanWorkingPartition
import dev.fajar.starter.workforce.data.overtime.working.repositories.EncryptedOvertimePlanWorkingCopyRepository
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

fun workingOvertimePlan(f: OvertimeReplicaFixture) =
    OvertimePlanWorkingCopy(
        f.raw.request.employeeId,
        OvertimePlanWorkingInput(
            LocalDate(2026, 10, 15),
            endDate = LocalDate(2026, 10, 16),
            startsAt = kotlinx.datetime.LocalTime(23, 30),
            breakMinutes = "",
            reason = "Unfinished input\n猫",
        ),
        overtimeReplicaId(101),
        f.raw.base.now,
    )

suspend fun checkOvertimePlanWorkingCopySurvivesFactCleanupAndLogin(f: OvertimeReplicaFixture) {
    val copies =
        EncryptedOvertimePlanWorkingCopyRepository(f.db.replicas, f.cipher, f.keys, f.keyCreation)
    val copy = workingOvertimePlan(f)
    assertEquals(AppResult.Success(copy), copies.save(f.access, null, copy))
    val raw =
        requireNotNull(
            f.db.replicas.checkpoint(
                overtimeReplicaScope(f.access),
                overtimePlanWorkingPartition(f.access, copy.employeeId),
            )
        )
    assertFalse(raw.payload.contains(copy.input.reason))
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    val login = f.access.copy(sessionId = "new-login")
    f.db.accounts.activate(login.sessionId, overtimeReplicaScope(login).accountKey)
    val restored =
        EncryptedOvertimePlanWorkingCopyRepository(f.db.replicas, f.cipher, f.keys, f.keyCreation)
    assertEquals(AppResult.Success(copy), restored.observe(login, copy.employeeId).first())
    assertEquals(AppResult.Success(OvertimePage(listOf(copy))), restored.observePage(login).first())
    assertEquals(AppResult.Success(null), restored.observe(login, overtimeReplicaId(909)).first())
    val late = copy.copy(revision = overtimeReplicaId(102))
    assertEquals(AppResult.Success(null), copies.save(f.access, copy.revision, late))
    assertEquals(AppResult.Success(copy), restored.observe(login, copy.employeeId).first())
}

suspend fun checkOvertimePlanWorkingAcknowledgementAndCompetingEdits(f: OvertimeReplicaFixture) {
    var loseAcknowledgement = true
    val store =
        object : ReplicaStore by f.db.replicas {
            override suspend fun replace(
                scope: ReplicaScope,
                partition: String,
                expected: ReplicaCheckpoint?,
                snapshot: ReplicaSnapshot?,
            ): Boolean {
                val committed = f.db.replicas.replace(scope, partition, expected, snapshot)
                if (committed && loseAcknowledgement) {
                    loseAcknowledgement = false
                    error("Fixture acknowledgement lost")
                }
                return committed
            }
        }
    val copies = EncryptedOvertimePlanWorkingCopyRepository(store, f.cipher, f.keys, f.keyCreation)
    val original = workingOvertimePlan(f)
    assertIs<AppResult.Failed>(copies.save(f.access, null, original))
    assertEquals(
        AppResult.Success(original),
        copies.save(f.access, null, original.copy(savedAt = original.savedAt + 3.days)),
    )
    assertEquals(
        AppResult.Success(null),
        copies.save(
            f.access,
            null,
            original.copy(input = original.input.copy(reason = "Different input")),
        ),
    )
    val newer =
        original.copy(
            revision = overtimeReplicaId(103),
            input = original.input.copy(reason = "Newer edit"),
        )
    assertEquals(AppResult.Success(newer), copies.save(f.access, original.revision, newer))
    assertEquals(AppResult.Success(null), copies.save(f.access, null, original))
    assertEquals(
        AppResult.Success(false),
        copies.remove(f.access, original.employeeId, original.revision),
    )
    assertEquals(AppResult.Success(newer), copies.observe(f.access, original.employeeId).first())
    assertEquals(AppResult.Success(true), copies.remove(f.access, newer.employeeId, newer.revision))
}

suspend fun checkOvertimePlanWorkingCipherBindsCompanyAndEmployee(f: OvertimeReplicaFixture) {
    val copies =
        EncryptedOvertimePlanWorkingCopyRepository(f.db.replicas, f.cipher, f.keys, f.keyCreation)
    val copy = workingOvertimePlan(f)
    copies.save(f.access, null, copy)
    val raw =
        requireNotNull(
            f.db.replicas.checkpoint(
                overtimeReplicaScope(f.access),
                overtimePlanWorkingPartition(f.access, copy.employeeId),
            )
        )
    val anotherCompany = f.access.copy(companyId = overtimeReplicaId(801))
    for ((owner, employee) in
        listOf(anotherCompany to copy.employeeId, f.access to overtimeReplicaId(802))) {
        assertTrue(
            f.db.replicas.replace(
                overtimeReplicaScope(owner),
                overtimePlanWorkingPartition(owner, employee),
                null,
                ReplicaSnapshot(raw.handle.generation, raw.payload, emptyList()),
            )
        )
        assertIs<AppResult.Failed>(copies.observe(owner, employee).first())
    }
    f.keys.write(null)
    assertIs<AppResult.Failed>(
        copies.save(f.access, copy.revision, copy.copy(revision = overtimeReplicaId(104)))
    )
    assertNull(f.keys.read())
    assertEquals(
        raw,
        f.db.replicas.checkpoint(
            overtimeReplicaScope(f.access),
            overtimePlanWorkingPartition(f.access, copy.employeeId),
        ),
    )
}

suspend fun checkCancelledOvertimePlanWorkingEncryptionCannotPublish(f: OvertimeReplicaFixture) =
    coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var inputBytes: ByteArray? = null
        var keyBytes: ByteArray? = null
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray =
                    withContext(NonCancellable) {
                        inputBytes = plaintext
                        keyBytes = key
                        entered.complete(Unit)
                        release.await()
                        f.cipher.encrypt(key, associatedData, plaintext)
                    }
            }
        val copies =
            EncryptedOvertimePlanWorkingCopyRepository(f.db.replicas, cipher, f.keys, f.keyCreation)
        val copy = workingOvertimePlan(f)
        val saving = async { copies.save(f.access, null, copy) }
        try {
            entered.await()
            saving.cancel()
            release.complete(Unit)
            saving.join()
            assertTrue(saving.isCancelled)
            assertNull(
                f.db.replicas.checkpoint(
                    overtimeReplicaScope(f.access),
                    overtimePlanWorkingPartition(f.access, copy.employeeId),
                )
            )
            assertTrue(requireNotNull(inputBytes).all { it == 0.toByte() })
            assertTrue(requireNotNull(keyBytes).all { it == 0.toByte() })
        } finally {
            release.complete(Unit)
            saving.cancel()
        }
    }

suspend fun checkConcurrentOvertimePlanWritersShareOneContentKey(f: OvertimeReplicaFixture) =
    coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var generations = 0
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun generateKey(): ByteArray {
                    generations++
                    entered.complete(Unit)
                    release.await()
                    return f.cipher.generateKey()
                }
            }
        val copies =
            EncryptedOvertimePlanWorkingCopyRepository(f.db.replicas, cipher, f.keys, f.keyCreation)
        val reads =
            dev.fajar.starter.workforce.data.overtime.repositories
                .EncryptedOvertimeReplicaRepository(
                    f.db.replicas,
                    cipher,
                    f.keys,
                    f.legacyKeys,
                    f.admissions,
                    f.keyCreation,
                )
        val copy = workingOvertimePlan(f)
        val saving = async { copies.save(f.access, null, copy) }
        try {
            entered.await()
            val restricting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    reads.restrict(
                        f.access,
                        Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
                    )
                }
            release.complete(Unit)
            assertEquals(AppResult.Success(copy), saving.await())
            assertEquals(AppResult.Success(true), restricting.await())
            assertEquals(1, generations)
            assertEquals(AppResult.Success(copy), copies.observe(f.access, copy.employeeId).first())
            val restored =
                dev.fajar.starter.workforce.data.overtime.repositories
                    .EncryptedOvertimeReplicaRepository(
                        f.db.replicas,
                        f.cipher,
                        f.keys,
                        f.legacyKeys,
                        dev.fajar.starter.workforce.data.overtime.datasources
                            .MemoryOvertimeReadAdmissionDataSource(),
                        f.keyCreation,
                    )
            assertEquals(
                "mfa_required",
                assertIs<AppResult.Success<OvertimeReadAdmission>>(restored.admission(f.access))
                    .value
                    .denial
                    ?.code,
            )
        } finally {
            release.complete(Unit)
        }
    }
