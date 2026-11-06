package dev.fajar.starter.collaboration.data.di

import dev.fajar.starter.collaboration.data.repositories.UnavailableCollaborationRuntimeRepository
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
    fun observeSpaceSetup(
        commands: CollaborationSpaceSetupCommandRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = ObserveCollaborationSpaceSetup(commands, sessions, server)

    @Factory
    fun prepareSpaceSetup(
        commands: CollaborationSpaceSetupCommandRepository,
        registrations: CollaborationSpaceRegistrationCommandRepository,
        identities: CollaborationSpaceIdentityRepository,
        devices: CollaborationSpaceDeviceRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationSpaceSetup(
            commands,
            registrations,
            identities,
            devices,
            directory,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Single
    fun replaySpaceSetup(
        commands: CollaborationSpaceSetupCommandRepository,
        registrations: CollaborationSpaceRegistrationCommandRepository,
        identities: CollaborationSpaceIdentityRepository,
        devices: CollaborationSpaceDeviceRepository,
        setup: CollaborationSpaceSetupRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReplayCollaborationSpaceSetup(
            commands,
            registrations,
            identities,
            devices,
            setup,
            directory,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun reconcileSpaceRetry(
        commands: CollaborationSpaceSetupCommandRepository,
        registrations: CollaborationSpaceRegistrationCommandRepository,
        devices: CollaborationSpaceDeviceRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReconcileCollaborationSpaceRetry(
            commands,
            registrations,
            devices,
            directory,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun observeSpaceRegistrations(
        commands: CollaborationSpaceRegistrationCommandRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = ObserveCollaborationSpaceRegistrations(commands, sessions, server)

    @Factory
    fun prepareSpaceRegistration(
        commands: CollaborationSpaceRegistrationCommandRepository,
        identities: CollaborationSpaceIdentityRepository,
        devices: CollaborationSpaceDeviceRepository,
        local: CollaborationLocalDeviceIdentityRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationSpaceRegistration(
            commands,
            identities,
            devices,
            local,
            directory,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Single
    fun replaySpaceRegistration(
        commands: CollaborationSpaceRegistrationCommandRepository,
        devices: CollaborationSpaceDeviceRepository,
        local: CollaborationLocalDeviceIdentityRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReplayCollaborationSpaceRegistration(
            commands,
            devices,
            local,
            directory,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun readSpaceIdentity(
        identities: CollaborationSpaceIdentityRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReadCollaborationSpaceIdentity(identities, directory, sessions, server, build, Clock.System)

    @Single
    fun localDeviceIdentity(
        runtime: CollaborationRuntimeRepository
    ): CollaborationLocalDeviceIdentityRepository =
        runtime as? CollaborationLocalDeviceIdentityRepository
            ?: UnavailableCollaborationRuntimeRepository()

    @Factory
    fun observeSpaceIdentityCommand(
        commands: CollaborationSpaceIdentityCommandRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = ObserveCollaborationSpaceIdentityCommand(commands, sessions, server)

    @Factory
    fun prepareSpaceIdentity(
        commands: CollaborationSpaceIdentityCommandRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = PrepareCollaborationSpaceIdentity(commands, sessions, server, build, Clock.System)

    @Single
    fun replaySpaceIdentity(
        commands: CollaborationSpaceIdentityCommandRepository,
        identities: CollaborationSpaceIdentityRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReplayCollaborationSpaceIdentity(
            commands,
            identities,
            directory,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun observeKnownSpace(
        roots: CollaborationSpaceRootRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = ObserveKnownCollaborationSpace(roots, sessions, server)

    @Factory
    fun publishKnownSpace(
        roots: CollaborationSpaceRootRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = PublishKnownCollaborationSpace(roots, sessions, server, build, Clock.System)

    @Factory
    fun observeSpaceDirectory(
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
    ) = ObserveCollaborationSpaceDirectory(directory, sessions, server)

    @Single
    fun refreshSpaceDirectory(
        directory: CollaborationSpaceDirectoryRepository,
        spaces: CollaborationSpaceRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = RefreshCollaborationSpaceDirectory(directory, spaces, sessions, server, build, Clock.System)

    @Factory
    fun discoverSpace(
        spaces: CollaborationSpaceRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = DiscoverCollaborationSpace(spaces, sessions, server, build, Clock.System)

    @Factory
    fun readSpaceContacts(
        spaces: CollaborationSpaceRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReadCollaborationSpaceContacts(spaces, sessions, server, build, Clock.System)

    @Factory
    fun readSpaceDeviceStatus(
        devices: CollaborationSpaceDeviceRepository,
        directory: CollaborationSpaceDirectoryRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationSpaceDeviceStatus(
            devices,
            directory,
            sessions,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun saveReplyDraft(
        inputs: CollaborationNotificationReplyRepository,
        resolutions: CollaborationNotificationReplyResolutionRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        SaveCollaborationReplyDraft(
            inputs,
            resolutions,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun confirmReply(
        inputs: CollaborationNotificationReplyRepository,
        resolutions: CollaborationNotificationReplyResolutionRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ConfirmCollaborationReply(
            inputs,
            resolutions,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun resumeReviewedReply(
        inputs: CollaborationNotificationReplyRepository,
        messages: CollaborationMessageRepository,
        cancellations: CollaborationMessageCancellationRepository,
        prevention: CollaborationMessagePreventionRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        schedules: SyncScheduleRepository,
    ) =
        ResumeCollaborationReviewedReply(
            inputs,
            messages,
            cancellations,
            prevention,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            schedules,
        )

    @Factory
    fun observeSavedReplies(
        inputs: CollaborationNotificationReplyRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ObserveCollaborationSavedReplies(
            inputs,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun discardNotificationReply(
        inputs: CollaborationNotificationReplyRepository,
        messages: CollaborationMessageRepository,
        cancellations: CollaborationMessageCancellationRepository,
        prevention: CollaborationMessagePreventionRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        DiscardCollaborationNotificationReply(
            inputs,
            messages,
            cancellations,
            prevention,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun beginNotificationInputRecovery(
        cursors: CollaborationNotificationInputRecoveryRepository,
        inputs: CollaborationNotificationInputRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = BeginCollaborationNotificationInputRecovery(cursors, inputs, sessions, server, build)

    @Factory
    fun advanceNotificationInputRecovery(
        cursors: CollaborationNotificationInputRecoveryRepository,
        inputs: CollaborationNotificationInputRepository,
        sessions: SessionRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = AdvanceCollaborationNotificationInputRecovery(cursors, inputs, sessions, server, build)

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
    fun synchronizeDevice(
        runtimes: CollaborationRuntimeRepository,
        setup: CollaborationSetupRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        SynchronizeCollaborationDevice(
            runtimes,
            setup,
            lists,
            sessions,
            workspaces,
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
        edits: CollaborationEditEvidenceRepository,
        editTargets: CollaborationEditTargetRepository,
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
            edits,
            editTargets,
        )

    @Single
    fun recoverMessageReceipts(
        edits: CollaborationEditEvidenceRepository,
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
            edits,
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
        edits: CollaborationMessageEditRepository,
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
            edits,
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
        edits: CollaborationMessageEditRepository,
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
            edits,
        )

    @Factory
    fun forwardTargets(
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ReadCollaborationForwardTargets(lists, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun prepareForward(
        quotes: CollaborationQuoteRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationForward(
            quotes,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Single
    fun queueForward(
        batches: CollaborationMessageBatchRepository,
        messages: CollaborationMessageRepository,
        quotes: CollaborationQuoteRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        schedules: SyncScheduleRepository,
    ) =
        QueueCollaborationForward(
            batches,
            messages,
            quotes,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            schedules,
        )

    @Factory
    fun observeQuotes(
        quotes: CollaborationQuoteRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) = ObserveCollaborationQuotes(quotes, lists, sessions, workspaces, server, build, Clock.System)

    @Factory
    fun observeThread(
        history: CollaborationHistoryRepository,
        messages: CollaborationMessageRepository,
        lists: CollaborationListRepository,
        identities: CollaborationIdentityRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ObserveCollaborationThread(
            history,
            messages,
            lists,
            identities,
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

    @Factory
    fun observeHiddenMessages(
        visibility: CollaborationLocalVisibilityRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ObserveCollaborationHiddenMessages(
            visibility,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

    @Factory
    fun deleteMessagesForMe(
        visibility: CollaborationLocalVisibilityRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        DeleteCollaborationMessagesForMe(
            visibility,
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
    fun settleCompletedQueue(
        messages: CollaborationMessageRepository,
        completions: CollaborationMessageCompletionRepository,
        lists: CollaborationListRepository,
        runtimes: CollaborationRuntimeRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        SettleCollaborationCompletedQueue(
            messages,
            completions,
            lists,
            runtimes,
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

    @Single
    fun queueEdit(
        messages: CollaborationMessageRepository,
        targets: CollaborationEditTargetRepository,
        identities: CollaborationIdentityRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
        schedules: SyncScheduleRepository,
    ) =
        QueueCollaborationEdit(
            messages,
            targets,
            identities,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
            schedules,
        )

    @Factory
    fun readEditCandidate(
        targets: CollaborationEditTargetRepository,
        identities: CollaborationIdentityRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationEditCandidate(
            targets,
            identities,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
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
    fun observeInbox(
        replicas: CollaborationListRepository,
        history: CollaborationHistoryRepository,
        drafts: CollaborationDraftRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ObserveCollaborationInbox(
            replicas,
            history,
            drafts,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

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
    fun syncList(
        remote: CollaborationSummarySyncRepository,
        cache: CollaborationListSyncReplicaRepository,
        reads: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        SyncCollaborationList(
            remote,
            cache,
            reads,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )

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

    @Factory
    fun prepareRoom(
        commands: CollaborationConversationCommandRepository,
        conversations: CollaborationConversationRepository,
        messages: CollaborationMessageRepository,
        setup: CollaborationSetupRepository,
        schedules: SyncScheduleRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        PrepareCollaborationRoom(
            commands,
            conversations,
            messages,
            setup,
            schedules,
            lists,
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

    @Factory
    fun readEditOutcomes(
        evidence: CollaborationEditEvidenceRepository,
        completions: CollaborationMessageCompletionRepository,
        lists: CollaborationListRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        server: IdentityServer,
        build: AppBuild,
    ) =
        ReadCollaborationEditOutcomes(
            evidence,
            completions,
            lists,
            sessions,
            workspaces,
            server,
            build,
            Clock.System,
        )
}
