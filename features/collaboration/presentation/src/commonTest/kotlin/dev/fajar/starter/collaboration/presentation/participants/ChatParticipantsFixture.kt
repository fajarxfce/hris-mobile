package dev.fajar.starter.collaboration.presentation.participants

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxFixture
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

internal class ChatParticipantsFixture {
    val base = ChatInboxFixture()
    val peers = (1..3).map { base.item(it).peer }
    val searches = mutableListOf<Pair<String, String?>>()
    val opens = mutableListOf<CollaborationOpenDirect>()
    val points = mutableListOf<String>()
    val retained = mutableMapOf<String, CollaborationConversationCommandRecord>()
    val remoteRecords = mutableMapOf<String, CollaborationDirectSummary>()
    var completedGateObservation = false
    var beforeGateObservation: suspend () -> Unit = {}
    var search:
        suspend (String, String?) -> AppResult<CollaborationPage<CollaborationParticipant>> =
        { _, _ ->
            AppResult.Success(CollaborationPage(peers, null))
        }
    var sendOpen: suspend (CollaborationOpenDirect) -> AppResult<CollaborationSetupReceipt> =
        ::commitOpen
    var point: suspend (String) -> AppResult<CollaborationDirectSummary> = {
        AppResult.Success(checkNotNull(remoteRecords[it]))
    }
    var direct: suspend (String) -> AppResult<CollaborationDirectConversation> = {
        AppResult.Success(checkNotNull(remoteRecords[it]).conversation)
    }

    fun commitOpen(command: CollaborationOpenDirect): AppResult<CollaborationSetupReceipt> {
        val index = peers.indexOfFirst { it.accountId == command.peerAccountId } + 1
        check(index > 0)
        val summary =
            base.item(index).let { it.copy(conversation = it.conversation.copy(version = 0)) }
        remoteRecords[summary.conversation.id] = summary
        return AppResult.Success(CollaborationSetupReceipt(summary.conversation.id, 0))
    }

    val local =
        object : CollaborationListRepository by base.local {
            override fun observeAdmission(
                access: CollaborationLocalAccess
            ): Flow<AppResult<CollaborationListAdmission>> = flow {
                beforeGateObservation()
                if (completedGateObservation) emit(AppResult.Failed(base.network))
                else emitAll(base.local.observeAdmission(access))
            }
        }
    val remote =
        object : CollaborationConversationRepository by base.remote {
            override suspend fun participants(
                owner: CollaborationOwner,
                query: String,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationParticipant>> {
                check(limit == 30)
                searches += query to after
                return search(query, after)
            }

            override suspend fun open(
                owner: CollaborationOwner,
                command: CollaborationOpenDirect,
            ): AppResult<CollaborationSetupReceipt> {
                opens += command
                return sendOpen(command)
            }

            override suspend fun summary(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary> {
                points += conversationId
                return point(conversationId)
            }

            override suspend fun get(owner: CollaborationOwner, conversationId: String) =
                direct(conversationId)
        }
    val commands =
        object : CollaborationConversationCommandRepository {
            override suspend fun current(owner: CollaborationOwner, resourceId: String) =
                AppResult.Success(
                    retained.values.singleOrNull {
                        it.command.binding == owner.setupBinding() &&
                            it.command.action.resourceId() == resourceId
                    }
                )

            override suspend fun get(owner: CollaborationOwner, commandId: String) =
                AppResult.Success(
                    retained[commandId]?.takeIf { it.command.binding == owner.setupBinding() }
                )

            override suspend fun insert(
                owner: CollaborationOwner,
                command: CollaborationConversationCommand,
            ): AppResult<CollaborationSetupInsertion> {
                if (
                    retained.values.any {
                        it.command.binding == command.binding &&
                            it.command.action.resourceId() == command.action.resourceId()
                    }
                )
                    return AppResult.Success(CollaborationSetupInsertion.OCCUPIED)
                retained[command.id] =
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
                if (
                    retained[expected.command.id] != expected ||
                        expected.command.binding != owner.setupBinding()
                )
                    return AppResult.Success(false)
                retained[expected.command.id] =
                    expected.copy(
                        revision = expected.revision + 1,
                        outcome = outcome,
                        attempts = attempts,
                    )
                return AppResult.Success(true)
            }

            override suspend fun remove(
                owner: CollaborationOwner,
                expected: CollaborationConversationCommandRecord,
            ): AppResult<Boolean> {
                if (
                    retained[expected.command.id] != expected ||
                        expected.command.binding != owner.setupBinding()
                )
                    return AppResult.Success(false)
                retained.remove(expected.command.id)
                return AppResult.Success(true)
            }
        }
    private val setup =
        object : CollaborationSetupRepository {
            override suspend fun read(
                owner: CollaborationOwner
            ): AppResult<CollaborationDeviceSetup?> = error("Opening a DM does not enroll a device")

            override suspend fun register(
                owner: CollaborationOwner,
                command: CollaborationRegistration,
            ): AppResult<CollaborationSetupReceipt> = error("No device enrollment")

            override suspend fun retry(
                owner: CollaborationOwner,
                command: CollaborationProvisioningRetry,
            ): AppResult<CollaborationSetupReceipt> = error("No device retry")

            override suspend fun bootstrap(
                owner: CollaborationOwner,
                command: CollaborationBootstrap,
            ): AppResult<CollaborationGateway> = error("No SDK bootstrap")
        }

    fun model() =
        ChatParticipantsViewModel(
            "selection",
            ObserveCollaborationAccess(
                local,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            ReadCollaborationConversations(
                base.sessions,
                base.workspaces,
                remote,
                base.server,
                base.build,
                base.clock,
                local,
            ),
            PrepareCollaborationConversation(
                commands,
                remote,
                setup,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            ReplayCollaborationConversation(
                commands,
                remote,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            RefreshCollaborationList(
                local,
                remote,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
            ReconcileCollaborationConversation(
                commands,
                remote,
                base.sessions,
                base.workspaces,
                base.server,
                base.build,
                base.clock,
            ),
        )
}
