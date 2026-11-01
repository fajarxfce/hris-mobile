package dev.fajar.starter.collaboration.data.di

import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.collaboration.data")
class CollaborationDataModule {
    @Factory
    fun advanceDelivery(
        cursors: CollaborationDeliveryCursorRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        AdvanceCollaborationDelivery(
            cursors,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readDeliveryWork(
        messages: CollaborationMessageRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        cursors: CollaborationDeliveryCursorRepository,
    ) =
        ReadCollaborationDeliveryWork(
            messages,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            cursors,
        )

    @Single
    fun retireMessages(
        messages: CollaborationMessageRepository,
        completions: CollaborationMessageCompletionRepository,
        receipts: CollaborationReceiptRepository,
        retirement: CollaborationReceiptRetirementRepository,
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        RetireCollaborationMessages(
            messages,
            completions,
            receipts,
            retirement,
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun recoverMessageReceipts(
        completions: CollaborationMessageCompletionRepository,
        history: CollaborationHistoryRepository,
        messages: CollaborationMessageRepository,
        receipts: CollaborationReceiptRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        RecoverCollaborationMessageReceipts(
            history,
            messages,
            receipts,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            completions,
        )

    @Single
    fun reopenHistory(
        runtimes: CollaborationRuntimeRepository,
        lists: CollaborationListRepository,
        conversations: CollaborationConversationRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReopenCollaborationHistory(
            runtimes,
            lists,
            conversations,
            setup,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun reconcileHistoryReferences(
        traversal: CollaborationHistoryTraversalRepository,
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReconcileCollaborationHistoryReferences(
            traversal,
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun acquireProviderHistory(
        history: CollaborationHistoryTraversalRepository,
        lists: CollaborationListRepository,
        conversations: CollaborationConversationRepository,
        setup: CollaborationSetupRepository,
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        AcquireCollaborationProviderHistory(
            history,
            lists,
            conversations,
            setup,
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun acquireHistory(
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        conversations: CollaborationConversationRepository,
        setup: CollaborationSetupRepository,
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        AcquireCollaborationHistory(
            history,
            lists,
            conversations,
            setup,
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun observeHistory(
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ObserveCollaborationHistory(
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun deliverMessages(
        completions: CollaborationMessageCompletionRepository,
        messages: CollaborationMessageRepository,
        receipts: CollaborationReceiptRepository,
        lists: CollaborationListRepository,
        conversations: CollaborationConversationRepository,
        setup: CollaborationSetupRepository,
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        DeliverCollaborationMessages(
            messages,
            receipts,
            lists,
            conversations,
            setup,
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            completions,
        )

    @Single
    fun reconcileMessages(
        completions: CollaborationMessageCompletionRepository,
        messages: CollaborationMessageRepository,
        receipts: CollaborationReceiptRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReconcileCollaborationMessages(
            messages,
            receipts,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            completions,
        )

    @Single
    fun stageText(
        messages: CollaborationMessageRepository,
        lists: CollaborationListRepository,
        conversations: CollaborationConversationRepository,
        setup: CollaborationSetupRepository,
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        StageCollaborationText(
            messages,
            lists,
            conversations,
            setup,
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun queueText(
        messages: CollaborationMessageRepository,
        conversations: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        schedules: SyncScheduleRepository,
    ) =
        QueueCollaborationText(
            messages,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            schedules,
        )

    @Factory
    fun requestDelivery(
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        schedules: SyncScheduleRepository,
    ) =
        RequestCollaborationDelivery(
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            schedules,
        )

    @Factory
    fun observeMessages(
        messages: CollaborationMessageRepository,
        conversations: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ObserveCollaborationMessages(
            messages,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun ensureRuntime(
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = EnsureCollaborationRuntime(runtimes, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun observeList(
        replicas: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ObserveCollaborationList(replicas, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun readThreadContext(
        conversations: CollaborationListRepository,
        identities: CollaborationIdentityRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationThreadContext(
            conversations,
            identities,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readIdentity(
        identities: CollaborationIdentityRepository,
        runtimes: CollaborationRuntimeRepository,
        admission: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationIdentity(
            identities,
            runtimes,
            admission,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readControl(
        setupCommands: CollaborationSetupCommandRepository,
        conversationCommands: CollaborationConversationCommandRepository,
        admission: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationControl(
            setupCommands,
            conversationCommands,
            admission,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun observeAccess(
        replicas: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ObserveCollaborationAccess(replicas, sessions, workspaces, server, build, Clock.System)

    @Single
    fun refreshList(
        replicas: CollaborationListRepository,
        conversations: CollaborationConversationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        RefreshCollaborationList(
            replicas,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun enterConversation(
        runtimes: CollaborationRuntimeRepository,
        conversations: CollaborationConversationRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        lists: CollaborationListRepository,
    ) =
        EnterCollaborationConversation(
            runtimes,
            conversations,
            setup,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            lists,
        )

    @Factory
    fun prepareConversation(
        commands: CollaborationConversationCommandRepository,
        conversations: CollaborationConversationRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationConversation(
            commands,
            conversations,
            setup,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun replayConversation(
        commands: CollaborationConversationCommandRepository,
        conversations: CollaborationConversationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReplayCollaborationConversation(
            commands,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun reconcileConversation(
        commands: CollaborationConversationCommandRepository,
        conversations: CollaborationConversationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReconcileCollaborationConversation(
            commands,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readConversations(
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        conversations: CollaborationConversationRepository,
        server: IdentityServer,
        build: AppBuild,
        replicas: CollaborationListRepository,
    ) =
        ReadCollaborationConversations(
            sessions,
            workspaces,
            conversations,
            server,
            build,
            Clock.System,
            replicas,
        )

    @Single
    fun maintainRuntimeOwnership(
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        MaintainCollaborationRuntimeOwnership(
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun restoreRuntime(
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = RestoreCollaborationRuntime(runtimes, sessions, workspaces, server, build, Clock.System)

    @Single
    fun installRuntime(
        commands: CollaborationSetupCommandRepository,
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        InstallCollaborationRuntime(
            commands,
            runtimes,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun loadSetup(
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        setup: CollaborationSetupRepository,
        server: IdentityServer,
        build: AppBuild,
        replicas: CollaborationListRepository,
    ) = LoadCollaborationSetup(sessions, workspaces, setup, server, build, Clock.System, replicas)

    @Factory
    fun prepareSetup(
        commands: CollaborationSetupCommandRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationSetup(
            commands,
            setup,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun replaySetup(
        commands: CollaborationSetupCommandRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReplayCollaborationSetup(commands, setup, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun reconcileSetup(
        commands: CollaborationSetupCommandRepository,
        setup: CollaborationSetupRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReconcileCollaborationSetupReceipt(
            commands,
            setup,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )
}
