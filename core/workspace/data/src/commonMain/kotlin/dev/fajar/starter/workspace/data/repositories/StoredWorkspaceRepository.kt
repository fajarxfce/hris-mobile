@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workspace.data.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.storage.*
import dev.fajar.starter.workspace.data.datasources.*
import dev.fajar.starter.workspace.data.mappers.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single
class StoredWorkspaceRepository(
    private val local: WorkspaceLocalDataSource,
    private val remote: WorkspaceRemoteDataSource,
) : WorkspaceRepository {
    override fun observe() = safeStorageFlow(local.selected.map { it?.toWorkspace() })

    override suspend fun current() = safeStorageCall { local.selected.value?.toWorkspace() }

    override suspend fun compareAndSet(expected: Workspace?, updated: Workspace?) =
        safeStorageCall {
            local.compareAndSet(expected?.toRecord(), updated?.toRecord())
        }

    override suspend fun permissions(sessionId: String, companyId: String) = safeApiCall {
        Uuid.parse(companyId)
        val response = remote.permissions(sessionId, companyId)
        require(response.companyId == companyId && response.permissions.size <= 1000)
        require(response.permissions.all { it.matches(Regex("[a-z][a-z0-9_.:-]{0,99}")) })
        response.permissions.toSet()
    }
}
