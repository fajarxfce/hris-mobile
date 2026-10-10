package dev.fajar.starter.workspace.data.datasources

import dev.fajar.starter.workspace.data.dto.WorkspaceRecord
import kotlinx.coroutines.flow.StateFlow

interface WorkspaceLocalDataSource {
    val selected: StateFlow<WorkspaceRecord?>

    suspend fun compareAndSet(expected: WorkspaceRecord?, updated: WorkspaceRecord?): Boolean
}
