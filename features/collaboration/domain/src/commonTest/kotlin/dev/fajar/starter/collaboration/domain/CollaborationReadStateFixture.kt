package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class CollaborationReadStateFixture {
    val base = HistoryReferenceReconciliationFixture()
    val local = base.local
    val conversationId = base.key.conversationId
    val roomId = base.key.roomId
    val ids = base.facts.keys.toList()
    val intent =
        CollaborationReadReceiptIntent(
            id(1),
            ids.first(),
            CollaborationReadReceiptVisibility.Private,
        )
    var state = CollaborationReadState(null, emptySet(), emptyList(), null)
    var rawRead: CollaborationReadState? = null
    var readFailure: Failure? = null
    var markResult: AppResult<Boolean> = AppResult.Success(true)
    var commitOnFailure = false
    var afterRead: suspend () -> Unit = {}
    var afterMark: suspend () -> Unit = {}
    var reads = 0
    var writes = 0
    val accessLog = mutableListOf<CollaborationLocalAccess>()
    val storedSelections = mutableListOf<List<String>>()
    val readSelections = mutableListOf<List<String>>()
    val repo =
        object : CollaborationReadStateRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventIds: List<String>,
            ): kotlinx.coroutines.flow.Flow<AppResult<CollaborationReadState>> =
                error("This fixture exercises finite read operations only")

            override suspend fun read(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventIds: List<String>,
            ): AppResult<CollaborationReadState> {
                checkScope(access, conversationId, roomId)
                reads++
                readSelections += eventIds
                val captured =
                    rawRead
                        ?: state.copy(readEventIds = state.readEventIds.intersect(eventIds.toSet()))
                afterRead()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }

            override suspend fun mark(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                expected: CollaborationReadStateRevision?,
                eventIds: List<String>,
                intent: CollaborationReadReceiptIntent,
            ): AppResult<Boolean> {
                checkScope(access, conversationId, roomId)
                writes++
                assertEquals(state.version, expected)
                storedSelections += eventIds
                if (markResult == AppResult.Success(true) || commitOnFailure) {
                    state =
                        CollaborationReadState(
                            CollaborationReadStateRevision(
                                id(2),
                                (state.version?.revision ?: -1) + 1,
                            ),
                            state.readEventIds + eventIds,
                            if (intent in state.pending || state.acknowledged == intent)
                                state.pending
                            else state.pending + intent,
                            state.acknowledged,
                        )
                }
                afterMark()
                return markResult
            }

            override suspend fun acknowledge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                intent: CollaborationReadReceiptIntent,
            ): AppResult<Boolean> = error("Local mark cannot acknowledge provider delivery")
        }
    val reader =
        ReadCollaborationReadState(
            repo,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )
    val marker =
        MarkCollaborationMessagesRead(
            repo,
            base.history,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    fun scope(): CollaborationLocalScope.Captured {
        val session = local.setup.session!!
        return CollaborationLocalScope.Captured(
            CollaborationLocalAccess(
                session.id,
                local.server.audience,
                session.user.id,
                local.summary!!.conversation.companyId,
            )
        )
    }

    suspend fun read(scope: CollaborationLocalScope = scope(), selected: List<String> = ids) =
        reader(scope, conversationId, roomId, selected)

    suspend fun mark(
        scope: CollaborationLocalScope = scope(),
        selected: List<String> = ids,
        nextIntent: CollaborationReadReceiptIntent = intent,
    ) = marker(scope, conversationId, roomId, selected, nextIntent)

    private fun checkScope(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
    ) {
        assertEquals(this.conversationId, conversationId)
        assertEquals(this.roomId, roomId)
        accessLog += access
    }

    companion object {
        fun id(value: Int) = "c6000000-0000-4000-8000-" + value.toString().padStart(12, '0')
    }
}
