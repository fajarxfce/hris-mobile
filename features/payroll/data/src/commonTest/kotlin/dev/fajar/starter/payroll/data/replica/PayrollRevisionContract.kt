package dev.fajar.starter.payroll.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.data.mappers.toDto
import dev.fajar.starter.payroll.data.replica.mappers.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.policies.initialPayrollCheckpoint
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.serialization.json.Json

suspend fun checkPayrollLegacyBalanceUpgrade(f: PayrollReplicaFixture) {
    val legacy = initialPayrollCheckpoint(f.access, "legacy", f.raw.now).copy(recordVersion = 1)
    assertFalse(Json.encodeToString(legacy.toReplicaDto()).contains("recordVersion"))
    assertEquals(AppResult.Success(true), f.repository.begin(legacy, null))
    val oldPayments =
        f.payment.copy(
            value =
                f.payment.value.copy(
                    settlement = null,
                    attempts =
                        f.payment.value.attempts.map {
                            it.copy(amount = null, assessmentRevision = null)
                        },
                )
        )
    val oldJson = Json.encodeToString(oldPayments.value.toDto())
    assertFalse(oldJson.contains("settlement"))
    assertFalse(oldJson.contains("assessmentRevision"))
    assertFalse(oldJson.contains("amount"))
    f.publish(listOf(f.slip, oldPayments))
    f.reopen()
    val retained = f.details()
    assertEquals(1, retained.checkpoint!!.recordVersion)
    assertEquals(f.slip.value, retained.view!!.payslip)
    assertNull(retained.view!!.payments.settlement)
    assertNull(retained.view!!.payments.attempts.single().amount)
    val replacement = initialPayrollCheckpoint(f.access, "settlement", f.raw.now)
    assertEquals(
        AppResult.Success(true),
        f.repository.begin(replacement, retained.checkpoint!!.generation),
    )
    f.publish(listOf(f.slip, f.payment), publish = false)
    f.reopen()
    assertEquals(retained, f.details())
    f.publish(emptyList())
    val current = f.details()
    assertEquals(PayrollCheckpoint.CURRENT_RECORD_VERSION, current.checkpoint!!.recordVersion)
    assertEquals(f.payment.value.version, current.view!!.payments.version)
    assertEquals(f.payment.value, current.view!!.payments)
    assertEquals(f.slip.value, current.view!!.payslip)
}

suspend fun checkPayrollRevisedDetailsAndSummary(f: PayrollReplicaFixture) {
    f.publish(listOf(f.slip, f.payment))
    val scope = payrollReplicaScope(f.access)
    val partition = payrollReplicaPartition(f.access)
    val originalPayment = f.db.replicas.document(scope, partition, "payments/${f.slip.id}")
    val revised =
        f.slip.copy(value = f.slip.value.copy(summary = f.slip.value.summary.copy(version = 2)))
    f.publish(listOf(revised))
    assertEquals(originalPayment, f.db.replicas.document(scope, partition, "payments/${f.slip.id}"))
    assertEquals(revised.value.summary, f.overview().page.items.single())
    assertEquals(revised.value, f.details().view!!.payslip)
    assertEquals(0L, f.details().view!!.payments.settlement!!.assessmentRevision)
    f.reopen()
    f.publish(listOf(f.slip))
    assertEquals(2L, f.overview().page.items.single().version)
    assertEquals(2L, f.details().view!!.payslip.summary.version)
    val previous = f.checkpoint()!!
    assertEquals(
        AppResult.Success(true),
        f.repository.commit(
            previous,
            previous.copy(revision = previous.revision + 1),
            listOf(PayrollReplicaEntry(SyncReference(SyncCollection.PAYSLIPS, f.slip.id, 2), null)),
        ),
    )
    f.publish(listOf(f.slip))
    f.publish(listOf(revised))
    assertNull(f.details().view)
    assertTrue(f.overview().page.items.isEmpty())
}

suspend fun checkPayrollRevisionMismatchCannotAdvanceCheckpoint(f: PayrollReplicaFixture) {
    f.publish(listOf(f.slip, f.payment))
    val previous = f.checkpoint()!!
    val retained = f.details()
    val wrongRevision =
        PayrollReplicaEntry(
            SyncReference(SyncCollection.PAYSLIPS, f.slip.id, 2),
            f.slip.copy(value = f.slip.value.copy(summary = f.slip.value.summary.copy(version = 1))),
        )
    assertIs<AppResult.Failed>(
        f.repository.commit(
            previous,
            previous.copy(revision = previous.revision + 1),
            listOf(wrongRevision),
        )
    )
    assertEquals(previous, f.checkpoint())
    assertEquals(retained, f.details())
    assertIs<AppResult.Failed>(
        f.repository.commit(
            previous,
            previous.copy(revision = previous.revision + 1, recordVersion = 1),
            emptyList(),
        )
    )
    assertEquals(previous, f.checkpoint())
    assertEquals(retained, f.details())
}
