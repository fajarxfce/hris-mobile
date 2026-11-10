package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceHistoryRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceHistory
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceHistoryUseCaseFixture {
    private val source = SpaceMessageUseCaseFixture()
    val room = source.room
    val base = source.base
    val native = source.native
    val access = source.access
    val identity = source.identity
    val writes = source.writes
    val schedules = source.schedules
    val facts =
        MutableStateFlow<AppResult<CollaborationHistoryPage>>(
            AppResult.Success(CollaborationHistoryPage(emptyList(), null))
        )
    var observations = 0
    var stops = 0
    val queries = mutableListOf<CollaborationHistoryWindow>()
    var readFacts: () -> Flow<AppResult<CollaborationHistoryPage>> = { facts }
    val history =
        object : CollaborationSpaceHistoryRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ) = flow {
                assertEquals(this@SpaceHistoryUseCaseFixture.access, access)
                assertEquals(room.conversation.id, conversationId)
                queries += CollaborationHistoryWindow(after, before, limit, overlap)
                observations++
                try {
                    emitAll(readFacts())
                } finally {
                    stops++
                }
            }

            override suspend fun getEventFact(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> =
                error("Observation does not acquire event evidence")

            override suspend fun getByEvent(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> =
                error("Observation does not acquire event evidence")

            override suspend fun getByLogicalId(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> =
                error("Observation does not acquire event evidence")

            override suspend fun merge(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("Observation never writes history")
        }

    fun observe() =
        ObserveCollaborationSpaceHistory(history, base.directory, source.sessions, native.server)

    fun page() =
        CollaborationHistoryPage(
            listOf(
                CollaborationHistoryMessage(
                    "@fictional:example.test",
                    source.id,
                    "\$event",
                    1_793_491_200_000,
                    "a".repeat(64),
                    "Retained local history",
                    listOf("\$event"),
                    false,
                )
            ),
            null,
            roomId = "!history:example.test",
        )
}
