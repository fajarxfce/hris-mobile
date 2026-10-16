package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.admitInbox
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

class InboxFixture(
    sessionLifetime: Duration = 21.days,
    now: () -> Instant = { Instant.parse("2026-10-11T00:00:00Z") },
) {
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
                (clock.now() + sessionLifetime).toEpochMilliseconds(),
            ),
        )
    val build = AppBuild(AppPlatform.Android, 1, "1.0.0", null)
    val policy =
        CompanyPolicy(
            null,
            CompanyModule.entries.toSet(),
            CompanyMinimumBuilds(0, 0, 0),
            null,
            false,
            clock.now(),
            clock.now() + 60.seconds,
        )
    var policyCalls = 0
    var policyCall: suspend () -> AppResult<CompanyPolicy> = {
        AppResult.Success(
            policy.copy(serverTime = clock.now(), validUntil = clock.now() + 60.seconds)
        )
    }
    val workspace =
        Workspace(
            "workspace",
            session.id,
            session.binding.audience,
            CompanyMembership("company", "Company", "CO", "Asia/Jakarta"),
            setOf("announcements.read"),
            WorkspaceAdmission(
                session.user.id,
                session.binding.serverId,
                clock.now(),
                minOf(
                    clock.now() + 1.hours,
                    Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis),
                ),
            ),
            policy = CompanyPolicySnapshot(policy, clock.now()),
        )
    val sessionState = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session))
    val workspaceState = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(workspace))
    val access = (admitInbox(workspace.id, workspace, session) as AppResult.Success).value
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
    var unreadCall: suspend () -> AppResult<Long> = { AppResult.Success(36L) }
    var unreadCalls = 0
    var bootstrapCalls = 0
    var changesCalls = 0
    var itemCalls = 0
    var clearCalls = 0
    var clearCall: suspend () -> AppResult<Boolean>? = { null }
    val readAdmission =
        MutableStateFlow<AppResult<InboxReadAdmission>>(
            AppResult.Success(InboxReadAdmission(null, null))
        )
    var restrictionWrites = 0
    var restrictionCall: suspend () -> AppResult<Boolean>? = { null }
    var admissionCall: suspend () -> AppResult<InboxReadAdmission>? = { null }
    var admitCall: suspend () -> AppResult<Boolean>? = { null }
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
            override suspend fun clientPolicy(
                sessionId: String,
                companyId: String,
            ): AppResult<CompanyPolicy> {
                policyCalls++
                return policyCall()
            }

            override fun observe(sessionId: String) = workspaceState

            override suspend fun current(sessionId: String) = workspaceState.value

            override suspend fun compareAndSet(
                sessionId: String,
                expected: Workspace?,
                updated: Workspace?,
            ) =
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
            override suspend fun unreadCount(
                sessionId: String,
                companyId: String,
            ): AppResult<Long> {
                check(sessionId == session.id && companyId == workspace.company.id)
                unreadCalls++
                return unreadCall()
            }

            override suspend fun act(
                sessionId: String,
                command: InboxCommand,
            ): AppResult<InboxActionReceipt> =
                error("Read synchronization must not issue commands.")

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
            override fun observeAdmission(access: InboxAccess) = readAdmission

            override suspend fun admission(access: InboxAccess) =
                admissionCall() ?: readAdmission.value

            override suspend fun restrict(
                access: InboxAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                readAdmission.value =
                    AppResult.Success(InboxReadAdmission("denial-${++restrictionWrites}", failure))
                return restrictionCall() ?: AppResult.Success(true)
            }

            override suspend fun admit(
                access: InboxAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> {
                val previous = readAdmission.value
                if (previous is AppResult.Failed) return previous
                if ((previous as AppResult.Success).value.revision != expectedRevision)
                    return AppResult.Success(false)
                admitCall()?.let {
                    if (it is AppResult.Failed || !(it as AppResult.Success).value) return it
                }
                return AppResult.Success(
                    readAdmission.compareAndSet(
                        previous,
                        AppResult.Success(InboxReadAdmission(null, null)),
                    )
                )
            }

            override fun observe(access: InboxAccess, query: InboxCacheQuery) =
                snapshot.map { result ->
                    if (result is AppResult.Success && query is InboxCacheQuery.Item)
                        AppResult.Success(
                            result.value.copy(
                                items = result.value.items.filter { it.id == query.id }
                            )
                        )
                    else if (result is AppResult.Success && query == InboxCacheQuery.Checkpoint)
                        AppResult.Success(result.value.copy(items = emptyList(), nextCursor = null))
                    else result
                }

            override fun observePublishedCheckpoint(access: InboxAccess) =
                snapshot.map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> AppResult.Success(result.value.checkpoint)
                    }
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
                clearCall()?.let {
                    return it
                }
                checkpoint = null
                entries.clear()
                snapshot.value = AppResult.Success(InboxCachePage(null, emptyList(), null))
                return AppResult.Success(true)
            }
        }
    val sync = SyncInbox(feed, remote, cache, sessions, workspaces, clock, build)
    val observe = ObserveInbox(cache, sessions, workspaces)
    val unread = LoadUnreadInbox(remote, cache, sessions, workspaces)
}
