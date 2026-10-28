package dev.fajar.starter.inbox.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.data.push.repositories.StoredInboxPushQueueRepository
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.securestorage.MemoryCredentialStore
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class InboxPushStorageTest {
    private val account = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val item = "33333333-3333-4333-8333-333333333333"
    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private val queued =
        InboxPushQueue(
            InboxPushOwner("https://hris.invalid/api/v1/", account),
            listOf(QueuedInboxPush(item, company, item, now)),
        )

    @Test
    fun restartRetainsOwnedHintsAndOnlyOneCompetingTransitionCommits() = runTest {
        val credentials = MemoryCredentialStore()
        val repository = StoredInboxPushQueueRepository(credentials)
        assertEquals(AppResult.Success(true), repository.compareAndSet(InboxPushQueue(), queued))
        val restored = StoredInboxPushQueueRepository(credentials)
        assertEquals(AppResult.Success(queued), restored.read())
        val attempted =
            queued.copy(
                pending = emptyList(),
                attempts =
                    listOf(InboxPushAttempt(item, company, requireNotNull(queued.owner), now)),
            )
        val writes =
            listOf(attempted, InboxPushQueue())
                .map { async { restored.compareAndSet(queued, it) } }
                .awaitAll()
        assertEquals(1, writes.count { it == AppResult.Success(true) })
        assertEquals(1, writes.count { it == AppResult.Success(false) })
    }

    @Test
    fun malformedOrOversizedStorageCannotBecomeAnEmptyQueueOrBeOverwritten() = runTest {
        val credentials = MemoryCredentialStore()
        val repository = StoredInboxPushQueueRepository(credentials)
        for (body in listOf("broken", " ".repeat(131_073), "{\"format\":2}")) {
            credentials.write(body)
            assertIs<AppResult.Failed>(repository.read())
            assertIs<AppResult.Failed>(repository.compareAndSet(InboxPushQueue(), queued))
            assertEquals(body, credentials.read())
        }
    }

    @Test
    fun invalidOwnerDuplicateEventsAndCapacityViolationsCannotReplaceTheQueue() = runTest {
        val credentials = MemoryCredentialStore()
        val repository = StoredInboxPushQueueRepository(credentials)
        val signal = queued.pending.single()
        for (state in
            listOf(
                queued.copy(owner = null),
                queued.copy(owner = queued.owner!!.copy(accountId = "wrong")),
                queued.copy(pending = listOf(signal, signal)),
                queued.copy(pending = List(65) { signal }),
                queued.copy(
                    attempts =
                        List(513) {
                            InboxPushAttempt(item, company, requireNotNull(queued.owner), now)
                        }
                ),
                queued.copy(
                    attempts =
                        listOf(InboxPushAttempt(item, company, requireNotNull(queued.owner), now))
                ),
            )) {
            assertIs<AppResult.Failed>(repository.compareAndSet(InboxPushQueue(), state))
            assertNull(credentials.read())
        }
    }
}
