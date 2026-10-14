@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.ExpenseDataFixture
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.replica.datasources.MemoryExpenseReadAdmissionDataSource
import dev.fajar.starter.expenses.data.replica.mappers.*
import dev.fajar.starter.expenses.data.replica.repositories.EncryptedExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.policies.initialExpenseCheckpoint
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.datetime.YearMonth

class ExpenseReplicaFixture(private val openDatabase: () -> AppDatabase) {
    var db = openDatabase()
        private set

    val raw = ExpenseDataFixture()
    val access = raw.access
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var admissions = MemoryExpenseReadAdmissionDataSource()
        private set

    var repository = EncryptedExpenseReplicaRepository(db.replicas, cipher, keys, admissions)
        private set

    var directory = raw.directory.toReplicaDirectory(access)
    val claim =
        ExpenseRecord.Claim(
            ExpenseSnapshot(
                access,
                Uuid.random().toString(),
                ExpenseClaimRecordDto(raw.claim, null, raw.history).toRecord(raw.claim.id, null),
                raw.now,
                raw.now + 1.hours,
            )
        )
    val payment = ExpenseRecord.Payments(ExpenseClaimPayments(raw.claim.id, 0, emptyList()))
    val selection = ExpenseSelection(raw.employee.id, YearMonth(2026, 10))

    suspend fun activate(owner: ExpenseAccess = access) =
        db.accounts.activate(owner.sessionId, expenseReplicaScope(owner).accountKey)

    suspend fun checkpoint(owner: ExpenseAccess = access) =
        assertIs<AppResult.Success<ExpenseCheckpoint?>>(repository.checkpoint(owner)).value

    suspend fun publish(records: List<ExpenseRecord>, publish: Boolean = true) {
        var current = checkpoint()
        if (current == null) {
            current = initialExpenseCheckpoint(access, Uuid.random().toString(), raw.now)
            assertEquals(AppResult.Success(true), repository.begin(current, null))
        }
        val batches = records.chunked(4).ifEmpty { listOf(emptyList()) }
        for ((index, batch) in batches.withIndex()) {
            val previous = requireNotNull(current)
            val visible = previous.published || (publish && index == batches.lastIndex)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = ExpenseSyncPhase.CHANGES,
                    cursor = "head",
                    published = visible,
                    refreshedAt = if (visible) raw.now else null,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(previous, next, directory, batch.map(::expenseTestEntry)),
            )
            current = next
        }
    }

    suspend fun overview(selected: ExpenseSelection = selection, owner: ExpenseAccess = access) =
        assertIs<AppResult.Success<ExpenseReplicaPage>>(repository.observe(owner, selected).first())
            .value

    suspend fun details(
        id: String = raw.claim.id,
        owner: ExpenseAccess = access,
        after: Long? = null,
    ) =
        assertIs<AppResult.Success<ExpenseReplicaDetails>>(
                repository.observeDetails(owner, id, after).first()
            )
            .value

    suspend fun reopen(owner: ExpenseAccess = access) {
        db.close()
        db = openDatabase()
        admissions = MemoryExpenseReadAdmissionDataSource()
        repository = EncryptedExpenseReplicaRepository(db.replicas, cipher, keys, admissions)
        activate(owner)
    }

    fun close() {
        db.close()
        keys.close()
    }
}

fun expenseTestEntry(record: ExpenseRecord): ExpenseReplicaEntry =
    when (record) {
        is ExpenseRecord.Claim ->
            ExpenseReplicaEntry(
                SyncReference(
                    SyncCollection.EXPENSE_CLAIMS,
                    record.snapshot.value.claim.id,
                    record.snapshot.value.claim.version,
                ),
                record,
            )
        is ExpenseRecord.Payments ->
            ExpenseReplicaEntry(
                SyncReference(
                    SyncCollection.EXPENSE_PAYMENTS,
                    record.progress.claimId,
                    record.progress.version,
                ),
                record,
            )
    }

fun expenseFixtureId(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

fun largeExpenseClaimRecord(f: ExpenseReplicaFixture): ExpenseRecord.Claim {
    val largeDto =
        f.raw.claim.copy(
            version = 19,
            draft =
                f.raw.draft.copy(
                    totalAmount = "0.20",
                    description = "測".repeat(2000),
                    lines =
                        List(20) {
                            f.raw.line.copy(
                                id = expenseFixtureId(100 + it),
                                amount = "0.01",
                                description = "測".repeat(500),
                            )
                        },
                ),
        )
    val history =
        ExpensePageDto(
            List(20) { f.raw.change.copy(version = 19L - it, reason = "測".repeat(1000)) },
            null,
        )
    return ExpenseRecord.Claim(
        f.claim.snapshot.copy(
            generation = Uuid.random().toString(),
            value = ExpenseClaimRecordDto(largeDto, null, history).toRecord(f.raw.claim.id, null),
        )
    )
}

fun smallExpenseClaimRecord(f: ExpenseReplicaFixture): ExpenseRecord.Claim {
    return ExpenseRecord.Claim(
        f.claim.snapshot.copy(
            generation = Uuid.random().toString(),
            value =
                ExpenseClaimRecordDto(
                        f.raw.claim.copy(version = 19),
                        null,
                        ExpensePageDto(listOf(f.raw.change.copy(version = 19)), null),
                    )
                    .toRecord(f.raw.claim.id, null),
        )
    )
}
