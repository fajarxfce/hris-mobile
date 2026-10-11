package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.admitInbox
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class InboxFixture(now: () -> Instant = { Instant.parse("2026-10-11T00:00:00Z") }) {
    val clock =
        object : Clock {
            override fun now() = now()
        }
    val session =
        Session(
            "session",
            User("account", "Alex", "alex@example.test"),
            SessionTokens("access", "refresh", Long.MAX_VALUE),
            SessionBinding(
                "family",
                "https://example.test/",
                Instant.parse("2026-11-01T00:00:00Z").toEpochMilliseconds(),
            ),
        )
    val workspace =
        Workspace(
            "workspace",
            session.id,
            session.binding.audience,
            CompanyMembership("company", "Company", "CO", "Asia/Jakarta"),
            setOf("announcements.read"),
        )
    val sessionState = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session))
    val workspaceState = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(workspace))
    val access =
        (admitInbox(workspace.id, workspace, session, clock.now()) as AppResult.Success).value
    val item =
        InboxItem(
            "item",
            "announcement",
            "publication",
            1,
            "Office notice",
            "A short notice.",
            true,
            clock.now(),
            null,
            null,
        )
    val reference = SyncReference(SyncCollection.INBOX, item.id, item.version)
    var bootstrapCall: suspend (String?) -> AppResult<SyncBootstrapPage> = {
        AppResult.Success(
            SyncBootstrapPage(
                setOf(SyncCollection.INBOX),
                listOf(reference),
                null,
                "delta-1",
                clock.now(),
            )
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = {
        AppResult.Success(SyncChangePage(emptyList(), "delta-2", false, false, 5, clock.now()))
    }
    var itemCall: suspend (String) -> AppResult<InboxItem> = { AppResult.Success(item) }
    var bootstrapCalls = 0
    var changesCalls = 0
    var itemCalls = 0
    var clearCalls = 0
    var checkpoint: InboxCheckpoint? = null
    val commits = mutableListOf<InboxCheckpoint>()
    val entries = linkedMapOf<String, InboxReplicaEntry>()
    val snapshot =
        MutableStateFlow<AppResult<InboxCachePage>>(
            AppResult.Success(InboxCachePage(null, emptyList(), null))
        )

    val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = sessionState

            override suspend fun current() = sessionState.value

            override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                AppResult.Success(
                    sessionState.compareAndSet(
                        AppResult.Success(expected),
                        AppResult.Success(updated),
                    )
                )

            override suspend fun clear(sessionId: String): AppResult<Boolean> {
                val current = (sessionState.value as? AppResult.Success)?.value
                return AppResult.Success(
                    current?.id == sessionId &&
                        sessionState.compareAndSet(
                            AppResult.Success(current),
                            AppResult.Success(null),
                        )
                )
            }
        }
    val workspaces =
        object : WorkspaceRepository {
            override fun observe() = workspaceState

            override suspend fun current() = workspaceState.value

            override suspend fun compareAndSet(expected: Workspace?, updated: Workspace?) =
                AppResult.Success(
                    workspaceState.compareAndSet(
                        AppResult.Success(expected),
                        AppResult.Success(updated),
                    )
                )

            override suspend fun permissions(sessionId: String, companyId: String) =
                AppResult.Success(workspace.permissions)
        }
    val feed =
        object : SyncFeedRepository {
            override suspend fun bootstrap(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String?,
                limit: Int,
            ): AppResult<SyncBootstrapPage> {
                check(collections == setOf(SyncCollection.INBOX) && limit == 50)
                bootstrapCalls++
                return bootstrapCall(cursor)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                changesCalls++
                return changesCall(cursor)
            }
        }
    val remote =
        object : InboxRepository {
            override suspend fun item(
                sessionId: String,
                companyId: String,
                id: String,
            ): AppResult<InboxItem> {
                itemCalls++
                return itemCall(id)
            }
        }
    val cache =
        object : InboxCacheRepository {
            override fun observe(access: InboxAccess, query: InboxCacheQuery) =
                snapshot.map { result ->
                    if (result is AppResult.Success && query is InboxCacheQuery.Item)
                        AppResult.Success(
                            result.value.copy(
                                items = result.value.items.filter { it.id == query.id }
                            )
                        )
                    else result
                }

            override suspend fun checkpoint(access: InboxAccess) = AppResult.Success(checkpoint)

            override suspend fun begin(
                checkpoint: InboxCheckpoint,
                expectedGeneration: String?,
            ): AppResult<Boolean> {
                if (this@InboxFixture.checkpoint?.generation != expectedGeneration)
                    return AppResult.Success(false)
                this@InboxFixture.checkpoint = checkpoint
                entries.clear()
                return AppResult.Success(true)
            }

            override suspend fun commit(
                previous: InboxCheckpoint,
                next: InboxCheckpoint,
                entries: List<InboxReplicaEntry>,
            ): AppResult<Boolean> {
                if (checkpoint != previous) return AppResult.Success(false)
                for (entry in entries) {
                    val old = this@InboxFixture.entries[entry.id]
                    if (
                        old == null ||
                            entry.version > old.version ||
                            (entry.version == old.version && entry.item == null)
                    )
                        this@InboxFixture.entries[entry.id] = entry
                }
                checkpoint = next
                commits.add(next)
                if (next.published)
                    snapshot.value =
                        AppResult.Success(
                            InboxCachePage(
                                next,
                                this@InboxFixture.entries.values.mapNotNull { it.item },
                                null,
                            )
                        )
                return AppResult.Success(true)
            }

            override suspend fun clear(access: InboxAccess): AppResult<Boolean> {
                clearCalls++
                checkpoint = null
                entries.clear()
                snapshot.value = AppResult.Success(InboxCachePage(null, emptyList(), null))
                return AppResult.Success(true)
            }
        }
    val sync = SyncInbox(feed, remote, cache, sessions, workspaces, clock)
    val observe = ObserveInbox(cache, sessions, workspaces, clock)
}
