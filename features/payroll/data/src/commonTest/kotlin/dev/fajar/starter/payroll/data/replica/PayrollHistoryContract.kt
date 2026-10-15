package dev.fajar.starter.payroll.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.replica.encoding.*
import dev.fajar.starter.payroll.data.replica.mappers.*
import dev.fajar.starter.payroll.data.replica.repositories.EncryptedPayslipReplicaRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.replica.policies.initialPayrollCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

suspend fun checkPayrollHistorySurvivesReplicaReplacementRestartAndSameAccountLogin(
    f: PayrollReplicaFixture
) {
    val original = f.slip.value
    val second = original.copy(summary = original.summary.copy(version = 2))
    assertEquals(
        AppResult.Success(null),
        f.repository.observeRevision(f.access, original.summary.id, 0).first(),
    )
    assertNull(f.keys.read())
    assertEquals(AppResult.Success(true), f.repository.retainRevision(f.access, original))
    assertEquals(AppResult.Success(true), f.repository.retainRevision(f.access, second))
    f.publish(listOf(f.slip.copy(value = second), f.payment))
    val current = f.checkpoint()!!
    val replacement = initialPayrollCheckpoint(f.access, "replacement-history", f.raw.now)
    assertEquals(AppResult.Success(true), f.repository.begin(replacement, current.generation))
    f.publish(listOf(f.slip.copy(value = second), f.payment), publish = false)
    f.reopen()
    assertEquals(
        AppResult.Success(original),
        f.repository.observeRevision(f.access, original.summary.id, 0).first(),
    )
    f.publish(emptyList())
    val scope = payrollReplicaScope(f.access)
    val raw =
        f.db.replicas.document(
            scope,
            payrollRevisionPartition(f.access),
            payrollRevisionRecordId(original.summary.id, 0),
        )!!
    assertTrue(raw.tags.isEmpty())
    assertFalse(raw.payload!!.contains(original.summary.takeHome.toString()))
    val renewed =
        f.access.copy(
            sessionId = "renewed",
            sessionExpiresAt = f.raw.now - 90.days,
            authorizationExpiresAt = f.raw.now - 90.days,
        )
    f.activate(renewed)
    assertEquals(
        AppResult.Success(original),
        f.repository.observeRevision(renewed, original.summary.id, 0).first(),
    )
    assertEquals(
        AppResult.Success(second),
        f.repository.observeRevision(renewed, original.summary.id, 2).first(),
    )
    assertEquals(
        AppResult.Success(null),
        f.repository
            .observeRevision(
                renewed.copy(companyId = payrollFixtureId(990)),
                original.summary.id,
                0,
            )
            .first(),
    )
    for (other in
        listOf(
            renewed.copy(accountId = payrollFixtureId(991)),
            renewed.copy(audience = "https://other.example.test/"),
        )) {
        f.activate(other)
        assertEquals(
            AppResult.Success(null),
            f.repository.observeRevision(other, original.summary.id, 0).first(),
        )
    }
    f.activate(renewed)
    assertEquals(
        AppResult.Success(original),
        f.repository.observeRevision(renewed, original.summary.id, 0).first(),
    )
}

suspend fun checkPayrollHistoryIsImmutableAndDoesNotChangeCurrentDataOrCursor(
    f: PayrollReplicaFixture
) {
    f.publish(listOf(f.slip, f.payment))
    val before = f.details()
    val first = f.slip.value.copy(summary = f.slip.value.summary.copy(version = 1))
    assertEquals(AppResult.Success(true), f.repository.retainRevision(f.access, first))
    val scope = payrollReplicaScope(f.access)
    val partition = payrollRevisionPartition(f.access)
    val saved = f.db.replicas.checkpoint(scope, partition)
    assertEquals(AppResult.Success(true), f.repository.retainRevision(f.access, first))
    assertEquals(saved, f.db.replicas.checkpoint(scope, partition))
    assertEquals(
        FailureKind.Storage,
        assertIs<AppResult.Failed>(
                f.repository.retainRevision(
                    f.access,
                    first.copy(companyName = "Different statement"),
                )
            )
            .failure
            .kind,
    )
    assertEquals(saved, f.db.replicas.checkpoint(scope, partition))
    assertEquals(
        AppResult.Success(first),
        f.repository.observeRevision(f.access, first.summary.id, 1).first(),
    )
    assertEquals(before, f.details())
    assertEquals(AppResult.Success(true), f.repository.clear(f.access))
    assertEquals(
        AppResult.Success(null),
        f.repository.observeRevision(f.access, first.summary.id, 1).first(),
    )
    assertNull(f.details().view)
}

