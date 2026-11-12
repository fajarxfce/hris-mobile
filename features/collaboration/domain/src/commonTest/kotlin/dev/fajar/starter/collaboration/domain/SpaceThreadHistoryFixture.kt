package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceThreadHistory
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceThreadHistoryFixture(val legacy: Boolean = false) {
    val room = SpaceRoomBindingPreparationFixture(legacy)
    val base = room.base
    val native = room.native
    val access = room.access
    val id = room.conversation.id
    val identity = MutableStateFlow<AppResult<Session?>>(AppResult.Success(native.session))
    var currentIdentity: suspend () -> AppResult<Session?> = { identity.value }
    var currentAdmission: suspend () -> AppResult<CollaborationSpaceDirectoryAdmission> = {
        base.gate.value
    }
    val facts = MutableStateFlow<AppResult<CollaborationHistoryPage>>(AppResult.Success(page()))
    var readFacts: () -> Flow<AppResult<CollaborationHistoryPage>> = { facts }
    val queries = mutableListOf<CollaborationHistoryWindow>()
    var spaceReads = 0
    var originalReads = 0
    var stops = 0
    val sessions =
        object : SessionRepository by room.source.local.sessions {
            override fun observe() = identity

            override suspend fun current() = currentIdentity()
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                assertEquals(this@SpaceThreadHistoryFixture.access, access)
                return currentAdmission()
            }
        }

    init {
        room.retained = AppResult.Success(room.binding)
    }

    private fun pages(window: CollaborationHistoryWindow) = flow {
        queries += window
        try {
            emitAll(readFacts())
        } finally {
            stops++
        }
    }

    val spaces =
        object : CollaborationSpaceHistoryRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> {
                assertEquals(false, legacy)
                assertEquals(this@SpaceThreadHistoryFixture.access, access)
                assertEquals(id, conversationId)
                assertEquals(room.roomId, roomId)
                spaceReads++
                return pages(CollaborationHistoryWindow(after, before, limit, overlap))
            }

            override suspend fun getEventFact(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No event lookup")

            override suspend fun getByEvent(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No event lookup")

            override suspend fun getByLogicalId(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No alias lookup")

            override suspend fun merge(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No history writes")
        }
    val originals =
        object : CollaborationHistoryRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> {
                assertEquals(true, legacy)
                assertEquals(
                    CollaborationLocalAccess(
                        this@SpaceThreadHistoryFixture.access.sessionId,
                        this@SpaceThreadHistoryFixture.access.audience,
                        this@SpaceThreadHistoryFixture.access.accountId,
                        room.position.companyId,
                    ),
                    access,
                )
                assertEquals(room.position.conversationId, conversationId)
                assertEquals(room.roomId, roomId)
                originalReads++
                return pages(CollaborationHistoryWindow(after, before, limit, overlap))
            }

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No event lookup")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No event lookup")

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No alias lookup")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No history writes")
        }

    fun observe() =
        ObserveCollaborationSpaceThreadHistory(
            room.bindings,
            spaces,
            originals,
            directory,
            sessions,
            native.server,
        )

    fun page() =
        CollaborationHistoryPage(
            listOf(
                CollaborationHistoryMessage(
                    "@fictional:matrix.example.test",
                    null,
                    "\$original",
                    1_793_491_200_000,
                    "a".repeat(64),
                    "Retained text",
                    listOf("\$original"),
                    false,
                )
            ),
            null,
            roomId = room.roomId,
        )
}
