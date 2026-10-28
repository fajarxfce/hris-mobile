package dev.fajar.starter.workforce.domain.overtime.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimePlanWorkingCopy
import kotlinx.coroutines.flow.Flow

interface OvertimePlanWorkingCopyRepository {
    fun observePage(
        access: WorkforceAccess,
        after: String? = null,
    ): Flow<
        AppResult<
            dev.fajar.starter.workforce.domain.overtime.entities.OvertimePage<
                OvertimePlanWorkingCopy
            >
        >
    >

    fun observe(
        access: WorkforceAccess,
        employeeId: String,
    ): Flow<AppResult<OvertimePlanWorkingCopy?>>

    /**
     * Null means a different revision owns the slot. Replaying the same acknowledged write is safe.
     */
    suspend fun save(
        access: WorkforceAccess,
        expectedRevision: String?,
        copy: OvertimePlanWorkingCopy,
    ): AppResult<OvertimePlanWorkingCopy?>

    suspend fun remove(
        access: WorkforceAccess,
        employeeId: String,
        expectedRevision: String,
    ): AppResult<Boolean>
}
