package dev.fajar.starter.inbox.data.di

import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.inbox.data")
class InboxDataModule {
    @Factory
    fun observe(
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = ObserveInbox(cache, sessions, workspaces, Clock.System)

    @Single(binds = [SyncInbox::class, SyncTask::class])
    fun synchronize(
        feed: SyncFeedRepository,
        remote: InboxRepository,
        cache: InboxCacheRepository,
        sessions: SessionRepository,
        workspaces: WorkspaceRepository,
    ) = SyncInbox(feed, remote, cache, sessions, workspaces, Clock.System)
}
