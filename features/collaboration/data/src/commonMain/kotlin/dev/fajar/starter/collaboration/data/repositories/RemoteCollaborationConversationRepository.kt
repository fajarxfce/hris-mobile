package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationConversationRemoteSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationConversationRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationConversationRepository(
    private val remote: CollaborationConversationRemoteSource,
    private val server: IdentityServer,
) : CollaborationConversationRepository {
    override suspend fun participants(
        owner: CollaborationOwner,
        query: String,
        after: String?,
        limit: Int,
    ) = safeApiCall {
        validate(owner)
        pageInput(after, limit)
        require(query.length <= 80 && query.none { it.code in 0..31 || it.code in 127..159 })
        val dto = remote.participants(owner.sessionId, owner.companyId, query, after, limit)
        conversationPage(dto.items.map { it.toParticipant(owner) }, dto.nextCursor, after, limit) {
            it.accountId
        }
    }

    override suspend fun list(owner: CollaborationOwner, after: String?, limit: Int) = safeApiCall {
        validate(owner)
        pageInput(after, limit)
        val dto = remote.list(owner.sessionId, owner.companyId, after, limit)
        conversationPage(dto.items.map { it.toConversation(owner) }, dto.nextCursor, after, limit) {
            it.id
        }
    }

    override suspend fun get(owner: CollaborationOwner, conversationId: String) = safeApiCall {
        validate(owner)
        collaborationId(conversationId)
        remote.get(owner.sessionId, owner.companyId, conversationId).toConversation(owner).also {
            require(it.id == conversationId)
        }
    }

    override suspend fun room(owner: CollaborationOwner, conversationId: String) = safeApiCall {
        validate(owner)
        collaborationId(conversationId)
        remote.room(owner.sessionId, owner.companyId, conversationId).toRoom(owner, conversationId)
    }

    override suspend fun open(owner: CollaborationOwner, command: CollaborationOpenDirect) =
        safeApiCall {
            validate(owner)
            collaborationId(command.operationId)
            collaborationId(command.peerAccountId)
            require(command.peerAccountId != owner.accountId)
            remote
                .open(
                    owner.sessionId,
                    owner.companyId,
                    command.operationId,
                    OpenCollaborationDirectRequest(command.peerAccountId),
                )
                .toReceipt()
                .also { require(it.version == 0L) }
        }

    override suspend fun requestRoom(
        owner: CollaborationOwner,
        command: CollaborationRequestDirectRoom,
    ) = safeApiCall {
        validate(owner)
        collaborationId(command.operationId)
        collaborationId(command.conversationId)
        collaborationId(command.deviceId)
        require(
            command.deviceVersion in 0..999_999 &&
                command.provisioningVersion?.let { it in 0..999_998 } != false
        )
        remote
            .requestRoom(
                owner.sessionId,
                owner.companyId,
                command.conversationId,
                command.operationId,
                RequestCollaborationDirectRoomRequest(
                    command.deviceId,
                    command.deviceVersion,
                    command.provisioningVersion,
                ),
            )
            .toReceipt()
            .also { require(it.id == command.conversationId && it.version <= 999_999) }
    }

    private fun validate(owner: CollaborationOwner) {
        require(owner.audience == server.audience)
        require(owner.sessionId.length in 1..128 && owner.sessionId.none { it.code < 33 })
        collaborationId(owner.accountId)
        collaborationId(owner.companyId)
        collaborationId(owner.nativeSessionId)
    }

    private fun pageInput(after: String?, limit: Int) {
        require(limit in 1..50)
        after?.let(::collaborationId)
    }
}
