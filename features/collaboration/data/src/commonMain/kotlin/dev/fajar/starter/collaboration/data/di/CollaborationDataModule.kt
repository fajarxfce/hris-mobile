package dev.fajar.starter.collaboration.data.di

import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.collaboration.data")
class CollaborationDataModule {
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
        )

    @Single
    fun reconcileMessages(
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
    ) =
        QueueCollaborationText(
            messages,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
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
    ) =
        ReadCollaborationConversations(
            sessions,
            workspaces,
            conversations,
            server,
            build,
            Clock.System,
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
    ) = LoadCollaborationSetup(sessions, workspaces, setup, server, build, Clock.System)

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
