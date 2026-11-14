package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceInboxEntry
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceInboxPage
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceInboxPageFixture {
    val base = SpaceThreadHistoryFixture()
    val access = base.access
    val id = base.id
    val generation = "af9c5e40-ea33-4348-a1b3-665c38fe149a"
    val other = "2846272f-8b73-4918-8c30-89fce1303355"
    val version = CollaborationSpaceInboxVersion(generation, 8, 8)
    val failure = Failure(FailureKind.Storage, "client.storage_failed")
    var observations = 0
    var stops = 0
    val queries = mutableListOf<Pair<String?, Int>>()
    val pages = MutableStateFlow<AppResult<CollaborationSpaceInboxPage>>(AppResult.Success(page()))
    var source: () -> Flow<AppResult<CollaborationSpaceInboxPage>> = { pages }
    val inbox =
        object : CollaborationSpaceInboxRepository {
            override fun observePage(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ) = flow {
                assertEquals(this@SpaceInboxPageFixture.access, access)
                observations++
                queries += after to limit
                try {
                    emitAll(source())
                } finally {
                    stops++
                }
            }

            override suspend fun conversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                candidateLimit: Int,
            ): AppResult<CollaborationSpaceInboxConversation> = error("No conversation scan")

            override suspend fun activities(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                sources: List<CollaborationSpaceInboxSource>,
            ): AppResult<CollaborationSpaceInboxActivities> = error("No source reads")

            override suspend fun seed(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
                repair: CollaborationSpaceInboxRepairPublication?,
            ): AppResult<CollaborationSpaceInboxSeedResult> = error("No seeding")

            override suspend fun publish(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> = error("No publication")
        }
    val observer =
        ObserveCollaborationSpaceInboxPage(inbox, base.directory, base.sessions, base.native.server)

    fun entry(conversation: String = id, timestamp: Long = 1000, ordinal: Long = 1) =
        collaborationSpaceInboxEntry(
            conversation,
            100,
            null,
            CollaborationSpaceInboxActivity(
                CollaborationSpaceInboxSource.GroupOriginal(other),
                CollaborationSpaceInboxPosition(timestamp, ordinal),
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.TEXT,
                    "Pending text",
                    timestamp,
                    CollaborationMessageDelivery.PENDING,
                ),
            ),
        )

    fun page() = CollaborationSpaceInboxPage.Items(version, listOf(entry()), null)
}
