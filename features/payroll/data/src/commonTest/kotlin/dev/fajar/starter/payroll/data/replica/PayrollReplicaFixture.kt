package dev.fajar.starter.payroll.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.payroll.data.PayslipDataFixture
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.data.replica.datasources.MemoryPayrollReadAdmissionDataSource
import dev.fajar.starter.payroll.data.replica.encoding.collection
import dev.fajar.starter.payroll.data.replica.mappers.payrollReplicaScope
import dev.fajar.starter.payroll.data.replica.repositories.EncryptedPayslipReplicaRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.policies.initialPayrollCheckpoint
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.SyncReference
import kotlin.test.*
import kotlinx.coroutines.flow.first

class PayrollReplicaFixture(private val openDatabase: () -> AppDatabase) {
    var db = openDatabase()
        private set

    val raw = PayslipDataFixture()
    val access = raw.access
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var admissions = MemoryPayrollReadAdmissionDataSource()
        private set

    var repository = EncryptedPayslipReplicaRepository(db.replicas, cipher, keys, admissions)
        private set

    val slip = PayrollRecord.Slip(raw.detail.toPayslip(raw.summary.id))
    val payment = PayrollRecord.Payments(raw.payments.toPayments(raw.summary.id))

    suspend fun activate(owner: PayrollAccess = access) =
        db.accounts.activate(owner.sessionId, payrollReplicaScope(owner).accountKey)

    suspend fun checkpoint(owner: PayrollAccess = access) =
        assertIs<AppResult.Success<PayrollCheckpoint?>>(repository.checkpoint(owner)).value

    suspend fun publish(records: List<PayrollRecord>, publish: Boolean = true) {
        var checkpoint = checkpoint()
        if (checkpoint == null) {
            checkpoint = initialPayrollCheckpoint(access, "feed", raw.now)
            assertEquals(AppResult.Success(true), repository.begin(checkpoint, null))
        }
        val batches = records.chunked(16).ifEmpty { listOf(emptyList()) }
        for ((index, batch) in batches.withIndex()) {
            val previous = requireNotNull(checkpoint)
            val visible = previous.published || (publish && index == batches.lastIndex)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = PayrollSyncPhase.CHANGES,
                    cursor = "head",
                    published = visible,
                    refreshedAt = if (visible) raw.now else null,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(
                    previous,
                    next,
                    batch.map {
                        PayrollReplicaEntry(SyncReference(it.collection(), it.id, it.version), it)
                    },
                ),
            )
            checkpoint = next
        }
    }

    suspend fun overview(
        selection: PayslipSelection = PayslipSelection(2026),
        owner: PayrollAccess = access,
    ) =
        assertIs<AppResult.Success<PayrollReplicaOverview>>(
                repository.observe(owner, selection).first()
            )
            .value

    suspend fun details(id: String = slip.id, owner: PayrollAccess = access) =
        assertIs<AppResult.Success<PayrollReplicaDetails>>(
                repository.observeDetails(owner, id).first()
            )
            .value

    suspend fun reopen(owner: PayrollAccess = access) {
        db.close()
        db = openDatabase()
        admissions = MemoryPayrollReadAdmissionDataSource()
        repository = EncryptedPayslipReplicaRepository(db.replicas, cipher, keys, admissions)
        activate(owner)
    }

    fun close() {
        db.close()
        keys.close()
    }
}

fun payrollFixtureId(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
