@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.profile.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.people.data.profile.datasources.SelfProfileRecordDataSource
import dev.fajar.starter.people.data.profile.mappers.toRecord
import dev.fajar.starter.people.domain.profile.entities.SelfProfileAccess
import dev.fajar.starter.people.domain.profile.repositories.SelfProfileRecordRepository
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteSelfProfileRecordRepository(private val remote: SelfProfileRecordDataSource) :
    SelfProfileRecordRepository {
    override suspend fun record(access: SelfProfileAccess, id: String) = safeApiCall {
        require(
            Uuid.parse(id).toString() == id &&
                Uuid.parse(access.companyId).toString() == access.companyId
        )
        remote
            .record(access.sessionId, access.companyId, id)
            .toRecord(id, access.companyId, access.accountId)
    }
}
