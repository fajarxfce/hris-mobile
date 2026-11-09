package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRoomRepository
import dev.fajar.starter.collaboration.domain.usecases.ReplayCollaborationSpaceRoomRequest
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceRoomRequestReplayFixture {
    val resolution = SpaceRoomRequestResolutionFixture()
    val local = resolution.local
    val native = resolution.native
    val conversation = resolution.conversation
    val command = resolution.command
    val receipt = resolution.receipt
    val original =
        resolution.original.copy(
            revision = 0,
            attempts = 0,
            outcome = CollaborationSpaceConversationOutcome.Pending,
        )
    val requests =
        mutableListOf<Pair<CollaborationSpaceConversation, CollaborationSpaceRoomRequest>>()
    var onRequest: suspend () -> AppResult<CollaborationSetupReceipt> = {
        AppResult.Success(receipt)
    }

    init {
        local.active = original
    }

    val rooms =
        object : CollaborationSpaceRoomRepository by resolution.rooms {
            override suspend fun request(
                conversation: CollaborationSpaceConversation,
                operation: String,
                request: CollaborationSpaceRoomRequest,
            ): AppResult<CollaborationSetupReceipt> {
                assertEquals(command.id, operation)
                assertEquals(command.intent.request, request)
                assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(
                    assertNotNull(local.active).outcome
                )
                assertTrue(assertNotNull(local.active).attempts > 0)
                requests += conversation to request
                return onRequest()
            }

            override suspend fun fenceRequest(
                conversation: CollaborationSpaceConversation,
                original: CollaborationSpaceRoomRequestIntent,
            ): AppResult<CollaborationSpaceRoomRequestResolution> =
                error("Dispatch never fences or replaces an original")
        }

    fun recorded() =
        original.copy(
            revision = 2,
            attempts = 1,
            outcome = CollaborationSpaceConversationOutcome.Recorded(receipt),
        )

    fun replay() =
        ReplayCollaborationSpaceRoomRequest(
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
