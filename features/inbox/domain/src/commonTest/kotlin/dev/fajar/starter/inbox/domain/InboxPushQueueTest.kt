@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.inbox.domain.push.policies.parseInboxPushSignal
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.inbox.domain.push.usecases.QueueInboxPushSignal
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*

class InboxPushQueueTest {
    private val account = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val item = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private fun fields(event: String = item, recipient: String = account) =
        mapOf(
            "schemaVersion" to "1",
            "eventCode" to "communications.inbox_available",
            "eventId" to event,
            "accountId" to recipient,
            "companyId" to company,
            "inboxId" to item,
        )

    @Test
    fun failedSchedulingRetainsTheHintAndALaterDuplicateRetriesTheQueue() = runTest {
        val f = Fixture()
        f.failSchedule = true
        assertIs<AppResult.Failed>(f.useCase(fields()))
        assertEquals(1, f.state.pending.size)
        assertEquals(1, f.writes)
        f.failSchedule = false
        assertEquals(AppResult.Success(true), f.useCase(fields()))
        assertEquals(1, f.writes)
        assertEquals(2, f.scheduleCalls)
    }

    @Test
    fun attemptEvidenceSurvivesAnotherAccountUsingTheInstallation() = runTest {
        val f = Fixture()
        f.useCase(fields())
        val owner = requireNotNull(f.state.owner)
        f.state =
            f.state.copy(
                pending = emptyList(),
                attempts = listOf(InboxPushAttempt(item, company, owner, f.now)),
            )
        f.identity.sessionState.value =
            AppResult.Success(
                f.session.copy(id = "second", user = f.session.user.copy(id = company))
            )
        assertEquals(AppResult.Success(true), f.useCase(fields(recipient = company)))
        assertEquals(owner, f.state.attempts.single().owner)
        f.identity.sessionState.value = AppResult.Success(f.session.copy(id = "third"))
        assertEquals(AppResult.Success(false), f.useCase(fields()))
        assertEquals(owner, f.state.attempts.single().owner)
    }

