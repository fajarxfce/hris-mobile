@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.data.repositories

import dev.fajar.starter.inbox.data.datasources.InboxRemoteDataSource
import dev.fajar.starter.inbox.data.mappers.toItem
import dev.fajar.starter.inbox.domain.repositories.InboxRepository
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteInboxRepository(private val remote: InboxRemoteDataSource) : InboxRepository {
    override suspend fun item(sessionId: String, companyId: String, id: String) = safeApiCall {
        Uuid.parse(companyId)
        Uuid.parse(id)
        remote.item(sessionId, companyId, id).toItem(id)
    }
}
