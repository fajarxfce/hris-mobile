package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceHistoryChangesFixture(legacy: Boolean = false) {
    val base = SpaceThreadHistoryFixture(legacy)
    val access = base.access
    val id = base.id
    val source =
        assertIs<AppResult.Success<CollaborationSpaceHistoryLocation>>(
                resolveCollaborationSpaceHistoryLocation(access, id, base.room.binding)
            )
            .value
    val owner = collaborationHistoryChangeOwner(source)
    val generation = "b3000000-0000-4000-8000-000000000001"
    val window = CollaborationHistoryChangeWindow(owner, generation, null, 4)
    val event = base.page().messages.single().primaryEventId
    val page = CollaborationHistoryChanges.Page(window, "$generation:4", listOf(event), null)
    val calls =
        mutableListOf<
            Triple<CollaborationSpaceHistoryLocation, CollaborationHistoryChangeCursor?, Int>
        >()
    var boundReads = 0
    var before: suspend (String) -> Unit = {}
    var response: AppResult<CollaborationHistoryChanges> = AppResult.Success(page)
    val failure = Failure(FailureKind.Storage, "Fixture storage failure")
    val bindings =
        object : CollaborationSpaceRoomBindingRepository by base.bindings {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                boundReads++
                before("binding")
                return base.bindings.getByConversation(access, conversationId)
            }
        }
    val spaces =
        object : CollaborationSpaceHistoryChangesRepository {
            override suspend fun changes(
                source: CollaborationSpaceHistoryLocation.Space,
                cursor: CollaborationHistoryChangeCursor?,
                limit: Int,
            ): AppResult<CollaborationHistoryChanges> {
                assertEquals(this@SpaceHistoryChangesFixture.source, source)
                calls += Triple(source, cursor, limit)
                before("history")
                return response
            }
        }
    val companies =
        object : CollaborationCompanyHistoryChangesRepository {
            override suspend fun changes(
                source: CollaborationSpaceHistoryLocation.Original,
                cursor: CollaborationHistoryChangeCursor?,
                limit: Int,
            ): AppResult<CollaborationHistoryChanges> {
                assertEquals(this@SpaceHistoryChangesFixture.source, source)
                calls += Triple(source, cursor, limit)
                before("history")
                return response
            }
        }

    fun read() =
        ReadCollaborationSpaceHistoryChanges(
            spaces,
            companies,
            bindings,
            base.directory,
            base.sessions,
            base.native.server,
        )

    suspend fun result(cursor: CollaborationHistoryChangeCursor? = null, limit: Int = 16) =
        read()(access, id, cursor, limit)
}
