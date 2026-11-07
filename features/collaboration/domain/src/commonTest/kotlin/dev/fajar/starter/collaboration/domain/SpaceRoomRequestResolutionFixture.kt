package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ResolveCollaborationSpaceRoomRequest
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceRoomRequestResolutionFixture {
    val local = SpaceConversationOpenReplayFixture()
    val native = local.native
    val owner = local.owner
    val conversation = local.conversation()
    val command =
        CollaborationSpaceConversationCommand.Room(
            CollaborationSpaceRoomRequestIntent(
                local.command.id,
                local.command.binding,
                conversation.id,
                CollaborationSpaceRoomRequest(
                    "83000000-0000-4000-8000-000000000001",
                    7,
                    CollaborationSpaceRoomAttempt(local.peer, 9),
                ),
            ),
            local.peer,
            local.command.createdAt,
        )
    val evidence =
        CollaborationSpaceRoomRequestFence(
            command.intent,
            "84000000-0000-4000-8000-000000000001",
            command.createdAt,
        )
    val receipt = CollaborationSetupReceipt(conversation.id, 10)
    val original =
        CollaborationSpaceConversationCommandRecord(
            command,
            1,
            1,
            CollaborationSpaceConversationOutcome.Unconfirmed(),
        )
    val requests =
        mutableListOf<Pair<CollaborationSpaceConversation, CollaborationSpaceRoomRequestIntent>>()
    var onResolve: suspend () -> AppResult<CollaborationSpaceRoomRequestResolution> = {
        AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(evidence))
    }

    init {
        local.active = original
    }

    val rooms =
        object : CollaborationSpaceRoomRepository {
            override suspend fun get(
                conversation: CollaborationSpaceConversation
            ): AppResult<CollaborationSpaceRoomView> =
                error("Resolution never polls room readiness")

            override suspend fun request(
                conversation: CollaborationSpaceConversation,
                operation: String,
                request: CollaborationSpaceRoomRequest,
            ): AppResult<CollaborationSetupReceipt> =
                error("Resolution never executes another room request")

            override suspend fun readReceipt(
                conversation: CollaborationSpaceConversation,
                original: CollaborationSpaceRoomOperation,
            ): AppResult<CollaborationSpaceRoomReceiptObservation> =
                error("Resolution never revives an old native parent")

            override suspend fun readIntentReceipt(
                conversation: CollaborationSpaceConversation,
                original: CollaborationSpaceRoomRequestIntent,
            ): AppResult<CollaborationSpaceRoomIntentReceiptObservation> =
                error("Receipt absence is not non-execution evidence")

            override suspend fun fenceRequest(
                conversation: CollaborationSpaceConversation,
                original: CollaborationSpaceRoomRequestIntent,
            ): AppResult<CollaborationSpaceRoomRequestResolution> {
                assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(
                    assertNotNull(local.active).outcome
                )
                assertEquals(command.intent, original)
                requests += conversation to original
                return onResolve()
            }
        }

    fun resolved(
        outcome: CollaborationSpaceConversationOutcome =
            CollaborationSpaceConversationOutcome.Fenced(evidence)
    ) = original.copy(revision = 3, outcome = outcome)

    fun resolve() =
        ResolveCollaborationSpaceRoomRequest(
            local.commands,
            rooms,
            local.base.directory,
            local.sessions,
            native.server,
            local.build,
            native.clock,
            native.time,
        )
}
