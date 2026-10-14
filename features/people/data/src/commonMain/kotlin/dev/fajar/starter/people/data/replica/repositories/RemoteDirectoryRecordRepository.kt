@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.replica.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.people.data.replica.datasources.DirectoryRecordDataSource
import dev.fajar.starter.people.data.replica.mappers.toRecord
import dev.fajar.starter.people.domain.replica.entities.DirectoryAccess
import dev.fajar.starter.people.domain.replica.repositories.DirectoryRecordRepository
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteDirectoryRecordRepository(private val remote: DirectoryRecordDataSource) :
    DirectoryRecordRepository {
    override suspend fun record(access: DirectoryAccess, id: String) = safeApiCall {
        require(
            Uuid.parse(id).toString() == id &&
                Uuid.parse(access.companyId).toString() == access.companyId
        )
        remote.record(access.sessionId, access.companyId, id).toRecord(id)
    }
}
