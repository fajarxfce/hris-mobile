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
    @Single
    fun protectNotificationReply(inputs: CollaborationNotificationInputRepository) =
        ProtectCollaborationNotificationReply(inputs)

    @Single
    fun transferNotificationInput(
        inputs: CollaborationNotificationInputRepository,
        actions: CollaborationNotificationActionRepository,
        replies: CollaborationNotificationReplyRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = TransferCollaborationNotificationInput(inputs, actions, replies, sessions, server, build)

    @Factory
    fun observeNotificationChanges(
        changes: CollaborationNotificationChangeRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ObserveCollaborationNotificationChanges(changes, lists, sessions, server, build)

    @Factory
    fun beginNotificationReplyRecoveryCycle(
        cursors: CollaborationNotificationReplyRecoveryCursorRepository,
        inputs: CollaborationNotificationReplyRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = BeginCollaborationNotificationReplyRecoveryCycle(cursors, inputs, sessions, server, build)

    @Factory
    fun settleNotificationReplyRecoveryCycle(
        cursors: CollaborationNotificationReplyRecoveryCursorRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = SettleCollaborationNotificationReplyRecoveryCycle(cursors, sessions, server, build)

    @Factory
    fun finishNotificationReplyRecoveryCycle(
        cursors: CollaborationNotificationReplyRecoveryCursorRepository,
        inputs: CollaborationNotificationReplyRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = FinishCollaborationNotificationReplyRecoveryCycle(cursors, inputs, sessions, server, build)

    @Factory
    fun readNotificationReplyRecovery(
        inputs: CollaborationNotificationReplyRepository,
        cursors: CollaborationNotificationReplyRecoveryCursorRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReadCollaborationNotificationReplyRecovery(inputs, cursors, sessions, server, build)

    @Factory
    fun advanceNotificationReplyRecovery(
        cursors: CollaborationNotificationReplyRecoveryCursorRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = AdvanceCollaborationNotificationReplyRecovery(cursors, sessions, server, build)

    @Factory
    fun readNotificationReply(
        inputs: CollaborationNotificationReplyRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationNotificationReply(
            inputs,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun reviewNotificationReply(
        inputs: CollaborationNotificationReplyRepository,
        messages: CollaborationMessageRepository,
        lists: CollaborationListRepository,
        history: CollaborationHistoryRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReviewCollaborationNotificationReply(
            inputs,
            messages,
            lists,
            history,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun completeNotificationReply(
        inputs: CollaborationNotificationReplyRepository,
        messages: CollaborationMessageRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        CompleteCollaborationNotificationReply(
            inputs,
            messages,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun retainNotificationReply(
        actions: CollaborationNotificationActionRepository,
        inputs: CollaborationNotificationReplyRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = RetainCollaborationNotificationReply(actions, inputs, sessions, server, build)

    @Factory
    fun openNotificationAction(
        actions: CollaborationNotificationActionRepository,
        lists: CollaborationListRepository,
        history: CollaborationHistoryRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        OpenCollaborationNotificationAction(
            actions,
            lists,
            history,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun cancelNotificationPublication(
        journal: CollaborationNotificationPublicationRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = CancelCollaborationNotificationPublication(journal, sessions, server, build)

    @Factory
    fun readNotificationPublications(
        journal: CollaborationNotificationPublicationRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReadCollaborationNotificationPublications(journal, sessions, server, build)

    @Single
    fun finishNotificationPublication(
        journal: CollaborationNotificationPublicationRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = FinishCollaborationNotificationPublication(journal, sessions, server, build)

    @Factory
    fun validateNotificationPublication(
        journal: CollaborationNotificationPublicationRepository,
        actions: CollaborationNotificationActionRepository,
        settings: CollaborationNotificationSettingsRepository,
        reads: CollaborationReadStateRepository,
        history: CollaborationHistoryRepository,
        identities: CollaborationIdentityRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ValidateCollaborationNotificationPublication(
            journal,
            actions,
            settings,
            reads,
            history,
            identities,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun issueMessageNotification(
        journal: CollaborationNotificationPublicationRepository,
        actions: CollaborationNotificationActionRepository,
        settings: CollaborationNotificationSettingsRepository,
        reads: CollaborationReadStateRepository,
        history: CollaborationHistoryRepository,
        identities: CollaborationIdentityRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        IssueCollaborationMessageNotification(
            journal,
            actions,
            settings,
            reads,
            history,
            identities,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun prepareMutedNotification(
        journal: CollaborationNotificationPublicationRepository,
        settings: CollaborationNotificationSettingsRepository,
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationMutedNotification(
            journal,
            settings,
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun commitMutedNotification(
        journal: CollaborationNotificationPublicationRepository,
        settings: CollaborationNotificationSettingsRepository,
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        CommitCollaborationMutedNotification(
            journal,
            settings,
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun prepareMessageNotification(
        settings: CollaborationNotificationSettingsRepository,
        reads: CollaborationReadStateRepository,
        history: CollaborationHistoryRepository,
        identities: CollaborationIdentityRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationMessageNotification(
            settings,
            reads,
            history,
            identities,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readNotificationSettings(
        settings: CollaborationNotificationSettingsRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationNotificationSettings(
            settings,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun updateNotificationSettings(
        settings: CollaborationNotificationSettingsRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        UpdateCollaborationNotificationSettings(
            settings,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun deliverReadReceipts(
        states: CollaborationReadStateRepository,
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
        DeliverCollaborationReadReceipts(
            states,
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
    fun readReadState(
        states: CollaborationReadStateRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReadCollaborationReadState(states, lists, sessions, workspaces, server, build, Clock.System)

    @Single
    fun markMessagesRead(
        states: CollaborationReadStateRepository,
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        MarkCollaborationMessagesRead(
            states,
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun queuePushSignal(
        sessions: SessionRepository,
        schedules: SyncScheduleRepository,
        wakes: CollaborationRecoveryWakeRepository,
        server: IdentityServer,
        build: AppBuild,
        priorities: CollaborationPushPriorityRepository,
    ) = QueueCollaborationPushSignal(sessions, schedules, wakes, server, build, priorities)

    @Factory
    fun readPushPriority(
        priorities: CollaborationPushPriorityRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReadCollaborationPushPriority(priorities, sessions, server, build)

    @Factory
    fun completePushPriority(
        priorities: CollaborationPushPriorityRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = CompleteCollaborationPushPriority(priorities, sessions, server, build)

    @Factory
    fun readHistoryEvent(
        history: CollaborationHistoryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationHistoryEvent(
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readRoomRecovery(
        progress: CollaborationRoomRecoveryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = ReadCollaborationRoomRecovery(progress, lists, sessions, server)

    @Factory
    fun updateRoomRecovery(
        progress: CollaborationRoomRecoveryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = UpdateCollaborationRoomRecovery(progress, lists, sessions, server)

    @Factory
    fun ensureRecoveryWake(wakes: CollaborationRecoveryWakeRepository) =
        EnsureCollaborationRecoveryWake(wakes)

    @Factory
    fun readRecoveryProgress(
        progress: CollaborationRecoveryProgressRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = ReadCollaborationRecoveryProgress(progress, sessions, server)

    @Factory
    fun updateRecoveryProgress(
        progress: CollaborationRecoveryProgressRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = UpdateCollaborationRecoveryProgress(progress, sessions, server)

    @Factory
    fun requestRecovery(
        wakes: CollaborationRecoveryWakeRepository,
        schedules: SyncScheduleRepository,
    ) = RequestCollaborationRecovery(wakes, schedules)

    @Factory
    fun resumeRecovery(
        wakes: CollaborationRecoveryWakeRepository,
        schedules: SyncScheduleRepository,
    ) = ResumeCollaborationRecovery(wakes, schedules)

    @Factory
    fun readRecoveryWake(wakes: CollaborationRecoveryWakeRepository) =
        ReadCollaborationRecoveryWake(wakes)

    @Factory
    fun completeRecoveryWake(wakes: CollaborationRecoveryWakeRepository) =
        CompleteCollaborationRecoveryWake(wakes)

    @Single
    fun loadDevicePage(
        recovery: CollaborationRecoveryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = LoadCollaborationDevicePage(recovery, sessions, server, build, Clock.System)

    @Single
    fun acquireBackgroundAdmission(
        recovery: CollaborationRecoveryRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        AcquireCollaborationBackgroundAdmission(
            recovery,
            lists,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Single
    fun activatePusher(
        runtimes: CollaborationRuntimeRepository,
        setup: CollaborationSetupRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ActivateCollaborationPusher(
            runtimes,
            setup,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readDraftRecovery(
        drafts: CollaborationDraftRepository,
        cursors: CollaborationDraftRecoveryCursorRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationDraftRecovery(
            drafts,
            cursors,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun advanceDraftRecovery(
        cursors: CollaborationDraftRecoveryCursorRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        AdvanceCollaborationDraftRecovery(
            cursors,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun suspendDraftSend(
        drafts: CollaborationDraftRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        messages: CollaborationMessageRepository,
    ) =
        SuspendCollaborationDraftSend(
            drafts,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            messages,
        )

    @Factory
    fun observeDraft(
        drafts: CollaborationDraftRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ObserveCollaborationDraft(drafts, lists, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun editDraft(
        drafts: CollaborationDraftRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = EditCollaborationDraft(drafts, lists, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun prepareDraftSend(
        drafts: CollaborationDraftRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        schedules: SyncScheduleRepository,
    ) =
        PrepareCollaborationDraftSend(
            drafts,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            schedules,
        )

    @Factory
    fun confirmDraftSend(
        drafts: CollaborationDraftRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        messages: CollaborationMessageRepository,
    ) =
        ConfirmCollaborationDraftSend(
            drafts,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            messages,
        )

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
    fun readHistoryReferences(
        traversal: CollaborationHistoryTraversalRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationHistoryReferences(
            traversal,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun advanceIncomingReferences(
        traversal: CollaborationHistoryTraversalRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        AdvanceCollaborationIncomingReferences(
            traversal,
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
    fun cancelText(
        cancellations: CollaborationMessageCancellationRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        CancelCollaborationText(
            cancellations,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
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
        lists: CollaborationListRepository,
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
            lists,
        )

    @Single
    fun replayConversation(
        commands: CollaborationConversationCommandRepository,
        conversations: CollaborationConversationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        lists: CollaborationListRepository,
    ) =
        ReplayCollaborationConversation(
            commands,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            lists,
        )

    @Factory
    fun reconcileConversation(
        commands: CollaborationConversationCommandRepository,
        conversations: CollaborationConversationRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        lists: CollaborationListRepository,
    ) =
        ReconcileCollaborationConversation(
            commands,
            conversations,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            lists,
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
    fun acquireBackgroundRuntime(
        runtimes: CollaborationRuntimeRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        AcquireCollaborationBackgroundRuntime(
            runtimes,
            lists,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Single
    fun maintainRuntimeOwnership(
        runtimes: CollaborationRuntimeRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        MaintainCollaborationRuntimeOwnership(
            runtimes,
            lists,
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
