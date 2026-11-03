package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class CollaborationNotificationSettingsFixture {
    val base = CollaborationReadStateFixture()
    val local = base.local
    var state = CollaborationNotificationSettingsState()
    var reads = 0
    var writes = 0
    var readFailure: Failure? = null
    var writeResult: AppResult<Boolean> = AppResult.Success(true)
    var commitOnFailure = false
    var afterRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    val repo =
        object : CollaborationNotificationSettingsRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): kotlinx.coroutines.flow.Flow<AppResult<CollaborationNotificationSettingsState>> =
                error("This fixture exercises finite settings operations only")

            override suspend fun read(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationNotificationSettingsState> {
                assertEquals(base.conversationId, conversationId)
                reads++
                val captured = state
                afterRead()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }

            override suspend fun update(
                access: CollaborationLocalAccess,
                conversationId: String,
                change: CollaborationNotificationSettingsChange,
            ): AppResult<Boolean> {
                assertEquals(base.conversationId, conversationId)
                assertEquals(state.version, change.expected)
                writes++
                if (writeResult == AppResult.Success(true) || commitOnFailure)
                    state =
                        CollaborationNotificationSettingsState(
                            CollaborationNotificationSettingsRevision(
                                id(800),
                                (state.version?.revision ?: -1) + 1,
                            ),
                            change.operationId,
                            change.settings,
                        )
                afterWrite()
                return writeResult
            }
        }
    val reader =
        ReadCollaborationNotificationSettings(
            repo,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )
    val updater =
        UpdateCollaborationNotificationSettings(
            repo,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    fun scope() = base.scope()

    fun change(
        index: Int = 1,
        value: CollaborationNotificationSettings =
            CollaborationNotificationSettings(CollaborationNotificationMute.Forever),
    ) = CollaborationNotificationSettingsChange(id(index), state.version, value)

    suspend fun read(scope: CollaborationLocalScope = scope()) = reader(scope, base.conversationId)

    suspend fun update(
        change: CollaborationNotificationSettingsChange = change(),
        scope: CollaborationLocalScope = scope(),
    ) = updater(scope, base.conversationId, change)

    companion object {
        fun id(value: Int) = CollaborationReadStateFixture.id(value)
    }
}
