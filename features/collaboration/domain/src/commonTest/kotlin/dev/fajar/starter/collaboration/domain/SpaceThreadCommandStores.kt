package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

/** Independently controlled local command subscriptions; every mutation fails the test. */
internal class SpaceThreadCommandStores(private val room: SpaceRoomBindingPreparationFixture) {
    val spaceFacts =
        MutableStateFlow<AppResult<List<CollaborationSpaceMessageRecord>>>(
            AppResult.Success(emptyList())
        )
    val originalFacts =
        MutableStateFlow<AppResult<List<CollaborationMessageRecord>>>(
            AppResult.Success(emptyList())
        )
    var readSpace: () -> Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = { spaceFacts }
    var readOriginal: () -> Flow<AppResult<List<CollaborationMessageRecord>>> = { originalFacts }
    var spaceStarts = 0
    var originalStarts = 0
    var spaceStops = 0
    var originalStops = 0

    val spaces =
        object : CollaborationSpaceMessageRepository {
            override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
                flow {
                    assertEquals(room.access, access)
                    assertEquals(room.conversation.id, conversationId)
                    spaceStarts++
                    try {
                        emitAll(readSpace())
                    } finally {
                        spaceStops++
                    }
                }

            override fun observeRecord(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationSpaceMessageRecord?>> = error("No record subscription")

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> = error("No record lookup")

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("No command write")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("No command write")
        }

    val originals =
        object : CollaborationMessageRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String?) = flow {
                assertEquals(CollaborationSpaceRoomOrigin.LEGACY, room.binding.room.origin)
                assertEquals(originalAccess(), access)
                assertEquals(room.position.conversationId, conversationId)
                originalStarts++
                try {
                    emitAll(readOriginal())
                } finally {
                    originalStops++
                }
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageRecord?> = error("No record lookup")

            override suspend fun insert(
                access: CollaborationLocalAccess,
                intent: CollaborationMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("No command write")

            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("No command write")
        }

    fun originalAccess() =
        CollaborationLocalAccess(
            room.access.sessionId,
            room.access.audience,
            room.access.accountId,
            room.position.companyId,
        )

    fun spaceRecord(id: String = "b1000000-0000-4000-8000-000000000001") =
        CollaborationSpaceMessageRecord(
            CollaborationSpaceMessageIntent(
                id,
                room.access.messageOwner(),
                room.conversation.id,
                room.conversation.createdAt,
                "Canonical pending",
            ),
            0,
            CollaborationMessageState.Queued,
        )

    fun originalRecord(id: String = "b1000000-0000-4000-8000-000000000001") =
        CollaborationMessageRecord(
            CollaborationMessageIntent(
                id,
                originalAccess().messageOwner(),
                room.position.conversationId,
                room.conversation.createdAt,
                "Original pending",
            ),
            0,
            CollaborationMessageState.Queued,
        )

    fun device() =
        CollaborationMessageDevice(
            "b2000000-0000-4000-8000-000000000001",
            "b3000000-0000-4000-8000-000000000001",
            "b4000000-0000-4000-8000-000000000001",
            room.roomId,
        )
}
