package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class CollaborationNotificationInvocationFixture {
    val read = CollaborationReadStateFixture()
    val local = read.local

    init {
        val session = local.setup.session!!
        local.setup.session = session.copy(binding = session.binding.copy(serverId = id(900)))
    }

    val parent =
        local.setup.session!!.let {
            CollaborationNativeParent(it.id, it.binding.audience, it.user.id, it.binding.serverId)
        }
    val target =
        CollaborationNotificationActionTarget(
            read.scope().access.companyId,
            read.conversationId,
            read.roomId,
            read.ids.first(),
            id(901),
            id(902),
        )
    var token = CollaborationNotificationActionToken("v1.fixture-opaque-token")
    var action: CollaborationNotificationAction =
        CollaborationNotificationAction.Reply(id(903), target)
    var issuingParent = parent
    var opens = 0
    var openFailure: Failure? = null
    var afterOpen: suspend () -> Unit = {}
    val actions =
        object : CollaborationNotificationActionRepository {
            override suspend fun seal(
                parent: CollaborationNativeParent,
                action: CollaborationNotificationAction,
            ): AppResult<CollaborationNotificationActionToken> =
                error("Callback never mints tokens")

            override suspend fun recover(
                parent: CollaborationNativeParent,
                token: CollaborationNotificationActionToken,
            ): AppResult<CollaborationAuthenticatedNotificationAction> {
                val current = local.setup.session!!
                assertEquals(
                    CollaborationNativeParent(
                        current.id,
                        current.binding.audience,
                        current.user.id,
                        current.binding.serverId,
                    ),
                    parent,
                )
                assertEquals(this@CollaborationNotificationInvocationFixture.token, token)
                opens++
                val captured = CollaborationAuthenticatedNotificationAction(issuingParent, action)
                afterOpen()
                return openFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }

            override suspend fun open(
                parent: CollaborationNativeParent,
                token: CollaborationNotificationActionToken,
            ): AppResult<CollaborationNotificationAction> {
                assertEquals(this@CollaborationNotificationInvocationFixture.parent, parent)
                assertEquals(this@CollaborationNotificationInvocationFixture.token, token)
                opens++
                val captured = action
                afterOpen()
                return openFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }
        }

    fun opener(platform: AppPlatform = AppPlatform.Android) =
        OpenCollaborationNotificationAction(
            actions,
            local.lists,
            read.base.history,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build.copy(platform = platform),
            local.clock,
        )

    suspend fun open() = opener()(token)

    fun replaceFamily() {
        val session = local.setup.session!!
        local.setup.session = session.copy(binding = session.binding.copy(serverId = id(904)))
    }

    fun scope() = read.scope().copy(nativeSessionId = parent.nativeSessionId)

    fun queue() =
        QueueCollaborationText(
            local.messages,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
            local.schedules,
        )

    companion object {
        fun id(value: Int) = CollaborationReadStateFixture.id(value)
    }
}
