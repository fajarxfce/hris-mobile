package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaScope
import dev.fajar.starter.workforce.data.overtime.working.encoding.overtimeActionWorkingPartition
import dev.fajar.starter.workforce.data.overtime.working.repositories.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime

fun workingOvertimeAction(f: OvertimeReplicaFixture) =
    OvertimeActionWorkingCopy(
        f.raw.request.id,
        f.raw.request.employeeId,
        OvertimeAction.SUBMIT_ACTUAL,
        OvertimeActionWorkingInput(
            0,
            OvertimeActualWorkingInput(
                LocalDateTime(2026, 10, 15, 23, 30),
                LocalDateTime(2026, 10, 16, 1, 0),
                "",
            ),
            "Unfinished input\n猫",
        ),
        overtimeReplicaId(201),
        f.raw.base.now,
    )

fun actionWorkingSelection(copy: OvertimeActionWorkingCopy) =
    OvertimeRequestSelection(copy.requestId, copy.employeeId)

suspend fun checkOvertimeActionWorkingCopySurvivesFactCleanupAndLogin(f: OvertimeReplicaFixture) {
    val copies =
        EncryptedOvertimeActionWorkingCopyRepository(f.db.replicas, f.cipher, f.keys, f.keyCreation)
    val copy = workingOvertimeAction(f)
    val other =
        copy.copy(
            action = OvertimeAction.WITHDRAW,
            input = copy.input.copy(actual = null, reason = "Other draft"),
        )
    assertEquals(AppResult.Success(copy), copies.save(f.access, null, copy))
    assertEquals(AppResult.Success(other), copies.save(f.access, null, other))
    val raw =
        requireNotNull(
            f.db.replicas.checkpoint(
                overtimeReplicaScope(f.access),
                overtimeActionWorkingPartition(f.access, actionWorkingSelection(copy), copy.action),
            )
        )
    assertFalse(raw.payload.contains(copy.input.reason))
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    val login = f.access.copy(sessionId = "new-login")
    f.db.accounts.activate(login.sessionId, overtimeReplicaScope(login).accountKey)
    val restored =
        EncryptedOvertimeActionWorkingCopyRepository(f.db.replicas, f.cipher, f.keys, f.keyCreation)
    for (record in listOf(copy, other)) assertEquals(
        AppResult.Success(record),
        restored.observe(login, actionWorkingSelection(record), record.action).first(),
    )
    val late = copy.copy(revision = overtimeReplicaId(202))
    assertEquals(AppResult.Success(null), copies.save(f.access, copy.revision, late))
    assertEquals(
        AppResult.Success(copy),
        restored.observe(login, actionWorkingSelection(copy), copy.action).first(),
    )
}

suspend fun checkOvertimeActionWorkingAcknowledgementAndCompetingEdits(f: OvertimeReplicaFixture) {
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
    val copies =
        EncryptedOvertimeActionWorkingCopyRepository(store, f.cipher, f.keys, f.keyCreation)
    val original = workingOvertimeAction(f)
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
            revision = overtimeReplicaId(203),
            input = original.input.copy(reason = "Newer edit"),
        )
    assertEquals(AppResult.Success(newer), copies.save(f.access, original.revision, newer))
    assertEquals(AppResult.Success(null), copies.save(f.access, null, original))
    assertEquals(
        AppResult.Success(false),
        copies.remove(
            f.access,
            actionWorkingSelection(original),
            original.action,
            original.revision,
        ),
    )
    assertEquals(
        AppResult.Success(newer),
        copies.observe(f.access, actionWorkingSelection(original), original.action).first(),
    )
    assertEquals(
        AppResult.Success(true),
        copies.remove(f.access, actionWorkingSelection(newer), newer.action, newer.revision),
    )
}

suspend fun checkOvertimeActionWorkingCipherBindsScopeAndAction(f: OvertimeReplicaFixture) {
    val copies =
        EncryptedOvertimeActionWorkingCopyRepository(f.db.replicas, f.cipher, f.keys, f.keyCreation)
    val copy = workingOvertimeAction(f)
    copies.save(f.access, null, copy)
    val selection = actionWorkingSelection(copy)
    val raw =
        requireNotNull(
            f.db.replicas.checkpoint(
                overtimeReplicaScope(f.access),
                overtimeActionWorkingPartition(f.access, selection, copy.action),
            )
        )
    val anotherCompany = f.access.copy(companyId = overtimeReplicaId(801))
    for ((access, target, action) in
        listOf(
            Triple(anotherCompany, selection, copy.action),
            Triple(f.access, selection.copy(employeeId = overtimeReplicaId(802)), copy.action),
            Triple(f.access, selection.copy(requestId = overtimeReplicaId(803)), copy.action),
            Triple(f.access, selection, OvertimeAction.WITHDRAW),
        )) {
        assertTrue(
            f.db.replicas.replace(
                overtimeReplicaScope(access),
                overtimeActionWorkingPartition(access, target, action),
                null,
                ReplicaSnapshot(raw.handle.generation, raw.payload, emptyList()),
            )
        )
        assertIs<AppResult.Failed>(copies.observe(access, target, action).first())
    }
    f.keys.write(null)
    assertIs<AppResult.Failed>(
        copies.save(f.access, copy.revision, copy.copy(revision = overtimeReplicaId(204)))
    )
    assertNull(f.keys.read())
    assertEquals(
        raw,
        f.db.replicas.checkpoint(
            overtimeReplicaScope(f.access),
            overtimeActionWorkingPartition(f.access, selection, copy.action),
        ),
    )
}

suspend fun checkCancelledOvertimeActionWorkingEncryptionCannotPublish(f: OvertimeReplicaFixture) =
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
            EncryptedOvertimeActionWorkingCopyRepository(
                f.db.replicas,
                cipher,
                f.keys,
                f.keyCreation,
            )
        val copy = workingOvertimeAction(f)
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
                    overtimeActionWorkingPartition(
                        f.access,
                        actionWorkingSelection(copy),
                        copy.action,
                    ),
                )
            )
            assertTrue(requireNotNull(inputBytes).all { it == 0.toByte() })
            assertTrue(requireNotNull(keyBytes).all { it == 0.toByte() })
        } finally {
            release.complete(Unit)
            saving.cancel()
        }
    }

suspend fun checkConcurrentOvertimeActionWritersShareOneContentKey(f: OvertimeReplicaFixture) =
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
            EncryptedOvertimeActionWorkingCopyRepository(
                f.db.replicas,
                cipher,
                f.keys,
                f.keyCreation,
            )
        val plans =
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
        val copy = workingOvertimeAction(f)
        val saving = async { copies.save(f.access, null, copy) }
        try {
            entered.await()
            val plan =
                async(start = CoroutineStart.UNDISPATCHED) {
                    plans.save(f.access, null, workingOvertimePlan(f))
                }
            val restricting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    reads.restrict(
                        f.access,
                        Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
                    )
                }
            release.complete(Unit)
            assertEquals(AppResult.Success(copy), saving.await())
            assertIs<AppResult.Success<OvertimePlanWorkingCopy?>>(plan.await())
            assertEquals(AppResult.Success(true), restricting.await())
            assertEquals(1, generations)
            assertEquals(
                AppResult.Success(copy),
                copies.observe(f.access, actionWorkingSelection(copy), copy.action).first(),
            )
            assertEquals(
                "mfa_required",
                assertIs<AppResult.Success<OvertimeReadAdmission>>(reads.admission(f.access))
                    .value
                    .denial
                    ?.code,
            )
        } finally {
            release.complete(Unit)
        }
    }
