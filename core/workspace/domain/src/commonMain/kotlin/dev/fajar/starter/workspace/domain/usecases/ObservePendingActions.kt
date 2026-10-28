package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import kotlinx.coroutines.flow.Flow

/**
 * Each owning feature supplies admitted local observation. Opening a summary performs no delivery.
 */
interface ObservePendingActions {
    val group: PendingActionGroup

    fun observePending(workspaceId: String): Flow<AppResult<PendingActionSummary>>
}