suspend fun checkPayrollHistoryAuthenticatesRecordsAndCheckpoint(f: PayrollReplicaFixture) {
    val slip = f.slip.value
    f.repository.retainRevision(f.access, slip)
    for (component in
        listOf(
            "payload",
            "id",
            "version",
            "sort",
            "tags",
            "checkpoint-payload",
            "checkpoint-generation",
            "checkpoint-revision",
            "checkpoint-partition",
        )) {
        val raw =
            object : ReplicaStore by f.db.replicas {
                override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
                    f.db.replicas.observeDocument(scope, partition, id).map { page ->
                        val checkpoint = page.checkpoint!!
                        val changedCheckpoint =
                            when (component) {
                                "checkpoint-payload" ->
                                    checkpoint.copy(payload = Base64.encode(ByteArray(32)))
                                "checkpoint-generation" ->
                                    checkpoint.copy(
                                        handle = checkpoint.handle.copy(generation = "other")
                                    )
                                "checkpoint-revision" ->
                                    checkpoint.copy(revision = checkpoint.revision + 1)
                                "checkpoint-partition" ->
                                    checkpoint.copy(
                                        handle = checkpoint.handle.copy(partition = "other")
                                    )
                                else -> checkpoint
                            }
                        page.copy(
                            checkpoint = changedCheckpoint,
                            documents =
                                page.documents.map { row ->
                                    when (component) {
                                        "payload" ->
                                            row.copy(payload = Base64.encode(ByteArray(32)))
                                        "id" -> row.copy(id = row.id + "x")
                                        "version" -> row.copy(version = row.version + 1)
                                        "sort" -> row.copy(sortKey = row.sortKey + "x")
                                        "tags" -> row.copy(tags = setOf("unexpected"))
                                        else -> row
                                    }
                                },
                        )
                    }
            }
        val repository = EncryptedPayslipReplicaRepository(raw, f.cipher, f.keys, f.admissions)
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(
                    repository.observeRevision(f.access, slip.summary.id, 0).first()
                )
                .failure
                .kind,
            component,
        )
    }
}

suspend fun checkPayrollHistoryAlonePreventsRegeneratingAMissingKey(f: PayrollReplicaFixture) {
    val slip = f.slip.value
    assertEquals(AppResult.Success(true), f.repository.retainRevision(f.access, slip))
    assertNull(f.checkpoint())
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.observeRevision(f.access, slip.summary.id, 0).first())
    assertIs<AppResult.Failed>(
        f.repository.retainRevision(f.access, slip.copy(summary = slip.summary.copy(version = 1)))
    )
    assertIs<AppResult.Failed>(
        f.repository.begin(initialPayrollCheckpoint(f.access, "replacement", f.raw.now), null)
    )
    assertNull(f.keys.read())
}

suspend fun checkPayrollHistoryCancellationAndOwnerReplacementCannotPublish(
    f: PayrollReplicaFixture
) = coroutineScope {
    f.repository.retainRevision(f.access, f.slip.value)
    for (cancel in listOf(false, true)) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val keys = mutableListOf<ByteArray>()
        val plaintexts = mutableListOf<ByteArray>()
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    keys += key
                    plaintexts += plaintext
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    return f.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        val repository =
            EncryptedPayslipReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions)
        val pending = async {
            repository.retainRevision(
                f.access,
                f.slip.value.copy(summary = f.slip.value.summary.copy(version = 1)),
            )
        }
        entered.await()
        if (cancel) pending.cancel() else f.db.accounts.activate("replacement")
        release.complete(Unit)
        if (cancel) assertFailsWith<CancellationException> { pending.await() }
        else assertEquals(AppResult.Success(false), pending.await())
        f.activate()
        assertEquals(
            AppResult.Success(null),
            f.repository.observeRevision(f.access, f.slip.id, 1).first(),
        )
        assertTrue(keys.isNotEmpty() && keys.all { key -> key.all { it == 0.toByte() } })
        assertTrue(plaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
    }
}

suspend fun checkPayrollHistoryRejectsObsoleteCommitsAndSerializesCleanup(
    f: PayrollReplicaFixture
) = coroutineScope {
    f.repository.retainRevision(f.access, f.slip.value)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val cipher =
        object : ContentCipher by f.cipher {
            override suspend fun encrypt(
                key: ByteArray,
                associatedData: ByteArray,
                plaintext: ByteArray,
            ): ByteArray {
                entered.complete(Unit)
                release.await()
                return f.cipher.encrypt(key, associatedData, plaintext)
            }
        }
    val repository = EncryptedPayslipReplicaRepository(f.db.replicas, cipher, f.keys, f.admissions)
    val pending = async {
        repository.retainRevision(
            f.access,
            f.slip.value.copy(summary = f.slip.value.summary.copy(version = 1)),
        )
    }
    entered.await()
    // Another application owner advances the archive while this write holds an older checkpoint.
    assertEquals(
        AppResult.Success(true),
        f.repository.retainRevision(
            f.access,
            f.slip.value.copy(summary = f.slip.value.summary.copy(version = 2)),
        ),
    )
    val clearing = async { repository.clear(f.access) }
    yield()
    assertFalse(clearing.isCompleted)
    release.complete(Unit)
    assertEquals(AppResult.Success(false), pending.await())
    assertEquals(AppResult.Success(true), clearing.await())
    assertEquals(
        AppResult.Success(null),
        repository.observeRevision(f.access, f.slip.id, 0).first(),
    )
    assertEquals(
        AppResult.Success(null),
        repository.observeRevision(f.access, f.slip.id, 2).first(),
    )
}
