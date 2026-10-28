package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.NativePushStateRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.admitInbox
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.inbox.domain.push.usecases.*
import dev.fajar.starter.notifications.domain.entities.*
import dev.fajar.starter.notifications.domain.repositories.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex

internal class InboxPushDeliveryFixture {
    var now = Instant.parse("2026-10-28T00:00:00Z")
    val inbox = InboxFixture(now = { now })
    val accountId = "11111111-1111-4111-8111-111111111111"
    val companyId = "22222222-2222-4222-8222-222222222222"
    val itemId = "33333333-3333-4333-8333-333333333333"
    val session =
        inbox.session.copy(
            user = inbox.session.user.copy(id = accountId),
            binding = inbox.session.binding.copy(serverId = "44444444-4444-4444-8444-444444444444"),
        )
    val workspace =
        inbox.workspace.copy(
            company = inbox.workspace.company.copy(id = companyId),
            admission =
                inbox.workspace.admission.copy(
                    accountId = accountId,
                    familyId = session.binding.serverId,
                ),
        )
    val access = (admitInbox(workspace.id, workspace, session) as AppResult.Success).value
    val item =
        inbox.item.copy(
            id = itemId,
            title = "Private canonical title",
            body = "Private canonical body",
        )
    val checkpoint =
        InboxCheckpoint(
            access,
            "generation",
            1,
            InboxSyncPhase.CHANGES,
            "cursor",
            true,
            now,
            now + 60.seconds,
            now + 5.seconds,
        )
    val owner = InboxPushOwner(access.audience, access.accountId)
    var state = InboxPushQueue(owner, listOf(QueuedInboxPush(itemId, companyId, itemId, now)))
    var pushState = NativePushState(desired = true, owner = session.pushOwner())
    var permission = NotificationAccess.Granted
    var failWrite = false
    val shown = mutableListOf<NotificationMessage>()
    var beforeShow: suspend () -> Unit = {}
    var beforePermission: suspend () -> Unit = {}
    var beforePushRead: suspend () -> Unit = {}
    val queue =
        object : InboxPushQueueRepository {
            override suspend fun read() = AppResult.Success(state)

            override suspend fun compareAndSet(
                expected: InboxPushQueue,
                updated: InboxPushQueue,
            ): AppResult<Boolean> {
                if (failWrite) return AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
                if (state != expected) return AppResult.Success(false)
                state = updated
                return AppResult.Success(true)
            }
        }
    val push =
        object : NativePushStateRepository {
            override val persistent = true

            override suspend fun read(): AppResult<NativePushState> {
                val snapshot = pushState
                beforePushRead()
                return AppResult.Success(snapshot)
            }

            override fun observe() = flowOf(AppResult.Success(pushState))

            override suspend fun compareAndSet(
                expected: NativePushState,
                updated: NativePushState,
            ): AppResult<Boolean> = error("No registration mutation")
        }
    val permissions =
        object : NotificationAccessRepository {
            override suspend fun check(): AppResult<NotificationAccess> {
                beforePermission()
                return AppResult.Success(permission)
            }

            override suspend fun request(): AppResult<NotificationAccess> =
                error("No notification prompt")
        }
    val delivery =
        object : NotificationDeliveryRepository {
            override suspend fun show(message: NotificationMessage): AppResult<Unit> {
                check(state.pending.none { it.eventId == message.id })
                check(state.attempts.any { it.eventId == message.id })
                shown += message
                beforeShow()
                return AppResult.Success(Unit)
            }
        }
    private val mutations = Mutex()
    val prepare =
        PrepareInboxPush(
            inbox.sessions,
            inbox.workspaces,
            queue,
            IdentityServer(access.audience, "Test"),
            inbox.clock,
            mutations,
        )
    val present =
        PresentInboxPush(
            inbox.sessions,
            inbox.workspaces,
            queue,
            inbox.remote,
            inbox.cache,
            push,
            permissions,
            delivery,
            inbox.clock,
            mutations,
        )
    val copy = InboxPushCopy("HRIS", "An inbox update is available.")

    init {
        inbox.sessionState.value = AppResult.Success(session)
        inbox.workspaceState.value = AppResult.Success(workspace)
        inbox.itemCall = { AppResult.Success(item) }
        inbox.snapshot.value = AppResult.Success(InboxCachePage(checkpoint, listOf(item), null))
    }
}
