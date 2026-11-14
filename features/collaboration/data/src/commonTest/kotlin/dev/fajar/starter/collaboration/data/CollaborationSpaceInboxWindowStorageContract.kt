package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceInboxEntry
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceInboxWindowStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absentWindowsDoNotAcquireOrReplaceContentKeys() = withDatabase { f ->
        assertEquals(CollaborationSpaceInboxWindow.Items(null, emptyList(), null, null), f.read())
        assertEquals(
            CollaborationSpaceInboxWindow.MissingAnchor,
            f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(1))),
        )
        assertEquals(
            CollaborationSpaceInboxWindow.Changed,
            f.read(CollaborationSpaceInboxWindowAnchor.Older("unavailable-cursor")),
        )
        assertNull(f.base.keys.read())
    }

    @Test
    fun olderAndNewerWindowsPreserveGlobalOrderAndTerminate() = withDatabase { f ->
        f.seed(7)
        val first = assertIs<CollaborationSpaceInboxWindow.Items>(f.read(limit = 3))
        assertEquals((1..3).map(::historyId), first.entries.map { it.conversationId })
        assertNull(first.previous)
        val second =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Older(assertNotNull(first.next)), 3)
            )
        assertEquals((4..6).map(::historyId), second.entries.map { it.conversationId })
        val back =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Newer(assertNotNull(second.previous)), 3)
            )
        assertEquals(first.entries, back.entries)
        assertNull(back.previous)
        val last =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Older(assertNotNull(second.next)), 3)
            )
        assertEquals(listOf(historyId(7)), last.entries.map { it.conversationId })
        assertNull(last.next)
    }

    @Test
    fun aRefreshCentersTheVisibleConversationAndFillsNearBothEnds() = withDatabase { f ->
        f.seed(11)
        for ((anchor, expected) in listOf(6 to (4..8), 1 to (1..5), 11 to (7..11))) {
            val page =
                assertIs<CollaborationSpaceInboxWindow.Items>(
                    f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(anchor)), 5)
                )
            assertEquals(expected.map(::historyId), page.entries.map { it.conversationId })
            assertEquals(expected.first != 1, page.previous != null)
            assertEquals(expected.last != 11, page.next != null)
        }
    }

    @Test
    fun aOneRowWindowCanLoadBothDirectionsFromItsCenter() = withDatabase { f ->
        f.seed(3)
        val center =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(2)), 1)
            )
        assertEquals(listOf(historyId(2)), center.entries.map { it.conversationId })
        val before =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Newer(assertNotNull(center.previous)), 1)
            )
        val after =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Older(assertNotNull(center.next)), 1)
            )
        assertEquals(historyId(1), before.entries.single().conversationId)
        assertEquals(historyId(3), after.entries.single().conversationId)
        assertNull(before.previous)
        assertNull(after.next)
    }

    @Test
    fun missingIdentityIsDistinctFromAChangedCapturedCursor() = withDatabase { f ->
        f.seed(4)
        assertEquals(
            CollaborationSpaceInboxWindow.MissingAnchor,
            f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(100))),
        )
        val first = assertIs<CollaborationSpaceInboxWindow.Items>(f.read(limit = 2))
        f.move(4, 20_000)
        assertEquals(
            CollaborationSpaceInboxWindow.Changed,
            f.read(CollaborationSpaceInboxWindowAnchor.Older(assertNotNull(first.next)), 2),
        )
        val current =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(2)), 3)
            )
        assertTrue(current.entries.any { it.conversationId == historyId(2) })
    }

    @Test
    fun anUnrelatedIndexWriteDuringTheProbeKeepsACoherentWindow() = withDatabase { f ->
        f.seed(9)
        var queryCount = 0
        val raw = f.base.database.replicas
        val store =
            object : ReplicaStore by raw {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow {
                    queryCount++
                    assertEquals(3, queries.size)
                    f.seedRow(10, 30_000)
                    emitAll(raw.observeQueries(scope, partition, queries))
                }
            }
        val page =
            assertIs<CollaborationSpaceInboxWindow.Items>(
                f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(5)), 5, store)
            )
        assertEquals((3..7).map(::historyId), page.entries.map { it.conversationId })
        assertEquals(1, queryCount)
    }

    @Test
    fun movingTheActualAnchorDuringTheProbeReturnsChangedOnce() = withDatabase { f ->
        f.seed(9)
        var queryCount = 0
        val raw = f.base.database.replicas
        val store =
            object : ReplicaStore by raw {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow {
                    queryCount++
                    f.move(5, 30_000)
                    emitAll(raw.observeQueries(scope, partition, queries))
                }
            }
        assertEquals(
            CollaborationSpaceInboxWindow.Changed,
            f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(5)), 5, store),
        )
        assertEquals(1, queryCount)
    }

    @Test
    fun aroundReadsAreBoundedAndReopeningPreservesTheSameRows() = withDatabase { f ->
        f.seed(120)
        val observedQueries = mutableListOf<List<ReplicaQuery>>()
        val raw = f.base.database.replicas
        val store =
            object : ReplicaStore by raw {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("No partition scan")

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    observedQueries += queries.toList()
                    return raw.observeQueries(scope, partition, queries)
                }
            }
        val anchor = CollaborationSpaceInboxWindowAnchor.Conversation(historyId(60))
        val before = assertIs<CollaborationSpaceInboxWindow.Items>(f.read(anchor, 50, store))
        assertEquals(50, before.entries.size)
        assertEquals(3, observedQueries.single().size)
        assertEquals(104, observedQueries.single().sumOf { it.limit })
        f.base.reopen()
        val after = assertIs<CollaborationSpaceInboxWindow.Items>(f.read(anchor, 50))
        assertEquals(before.version, after.version)
        assertEquals(before.entries, after.entries)
        assertNotNull(after.previous)
        assertNotNull(after.next)
    }

    @Test
    fun cancellationOfAPendingRawWindowPropagatesAndClosesItsOnlyQuery() = withDatabase { f ->
        f.seed(3)
        val entered = CompletableDeferred<Unit>()
        var closed = 0
        val raw = f.base.database.replicas
        val store =
            object : ReplicaStore by raw {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        closed++
                    }
                }
            }
        coroutineScope {
            var returned = false
            val work = async {
                f.reader(store).read(f.access, CollaborationSpaceInboxWindowAnchor.Latest, 50)
                returned = true
            }
            entered.await()
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertFalse(returned)
            assertEquals(1, closed)
        }
    }

    @Test
    fun replacementGenerationDuringAnAnchorProbeReturnsChangedOnce() = withDatabase { f ->
        f.seed(5)
        val raw = f.base.database.replicas
        var queriesRead = 0
        val store =
            object : ReplicaStore by raw {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow {
                    queriesRead++
                    assertTrue(raw.clear(scope, partition))
                    f.seed(5)
                    emitAll(raw.observeQueries(scope, partition, queries))
                }
            }
        assertEquals(
            CollaborationSpaceInboxWindow.Changed,
            f.read(CollaborationSpaceInboxWindowAnchor.Conversation(historyId(3)), 3, store),
        )
        assertEquals(1, queriesRead)
    }

    @Test
    fun foreignAccountAndTamperedCursorsCannotReadAnOwnedWindow() = withDatabase { f ->
        f.seed(5)
        val page = assertIs<CollaborationSpaceInboxWindow.Items>(f.read(limit = 2))
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxWindow.Items(null, emptyList(), null, null)),
            f.reader()
                .read(
                    f.access.copy(accountId = historyId(777)),
                    CollaborationSpaceInboxWindowAnchor.Latest,
                    2,
                ),
        )
        val otherSpace = f.access.copy(spaceId = historyId(778))
        assertEquals(
            AppResult.Success(true),
            f.writer()
                .publish(
                    otherSpace,
                    CollaborationSpaceInboxPublication(
                        null,
                        0,
                        collaborationSpaceInboxEntry(historyId(1), 100, null, null),
                    ),
                ),
        )
        assertIs<AppResult.Failed>(
            f.reader()
                .read(
                    otherSpace,
                    CollaborationSpaceInboxWindowAnchor.Older(assertNotNull(page.next)),
                    2,
                )
        )
        assertIs<AppResult.Failed>(
            f.reader()
                .read(f.access, CollaborationSpaceInboxWindowAnchor.Older("invalid-ciphertext"), 2)
        )
    }

    @Test
    fun unauthenticatedOrderingAndDuplicateDocumentsAreNotSuccessfulWindows() = withDatabase { f ->
        f.seed(5)
        val raw = f.base.database.replicas
        for (duplicate in listOf(false, true)) {
            val store =
                object : ReplicaStore by raw {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> =
                        raw.observeQueries(scope, partition, queries).map { page ->
                            val documents = page.pages.single()
                            val altered =
                                if (duplicate) listOf(documents.first(), documents.first())
                                else
                                    documents.mapIndexed { index, document ->
                                        if (index == 0) document.copy(tags = emptySet())
                                        else document
                                    }
                            page.copy(pages = listOf(altered))
                        }
                }
            assertIs<AppResult.Failed>(
                f.reader(store).read(f.access, CollaborationSpaceInboxWindowAnchor.Latest, 3)
            )
        }
    }

    @Test
    fun rawListsAndTagsAreDetachedBeforeSuspendedDecryption() = withDatabase { f ->
        f.seed(7)
        val raw = f.base.database.replicas
        val pages = mutableListOf<MutableList<ReplicaDocument>>()
        var captured = false
        var mutations = 0
        val store =
            object : ReplicaStore by raw {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> =
                    raw.observeQueries(scope, partition, queries).map { page ->
                        pages.clear()
                        pages.addAll(
                            page.pages.map { rows ->
                                rows.map { it.copy(tags = it.tags.toMutableSet()) }.toMutableList()
                            }
                        )
                        captured = true
                        page.copy(pages = pages)
                    }
            }
        val cipher =
            object : ContentCipher by f.base.cipher {
                override suspend fun decrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    ciphertext: ByteArray,
                ): ByteArray {
                    if (captured) {
                        captured = false
                        mutations++
                        for (page in pages) {
                            for (document in page) (document.tags as MutableSet<String>).clear()
                            page.clear()
                        }
                        pages.clear()
                    }
                    return f.base.cipher.decrypt(key, associatedData, ciphertext)
                }
            }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxWindow>>(
                f.reader(store, cipher)
                    .read(
                        f.access,
                        CollaborationSpaceInboxWindowAnchor.Conversation(historyId(4)),
                        5,
                    )
            )
        val page = assertIs<CollaborationSpaceInboxWindow.Items>(result.value)
        assertEquals((2..6).map(::historyId), page.entries.map { it.conversationId })
        assertEquals(1, mutations)
        assertTrue(pages.isEmpty())
    }

    private fun withDatabase(block: suspend (InboxWindowStorageFixture) -> Unit) = runTest {
        val fixture = InboxWindowStorageFixture(databaseOwner())
        try {
            fixture.base.activate()
            block(fixture)
        } finally {
            fixture.base.close()
        }
    }
}
