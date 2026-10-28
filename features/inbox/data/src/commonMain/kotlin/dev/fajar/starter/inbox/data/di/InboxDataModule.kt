package dev.fajar.starter.inbox.data.di

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.inbox.data")
class InboxDataModule {
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
