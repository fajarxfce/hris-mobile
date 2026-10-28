package dev.fajar.starter.inbox.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.push.repositories.NativePushStateRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.inbox.domain.push.usecases.*
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.notifications.domain.repositories.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.inbox.data")
class InboxDataModule {
    @Factory
    fun preparePush(
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        queue: InboxPushQueueRepository,
        server: IdentityServer,
        @Named("inbox-push-mutations") mutations: Mutex,
    ) = PrepareInboxPush(sessions, workspaces, queue, server, Clock.System, mutations)

    @Factory
    fun presentPush(
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        queue: InboxPushQueueRepository,
        remote: InboxRepository,
        cache: InboxCacheRepository,
        push: NativePushStateRepository,
        permissions: NotificationAccessRepository,
        delivery: NotificationDeliveryRepository,
        @Named("inbox-push-mutations") mutations: Mutex,
    ) =
        PresentInboxPush(
            sessions,
            workspaces,
            queue,
            remote,
            cache,
            push,
            permissions,
            delivery,
            Clock.System,
            mutations,
        )

    @Single @Named("inbox-push-mutations") fun pushMutations() = Mutex()

    @Factory
    fun queuePush(
        sessions: SessionRepository,
        queue: InboxPushQueueRepository,
        schedules: SyncScheduleRepository,
        server: IdentityServer,
        @Named("inbox-push-mutations") mutations: Mutex,
    ) = QueueInboxPushSignal(sessions, queue, schedules, server, Clock.System, mutations)

    @Factory
    fun unread(
        remote: InboxRepository,
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = LoadUnreadInbox(remote, cache, sessions, workspaces)

    @Factory
    fun prepareAction(
        commands: InboxCommandRepository,
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = PrepareInboxAction(commands, cache, sessions, workspaces, Clock.System)

    @Factory
    fun observeAction(
        commands: InboxCommandRepository,
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = ObserveInboxAction(commands, cache, sessions, workspaces)

    @Factory
    fun reviewAction(
        commands: InboxCommandRepository,
        remote: InboxRepository,
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = ReviewInboxAction(commands, remote, cache, sessions, workspaces, Clock.System)

    @Single(binds = [ReplayInboxAction::class, SyncTask::class])
    fun replayAction(
        commands: InboxCommandRepository,
        remote: InboxRepository,
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = ReplayInboxAction(commands, remote, cache, sessions, workspaces, Clock.System, build)

    @Factory(binds = [ObserveInbox::class, ObserveCompanyData::class])
    fun observe(
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = ObserveInbox(cache, sessions, workspaces)

    @Single(binds = [SyncInbox::class, SyncTask::class, SyncCompanyData::class])
    fun synchronize(
        feed: SyncFeedRepository,
        remote: InboxRepository,
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
        build: AppBuild,
    ) = SyncInbox(feed, remote, cache, sessions, workspaces, Clock.System, build)

    @Factory(binds = [ObservePendingActions::class])
    fun observeInboxPending(
        commands: InboxCommandRepository,
        reads: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = ObserveInboxPending(commands, reads, sessions, workspaces)
}
