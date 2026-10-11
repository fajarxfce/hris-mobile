@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.data.repositories

import dev.fajar.starter.inbox.data.datasources.InboxRemoteDataSource
import dev.fajar.starter.inbox.data.dto.InboxActionRequest
import dev.fajar.starter.inbox.data.mappers.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.repositories.InboxRepository
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteInboxRepository(private val remote: InboxRemoteDataSource) : InboxRepository {
    override suspend fun unreadCount(sessionId: String, companyId: String) = safeApiCall {
        require(Uuid.parse(companyId).toString() == companyId)
        remote.unreadCount(sessionId, companyId).unreadCount.also { require(it >= 0) }
    }

    override suspend fun item(sessionId: String, companyId: String, id: String) = safeApiCall {
        Uuid.parse(companyId)
        Uuid.parse(id)
        remote.item(sessionId, companyId, id).toItem(id)
    }

    override suspend fun act(sessionId: String, command: InboxCommand) = safeApiCall {
        listOf(command.companyId, command.itemId, command.id).forEach {
            require(Uuid.parse(it).toString() == it)
        }
        remote
            .act(
                sessionId,
                command.companyId,
                command.itemId,
                command.id,
                when (command.action) {
                    InboxAction.READ -> "read"
                    InboxAction.ACKNOWLEDGE -> "acknowledge"
                },
                InboxActionRequest(command.expectedVersion),
            )
            .toReceipt(command)
    }
}
