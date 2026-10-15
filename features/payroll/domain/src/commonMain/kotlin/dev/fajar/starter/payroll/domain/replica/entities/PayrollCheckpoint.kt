package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.payroll.domain.entities.PayrollAccess
import kotlin.time.Instant

/** Acquisition is session-bound; the published generation belongs to the account. */
data class PayrollCheckpoint(
    val access: PayrollAccess,
    val generation: String,
    val revision: Long,
    val phase: PayrollSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
    /** Acquisition format; upgrading stages a replacement without hiding published facts. */
    val recordVersion: Int = CURRENT_RECORD_VERSION,
) {
    override fun toString() = "PayrollCheckpoint([redacted])"

    companion object {
        const val CURRENT_RECORD_VERSION = 2
    }
}
