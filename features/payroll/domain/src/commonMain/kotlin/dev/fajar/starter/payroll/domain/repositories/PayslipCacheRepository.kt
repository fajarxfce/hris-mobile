package dev.fajar.starter.payroll.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.*
import kotlinx.coroutines.flow.Flow

/** Independent, bounded snapshots. Replacement checks the previously observed generation. */
interface PayslipCacheRepository {
    fun observe(access: PayrollAccess): Flow<AppResult<PayslipSnapshot<PayslipOverview>?>>

    suspend fun generation(access: PayrollAccess): AppResult<String?>

    suspend fun replace(
        access: PayrollAccess,
        expectedGeneration: String?,
        snapshot: PayslipSnapshot<PayslipOverview>?,
    ): AppResult<Boolean>

    fun observeDetails(access: PayrollAccess): Flow<AppResult<PayslipSnapshot<PayslipView>?>>

    suspend fun detailsGeneration(access: PayrollAccess): AppResult<String?>

    suspend fun replaceDetails(
        access: PayrollAccess,
        expectedGeneration: String?,
        snapshot: PayslipSnapshot<PayslipView>?,
    ): AppResult<Boolean>
}