    @Test
    fun malformedForeignAndDisplayPayloadsNeverReachStorage() = runTest {
        val f = Fixture()
        val invalid =
            listOf(
                fields() + ("title" to "Untrusted"),
                fields() + ("url" to "https://evil.invalid"),
                fields() - "eventId",
                fields() + ("schemaVersion" to "2"),
                fields() + ("eventCode" to "other"),
                fields() + ("eventId" to item.uppercase()),
                fields() + ("companyId" to "a".repeat(3000)),
                fields() + ("inboxId" to "../../account"),
            )
        invalid.forEach {
            assertNull(parseInboxPushSignal(it))
            assertEquals(AppResult.Success(false), f.useCase(it))
        }
        assertEquals(AppResult.Success(false), f.useCase(fields(recipient = company)))
        assertEquals(0, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun duplicatesRetainTheOriginalHintAndPreviouslyAttemptedEventsCannotAlertAgain() = runTest {
        val f = Fixture()
        assertEquals(AppResult.Success(true), f.useCase(fields()))
        val first = f.state
        f.now += 1.days / 2
        assertEquals(AppResult.Success(true), f.useCase(fields()))
        assertEquals(first, f.state)
        assertEquals(1, f.writes)
        assertEquals(AppResult.Success(false), f.useCase(fields() + ("companyId" to account)))
        f.state =
            f.state.copy(
                pending = emptyList(),
                attempts =
                    listOf(InboxPushAttempt(item, company, requireNotNull(f.state.owner), f.now)),
            )
        assertEquals(AppResult.Success(false), f.useCase(fields()))
        assertEquals(1, f.writes)
        assertFalse(f.state.toString().contains(item))
    }

    @Test
    fun accountReplacementIgnoresOldPushAndKeepsOnlyTheNewOwnersQueue() = runTest {
        val f = Fixture()
        f.useCase(fields())
        val old = f.state
        f.identity.sessionState.value =
            AppResult.Success(
                f.session.copy(id = "replacement", user = f.session.user.copy(id = company))
            )
        assertEquals(AppResult.Success(false), f.useCase(fields()))
        assertEquals(old, f.state)
        assertEquals(AppResult.Success(true), f.useCase(fields(recipient = company)))
        assertEquals(company, f.state.owner!!.accountId)
        assertEquals(1, f.state.pending.size)
        f.identity.sessionState.value = AppResult.Success(null)
        assertEquals(AppResult.Success(false), f.useCase(fields(recipient = company)))
        assertEquals(2, f.writes)
    }

    @Test
    fun accountReplacementDuringLocalReadAndCancelledCallbacksCannotEnqueueWork() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeRead = { withContext(NonCancellable) { release.await() } }
        val work = async { f.useCase(fields()) }
        runCurrent()
        f.identity.sessionState.value = AppResult.Success(null)
        release.complete(Unit)
        assertIs<AppResult.Failed>(work.await())
        assertEquals(0, f.writes)
        f.identity.sessionState.value = AppResult.Success(f.session)
        val cancelled = CompletableDeferred<Unit>()
        f.beforeRead = { withContext(NonCancellable) { cancelled.await() } }
        val job = launch { f.useCase(fields()) }
        runCurrent()
        job.cancel()
        cancelled.complete(Unit)
        job.join()
        assertEquals(0, f.writes)
    }

    @Test
    fun queuesAreBoundedWithoutEvictingRecentAttemptEvidenceAndTransientHintsExpire() = runTest {
        val f = Fixture()
        f.state =
            InboxPushQueue(
                InboxPushOwner(f.server.audience, account),
                pending = (0 until 64).map { QueuedInboxPush(id(it), company, item, f.now) },
            )
        assertEquals(
            "client.inbox_push_capacity",
            assertIs<AppResult.Failed>(f.useCase(fields())).failure.code,
        )
        assertEquals(64, f.state.pending.size)
        f.now += 2.days
        assertEquals(AppResult.Success(true), f.useCase(fields()))
        assertEquals(listOf(item), f.state.pending.map { it.eventId })
        f.state =
            f.state.copy(
                pending = emptyList(),
                attempts =
                    (0 until 512).map {
                        InboxPushAttempt(id(it), company, requireNotNull(f.state.owner), f.now)
                    },
            )
        assertEquals(
            "client.inbox_push_capacity",
            assertIs<AppResult.Failed>(f.useCase(fields())).failure.code,
        )
        assertEquals(512, f.state.attempts.size)
        f.now += 8.days
        assertEquals(AppResult.Success(true), f.useCase(fields()))
        assertTrue(f.state.attempts.isEmpty())
    }

    @Test
    fun pendingLocalIoHasADeadlineAndRetryDoesNotDuplicateTheHint() = runTest {
        val f = Fixture()
        f.beforeRead = { awaitCancellation() }
        val result = async { f.useCase(fields()) }
        advanceUntilIdle()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result.await()).failure.kind)
        assertEquals(0, f.writes)
        f.beforeRead = {}
        val results = (0..3).map { async { f.useCase(fields()) } }.awaitAll()
        assertTrue(results.all { it == AppResult.Success(true) })
        assertEquals(1, f.writes)
    }

    private fun id(index: Int) = "33333333-3333-4333-8333-${index.toString().padStart(12, '0')}"

    private inner class Fixture {
        var now = Instant.parse("2026-10-28T00:00:00Z")
        val identity = InboxFixture(now = { now })
        val session = identity.session.copy(user = identity.session.user.copy(id = account))
        val server = IdentityServer(session.binding.audience, "Test")
        var state = InboxPushQueue()
        var reads = 0
        var writes = 0
        var scheduleCalls = 0
        var failSchedule = false
        val schedules =
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    assertEquals(QueueInboxPushSignal.KEY, key)
                    assertTrue(state.pending.isNotEmpty())
                    scheduleCalls++
                    return if (failSchedule)
                        AppResult.Failed(Failure(FailureKind.Service, "Unavailable"))
                    else AppResult.Success(Unit)
                }
            }
        var beforeRead: suspend () -> Unit = {}
        val queue =
            object : InboxPushQueueRepository {
                override suspend fun read(): AppResult<InboxPushQueue> {
                    reads++
                    beforeRead()
                    return AppResult.Success(state)
                }

                override suspend fun compareAndSet(
                    expected: InboxPushQueue,
                    updated: InboxPushQueue,
                ): AppResult<Boolean> {
                    if (state != expected) return AppResult.Success(false)
                    writes++
                    state = updated
                    return AppResult.Success(true)
                }
            }
        val useCase =
            QueueInboxPushSignal(
                identity.sessions,
                queue,
                schedules,
                server,
                identity.clock,
                Mutex(),
            )

        init {
            identity.sessionState.value = AppResult.Success(session)
        }
    }
}
