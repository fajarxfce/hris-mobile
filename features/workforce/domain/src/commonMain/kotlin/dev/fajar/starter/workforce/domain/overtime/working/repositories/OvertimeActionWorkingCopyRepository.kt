package dev.fajar.starter.workforce.domain.overtime.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeAction
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingCopy
import kotlinx.coroutines.flow.Flow

interface OvertimeActionWorkingCopyRepository {
    fun observe(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
        action: OvertimeAction,
    ): Flow<AppResult<OvertimeActionWorkingCopy?>>

    /** Compare-and-set; the same revision and input recover a lost save acknowledgement. */
    suspend fun save(
        access: WorkforceAccess,
        expectedRevision: String?,
        copy: OvertimeActionWorkingCopy,
    ): AppResult<OvertimeActionWorkingCopy?>

    /** Removes only the acknowledged revision; other actions and newer edits remain. */
    suspend fun remove(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
        action: OvertimeAction,
        expectedRevision: String,
    ): AppResult<Boolean>
}
