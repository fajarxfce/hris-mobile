package dev.fajar.starter.workspace.data.datasources

import dev.fajar.starter.workspace.data.dto.WorkspaceRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

@Single
class MemoryWorkspaceDataSource : WorkspaceLocalDataSource {
    private val record = MutableStateFlow<WorkspaceRecord?>(null)
    override val selected = record.asStateFlow()

    override suspend fun compareAndSet(expected: WorkspaceRecord?, updated: WorkspaceRecord?) =
        record.compareAndSet(expected, updated)
}
