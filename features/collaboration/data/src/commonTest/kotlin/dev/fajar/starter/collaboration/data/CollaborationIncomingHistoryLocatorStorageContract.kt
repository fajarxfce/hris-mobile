package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Actual published metadata transactions; locator results do not authenticate event contents. */
abstract class CollaborationIncomingHistoryLocatorStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun emptyStorageIsReadWithoutCreatingHistoryOrContentKeys() = withDatabase { f ->
        assertEquals(CollaborationIncomingHistoryLocatorPage(emptyList(), null), f.read())
        assertEquals(
            CollaborationIncomingHistoryLocatorPage(emptyList(), null),
            f.read(space = true),
        )
        assertFalse(f.local.database.replicas.containsRecords(COLLABORATION_HISTORY_PREFIX))
        assertNull(f.local.keys.read())
    }

    @Test
    fun companyAndSpaceNamespacesRemainSeparateWithIdenticalIds() = withDatabase { f ->
        f.publish(f.partition(10))
        f.publish(f.partition(11, space = true))
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(10), historyId(99))),
            f.read().locators,
        )
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(11), historyId(99))),
            f.read(space = true).locators,
        )
        assertNull(f.local.keys.read())
    }

    @Test
    fun anotherCompanyOrSpaceIsExcludedByTheExactPrefix() = withDatabase { f ->
        f.publish(f.partition(10))
        f.publish(f.partition(11).replace(f.prefix(), f.prefix(owner = historyId(999))))
        f.publish(
            f.partition(12, space = true).replace(f.prefix(true), f.prefix(true, historyId(999)))
        )
        assertEquals(1, f.read().locators.size)
        assertTrue(f.read(space = true).locators.isEmpty())
    }

    @Test
    fun pagingUsesPublishedCoordinatesAndKeepsEachDevice() = withDatabase { f ->
        for (device in 1..35) f.publish(f.partition(10, device = device))
        val first = f.read()
        val second = f.read(assertNotNull(first.next))
        val third = f.read(assertNotNull(second.next))
        assertEquals(listOf(16, 16, 3), listOf(first, second, third).map { it.locators.size })
        assertEquals(
            (1..35).map { CollaborationIncomingHistoryLocator(historyId(10), historyId(it)) },
            first.locators + second.locators + third.locators,
        )
        assertNull(third.next)
    }

    @Test
    fun aPageOfOtherHistoryLanesAdvancesWithoutClaimingExhaustion() = withDatabase { f ->
        for (index in 1..16) f.publish(f.partition(index, lane = null))
        f.publish(f.partition(17))
        val first = f.read()
        assertTrue(first.locators.isEmpty())
        val next = assertNotNull(first.next)
        assertEquals(f.partition(16, lane = null), next)
        val last = f.read(next)
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(17), historyId(99))),
            last.locators,
        )
        assertNull(last.next)
    }

    @Test
    fun unfinishedStagingCannotAppearOrHideAnAlreadyPublishedLocator() = withDatabase { f ->
        val store = f.local.database.replicas
        val visible = f.publish(f.partition(10))
        store.begin(
            f.scope,
            visible.handle.partition,
            historyId(200_000),
            "staged replacement",
            visible.handle.generation,
        )
        store.begin(f.scope, f.partition(11), historyId(200_001), "staging only", null)
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(10), historyId(99))),
            f.read().locators,
        )
    }

    @Test
    fun reopenResumesFromTheExactCursorWithoutReadingContent() = withDatabase { f ->
        for (index in 1..17) f.publish(f.partition(index))
        val first = f.read()
        f.local.reopen()
        val last = f.read(assertNotNull(first.next))
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(17), historyId(99))),
            last.locators,
        )
        assertNull(last.next)
        assertNull(f.local.keys.read())
    }

    @Test
    fun ordinaryReauthenticationKeepsTheSameAccountHistory() = withDatabase { f ->
        f.publish(f.partition(10))
        val renewed = f.access.copy(sessionId = "renewed-login")
        f.local.activate(renewed)
        assertEquals(
            AppResult.Success(CollaborationIncomingHistoryLocatorPage(emptyList(), null)),
            f.repository().read(f.access),
        )
        val page =
            assertIs<AppResult.Success<CollaborationIncomingHistoryLocatorPage>>(
                    f.repository().read(renewed)
                )
                .value
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(10), historyId(99))),
            page.locators,
        )
    }

    @Test
    fun accountReplacementCannotExposeThePreviousAccountsLocators() = withDatabase { f ->
        f.publish(f.partition(10))
        val other = f.access.copy(accountId = historyId(800_000))
        f.local.activate(other)
        assertEquals(
            AppResult.Success(CollaborationIncomingHistoryLocatorPage(emptyList(), null)),
            f.repository().read(f.access),
        )
        val page =
            assertIs<AppResult.Success<CollaborationIncomingHistoryLocatorPage>>(
                    f.repository().read(other)
                )
                .value
        assertTrue(page.locators.isEmpty())
        f.local.activate()
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(10), historyId(99))),
            f.read().locators,
        )
    }

    @Test
    fun anotherServerAudienceHasAnIndependentAccountPartition() = withDatabase { f ->
        f.publish(f.partition(10))
        val other = f.access.copy(audience = "https://other.example.test/")
        f.local.activate(other)
        val page =
            assertIs<AppResult.Success<CollaborationIncomingHistoryLocatorPage>>(
                    f.repository().read(other)
                )
                .value
        assertTrue(page.locators.isEmpty())
        assertEquals(
            AppResult.Success(CollaborationIncomingHistoryLocatorPage(emptyList(), null)),
            f.repository().read(f.access),
        )
        f.local.activate()
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(10), historyId(99))),
            f.read().locators,
        )
    }

    @Test
    fun removingTheCursorRowDoesNotRestartTheRemainingPage() = withDatabase { f ->
        for (index in 1..3) f.publish(f.partition(index))
        val first = f.read(limit = 2)
        val cursor = assertNotNull(first.next)
        val store = f.local.database.replicas
        assertTrue(store.replace(f.scope, cursor, store.checkpoint(f.scope, cursor), null))
        val last = f.read(cursor, limit = 2)
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(3), historyId(99))),
            last.locators,
        )
        assertNull(last.next)
    }

    @Test
    fun insertionBeforeAnActiveCursorBelongsToTheNextPass() = withDatabase { f ->
        for (index in 2..4) f.publish(f.partition(index))
        val first = f.read(limit = 2)
        f.publish(f.partition(1))
        val last = f.read(assertNotNull(first.next), limit = 2)
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(4), historyId(99))),
            last.locators,
        )
        val restarted = f.read()
        assertEquals(
            (1..4).map { CollaborationIncomingHistoryLocator(historyId(it), historyId(99)) },
            restarted.locators,
        )
    }

    @Test
    fun exactlyOneBoundedMetadataReadOccursAndInvalidRequestsPerformNone() = withDatabase { f ->
        f.publish(f.partition(10))
        val original = f.local.database.replicas
        var reads = 0
        val measured =
            object : ReplicaStore by original {
                override fun observePublishedCheckpoints(
                    scope: ReplicaScope,
                    partitionPrefix: String,
                    afterPartition: String?,
                    limit: Int,
                ): Flow<List<ReplicaCheckpoint>> {
                    reads++
                    assertEquals(f.scope, scope)
                    assertEquals(f.prefix(), partitionPrefix)
                    assertNull(afterPartition)
                    assertEquals(17, limit)
                    return original.observePublishedCheckpoints(
                        scope,
                        partitionPrefix,
                        afterPartition,
                        limit,
                    )
                }

                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? =
                    error("Unbounded per-partition lookup is not locator discovery")
            }
        val repository = f.repository(measured)
        assertIs<AppResult.Failed>(repository.read(f.access, limit = 17))
        assertIs<AppResult.Failed>(repository.read(f.access, after = f.partition(10, space = true)))
        assertEquals(0, reads)
        assertIs<AppResult.Success<CollaborationIncomingHistoryLocatorPage>>(
            repository.read(f.access)
        )
        assertEquals(1, reads)
        assertNull(f.local.keys.read())
    }

    @Test
    fun aCompletedReadReleasesItsDatabaseObservationImmediately() = withDatabase { f ->
        f.publish(f.partition(10))
        val original = f.local.database.replicas
        var released = false
        val measured =
            object : ReplicaStore by original {
                override fun observePublishedCheckpoints(
                    scope: ReplicaScope,
                    partitionPrefix: String,
                    afterPartition: String?,
                    limit: Int,
                ): Flow<List<ReplicaCheckpoint>> = flow {
                    try {
                        emitAll(
                            original.observePublishedCheckpoints(
                                scope,
                                partitionPrefix,
                                afterPartition,
                                limit,
                            )
                        )
                    } finally {
                        released = true
                    }
                }
            }
        assertIs<AppResult.Success<CollaborationIncomingHistoryLocatorPage>>(
            f.repository(measured).read(f.access)
        )
        assertTrue(released)
    }

    @Test
    fun cancellingAPendingReadReleasesObservationWithoutMappingCancellationToFailure() =
        withDatabase { f ->
            val original = f.local.database.replicas
            val entered = CompletableDeferred<Unit>()
            var released = false
            val measured =
                object : ReplicaStore by original {
                    override fun observePublishedCheckpoints(
                        scope: ReplicaScope,
                        partitionPrefix: String,
                        afterPartition: String?,
                        limit: Int,
                    ): Flow<List<ReplicaCheckpoint>> = flow {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            released = true
                        }
                    }
                }
            coroutineScope {
                val pending = async { f.repository(measured).read(f.access) }
                entered.await()
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
                assertTrue(released)
                assertFailsWith<CancellationException> { pending.await() }
            }
            assertFalse(f.local.database.replicas.containsRecords(COLLABORATION_HISTORY_PREFIX))
        }

    private fun withDatabase(block: suspend (CollaborationIncomingHistoryLocatorFixture) -> Unit) =
        runTest {
            val f = CollaborationIncomingHistoryLocatorFixture(databaseOwner())
            try {
                f.local.activate()
                block(f)
            } finally {
                f.local.close()
            }
        }
}
