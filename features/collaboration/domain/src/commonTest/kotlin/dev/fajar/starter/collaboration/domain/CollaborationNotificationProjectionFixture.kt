package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationMessageNotification
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class CollaborationNotificationProjectionFixture {
    val settings = CollaborationNotificationSettingsFixture()
    val local = settings.local
    val read = settings.base
    val conversation = read.conversationId
    val room = read.roomId
    val event = read.ids.first()
    var original: CollaborationHistoryMessage? =
        CollaborationHistoryMessage(
            "@peer:test",
            read.intent.id,
            event,
            100,
            "a".repeat(64),
            "Private message",
            read.ids,
            false,
        )
    var self: CollaborationSelfIdentity? = CollaborationSelfIdentity("@self:test")
    var historyCalls = 0
    var identityCalls = 0
    var historyFailure: Failure? = null
    var identityFailure: Failure? = null
    var afterHistory: suspend () -> Unit = {}
    var afterIdentity: suspend () -> Unit = {}
    val history =
        object : CollaborationHistoryRepository by read.base.history {
            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> {
                assertEquals(conversation, conversationId)
                assertEquals(room, roomId)
                historyCalls++
                val captured = original
                afterHistory()
                return historyFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }
        }
    val identities =
        object : CollaborationIdentityRepository {
            override suspend fun current(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationSelfIdentity?> {
                identityCalls++
                val captured = self
                afterIdentity()
                return identityFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }

            override suspend fun retain(
                access: CollaborationLocalAccess,
                identity: CollaborationSelfIdentity,
            ): AppResult<Boolean> = error("Projection never enrolls or retains identity")
        }
    val prepare =
        PrepareCollaborationMessageNotification(
            settings.repo,
            read.repo,
            history,
            identities,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    fun preference(value: CollaborationNotificationSettings) {
        settings.state =
            CollaborationNotificationSettingsState(
                CollaborationNotificationSettingsRevision(CollaborationReadStateFixture.id(500), 0),
                CollaborationReadStateFixture.id(501),
                value,
            )
    }

    suspend fun project(scope: CollaborationLocalScope.Captured = settings.scope()) =
        prepare(scope, conversation, room, event)
}
