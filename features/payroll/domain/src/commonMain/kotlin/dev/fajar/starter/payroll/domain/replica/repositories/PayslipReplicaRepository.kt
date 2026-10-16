package dev.fajar.starter.payroll.domain.replica.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import kotlinx.coroutines.flow.Flow

interface PayslipReplicaRepository {
    fun observeAdmission(access: PayrollAccess): Flow<AppResult<PayrollReadAdmission>>

    suspend fun admission(access: PayrollAccess): AppResult<PayrollReadAdmission>

    suspend fun restrict(access: PayrollAccess, failure: Failure): AppResult<Boolean>

    /** Only the acquisition that observed this denial may reopen its projection. */
    suspend fun admit(access: PayrollAccess, expectedRevision: String?): AppResult<Boolean>

    fun observe(
        access: PayrollAccess,
        selection: PayslipSelection,
    ): Flow<AppResult<PayrollReplicaOverview>>

    fun observeDetails(access: PayrollAccess, id: String): Flow<AppResult<PayrollReplicaDetails>>

    /** Metadata from the visible generation only, without reading its documents. */
    fun observePublishedCheckpoint(access: PayrollAccess): Flow<AppResult<PayrollCheckpoint?>>

    suspend fun checkpoint(access: PayrollAccess): AppResult<PayrollCheckpoint?>

    suspend fun begin(
        checkpoint: PayrollCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean>

    /** Full facts, indexed summaries, payment progress and cursor commit atomically. */
    suspend fun commit(
        previous: PayrollCheckpoint,
        next: PayrollCheckpoint,
        entries: List<PayrollReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: PayrollAccess): AppResult<Boolean>
}
