package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceRoomRemoteSource
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceRoomRequestDto
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRoomRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceRoomRepository(
    private val remote: CollaborationSpaceRoomRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceRoomRepository {
    override suspend fun readIntentReceipt(
        conversation: CollaborationSpaceConversation,
        original: CollaborationSpaceRoomRequestIntent,
    ) = safeApiCall {
        validate(conversation)
        require(validCollaborationSpaceRoomRequestIntent(conversation, original))
        remote
            .readIntentReceipt(
                conversation.owner.parent.sessionId,
                conversation.owner.spaceId,
                conversation.id,
                original.toIntentReceiptReadDto(),
            )
            .toRoomIntentReceiptObservation(original)
    }

    override suspend fun readReceipt(
        conversation: CollaborationSpaceConversation,
        original: CollaborationSpaceRoomOperation,
    ) = safeApiCall {
        validate(conversation)
        require(validCollaborationSpaceRoomOperation(conversation, original))
        remote
            .readReceipt(
                conversation.owner.parent.sessionId,
                conversation.owner.spaceId,
                conversation.id,
                original.toReceiptReadDto(),
            )
            .toRoomReceiptObservation(original)
    }

    override suspend fun get(conversation: CollaborationSpaceConversation) = safeApiCall {
        validate(conversation)
        remote
            .get(conversation.owner.parent.sessionId, conversation.owner.spaceId, conversation.id)
            .toSpaceRoomView(conversation)
    }

    override suspend fun request(
        conversation: CollaborationSpaceConversation,
        operation: String,
        request: CollaborationSpaceRoomRequest,
    ) = safeApiCall {
        validate(conversation)
        require(validCollaborationSpaceConversationId(operation))
        require(validCollaborationSpaceRoomRequest(conversation, request))
        remote
            .request(
                conversation.owner.parent.sessionId,
                conversation.owner.spaceId,
                conversation.id,
                operation,
                CollaborationSpaceRoomRequestDto(
                    request.deviceId,
                    request.expectedDeviceVersion,
                    request.expectedAttempt?.revision,
                    request.expectedAttempt?.accountId,
                ),
            )
            .toReceipt()
            .also { require(it.id == conversation.id && it.version in 0..999999) }
    }

    private fun validate(conversation: CollaborationSpaceConversation) {
        val parent = conversation.owner.parent
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        require(validCollaborationSpaceConversation(conversation))
    }
}
