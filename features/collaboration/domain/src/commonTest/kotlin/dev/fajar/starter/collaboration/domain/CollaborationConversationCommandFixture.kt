package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.time.Clock

internal const val COMMAND_PEER = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
internal const val COMMAND_ROOM = "88888888-8888-4888-8888-888888888888"
internal const val COMMAND_DEVICE = "44444444-4444-4444-8444-444444444444"

internal class ConversationCommandFixture {
    val setup = CollaborationSetupFixture()
    var build = AppBuild(AppPlatform.Android, 5, "test", null)
    val values = mutableMapOf<String, CollaborationConversationCommandRecord>()
    val sent = mutableListOf<Pair<String, CollaborationConversationAction>>()
    var beforeStorage: suspend (String) -> Unit = {}
    var beforeRemote: suspend (String) -> Unit = {}
    var remoteFailure: Failure? = null
    var failOutcomeWrite = false
    var room =
        CollaborationDirectRoom(
            COMMAND_ROOM,
            null,
            CollaborationDirectRoomState.NOT_REQUESTED,
            null,
            null,
            false,
            null,
        )
    var conversation =
        CollaborationDirectConversation(
            COMMAND_ROOM,
            "company",
            "account",
            COMMAND_PEER,
            "account",
            0,
            0,
        )

    init {
        setup.onRead = { AppResult.Success(setup.device.copy(id = COMMAND_DEVICE)) }
    }

    val commands =
        object : CollaborationConversationCommandRepository {
            override suspend fun current(
                owner: CollaborationOwner,
                resourceId: String,
            ): AppResult<CollaborationConversationCommandRecord?> {
                val value =
                    values.values.singleOrNull { it.command.action.resourceId() == resourceId }
                beforeStorage("current")
                return AppResult.Success(value)
            }

            override suspend fun get(
                owner: CollaborationOwner,
                commandId: String,
            ): AppResult<CollaborationConversationCommandRecord?> {
                val value = values[commandId]
                beforeStorage("get")
                return AppResult.Success(value)
            }

            override suspend fun insert(
                owner: CollaborationOwner,
                command: CollaborationConversationCommand,
            ): AppResult<CollaborationSetupInsertion> {
                beforeStorage("insert")
                if (
                    values.values.any {
                        it.command.action.resourceId() == command.action.resourceId()
                    }
                )
                    return AppResult.Success(CollaborationSetupInsertion.OCCUPIED)
                values[command.id] =
                    CollaborationConversationCommandRecord(
                        command,
                        0,
                        0,
                        CollaborationConversationOutcome.Pending,
                    )
                return AppResult.Success(CollaborationSetupInsertion.STORED)
            }

            override suspend fun update(
                owner: CollaborationOwner,
                expected: CollaborationConversationCommandRecord,
                outcome: CollaborationConversationOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                beforeStorage("update")
                if (
                    failOutcomeWrite &&
                        (outcome is CollaborationConversationOutcome.Confirmed ||
                            (outcome is CollaborationConversationOutcome.Unconfirmed &&
                                outcome.failure != null))
                )
                    return AppResult.Failed(Failure(FailureKind.Storage, "Failed write"))
                if (values[expected.command.id] != expected) return AppResult.Success(false)
                values[expected.command.id] =
                    expected.copy(
                        revision = expected.revision + 1,
                        attempts = attempts,
                        outcome = outcome,
                    )
                return AppResult.Success(true)
            }

            override suspend fun remove(
                owner: CollaborationOwner,
                expected: CollaborationConversationCommandRecord,
            ): AppResult<Boolean> {
                beforeStorage("remove")
                if (values[expected.command.id] != expected) return AppResult.Success(false)
                values.remove(expected.command.id)
                return AppResult.Success(true)
            }
        }
    val remote =
        object : CollaborationConversationRepository {
            override suspend fun summaries(
                owner: CollaborationOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationDirectSummary>> =
                error("Commands do not read summary pages")

            override suspend fun summary(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary> = error("Commands do not read summaries")

            override suspend fun participants(
                owner: CollaborationOwner,
                query: String,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationParticipant>> = error("No discovery")

            override suspend fun list(
                owner: CollaborationOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationDirectConversation>> = error("No list")

            override suspend fun get(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectConversation> {
                beforeRemote("get")
                return remoteFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(conversation)
            }

            override suspend fun room(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectRoom> {
                beforeRemote("room")
                return remoteFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(room)
            }

            override suspend fun open(
                owner: CollaborationOwner,
                command: CollaborationOpenDirect,
            ): AppResult<CollaborationSetupReceipt> {
                sent +=
                    command.operationId to
                        CollaborationConversationAction.Open(command.peerAccountId)
                beforeRemote("open")
                return remoteFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(CollaborationSetupReceipt(COMMAND_ROOM, 0))
            }

            override suspend fun requestRoom(
                owner: CollaborationOwner,
                command: CollaborationRequestDirectRoom,
            ): AppResult<CollaborationSetupReceipt> {
                sent +=
                    command.operationId to
                        CollaborationConversationAction.RequestRoom(
                            command.conversationId,
                            command.deviceId,
                            command.deviceVersion,
                            command.provisioningVersion,
                        )
                beforeRemote("requestRoom")
                return remoteFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(
                        CollaborationSetupReceipt(
                            COMMAND_ROOM,
                            (command.provisioningVersion ?: -1) + 1,
                        )
                    )
            }
        }
    private val server = IdentityServer(setup.initial.binding.audience, "Fictional Android")
    private val clock =
        object : Clock {
            override fun now() = setup.now
        }

    fun prepare() =
        PrepareCollaborationConversation(
            commands,
            remote,
            setup.remote,
            setup.sessions,
            setup.workspaces,
            server,
            build,
            clock,
            setup.admission.repository,
        )

    fun replay() =
        ReplayCollaborationConversation(
            commands,
            remote,
            setup.sessions,
            setup.workspaces,
            server,
            build,
            clock,
            setup.admission.repository,
        )

    fun reconcile() =
        ReconcileCollaborationConversation(
            commands,
            remote,
            setup.sessions,
            setup.workspaces,
            server,
            build,
            clock,
            setup.admission.repository,
        )
}
