package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationConversationCommandRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageRepository
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationRoom
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.Clock
import kotlinx.coroutines.flow.flow

internal class RoomPreparationFixture {
    val base = ConversationCommandFixture()
    val access =
        CollaborationLocalAccess("local", base.setup.initial.binding.audience, "account", "company")
    var records = emptyList<CollaborationMessageRecord>()
    var beforeMessages: suspend () -> Unit = {}
    var afterInsert: suspend () -> Unit = {}
    var messageReads = 0
    var activeObservers = 0
    var roomReads = 0
    var insertions = 0
    val scheduled = mutableListOf<String>()
    private val commands =
        object : CollaborationConversationCommandRepository by base.commands {
            override suspend fun insert(
                owner: CollaborationOwner,
                command: CollaborationConversationCommand,
            ): AppResult<CollaborationSetupInsertion> {
                insertions++
                val result = base.commands.insert(owner, command)
                afterInsert()
                return result
            }
        }
    private val messages =
        object : CollaborationMessageRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String?) = flow {
                messageReads++
                activeObservers++
                try {
                    beforeMessages()
                    emit(AppResult.Success(records.toList()))
                } finally {
                    activeObservers--
                }
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageRecord?> = error("No point message read")

            override suspend fun insert(
                access: CollaborationLocalAccess,
                intent: CollaborationMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("No message insertion")

            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("No message mutation")
        }
    private val schedules =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                scheduled += key
                return AppResult.Success(Unit)
            }
        }
    private val preparation =
        PrepareCollaborationRoom(
            commands,
            base.remote,
            messages,
            base.setup.remote,
            schedules,
            base.setup.admission.repository,
            base.setup.sessions,
            base.setup.workspaces,
            IdentityServer(base.setup.initial.binding.audience, "Fixture"),
            base.build,
            object : Clock {
                override fun now() = base.setup.now
            },
        )

    suspend fun prepare(retry: Boolean = false) =
        preparation("selection", access, COMMAND_ROOM, COMMAND_DEVICE, 1, retry)

    fun addPending() {
        records =
            listOf(
                CollaborationMessageRecord(
                    CollaborationMessageIntent(
                        "e6000000-0000-4000-8000-000000000001",
                        access.messageOwner(),
                        COMMAND_ROOM,
                        base.setup.now,
                        "Retained local message",
                    ),
                    0,
                    CollaborationMessageState.Queued,
                )
            )
    }

    fun failedRoom() {
        base.room =
            base.room.copy(
                version = 7,
                state = CollaborationDirectRoomState.FAILED,
                creatorAccountId = access.accountId,
                retryable = true,
                retryAt = base.setup.now.toEpochMilliseconds(),
            )
    }
}
