package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceConversationCatalogRead as CatalogRead
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceConversationCatalogStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun emptyInventoryAndVersionNeverCreateKeysOrWriteRecords() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.catalog().observeVersion(f.local()).first())
        assertEquals(CatalogRead.Page(null, emptyList(), null, false), f.page())
        assertNull(f.keys.read())
        assertNull(f.rawCatalog().checkpoint)
    }

    @Test
    fun finiteCursorVisitsEveryCanonicalPairOnceIncludingEmptyPages() = withDatabase { f ->
        val values = (1..8).map { f.conversation(it).also { value -> f.retain(value) } }
        val before = f.rawCatalog()
        var next: String? = null
        val found = mutableListOf<CollaborationSpaceConversationMetadata>()
        val cursors = mutableSetOf<String>()
        var emptyPages = 0
        repeat(16) { pageIndex ->
            val page = f.page(next, limit = 1)
            assertEquals(7, page.version?.revision)
            assertFalse(page.changed)
            assertTrue(page.items.size <= 1)
            if (page.items.isEmpty()) emptyPages++
            found += page.items
            next = page.next
            if (pageIndex < 15) assertTrue(cursors.add(assertNotNull(next))) else assertNull(next)
        }
        assertEquals(8, emptyPages)
        assertEquals(values.map { it.localMetadata() }.toSet(), found.toSet())
        assertEquals(8, found.size)
        assertEquals(before, f.rawCatalog())
    }

    @Test
    fun readsArePublishedBoundedAndNeverScanMessageHistory() = withDatabase { f ->
        (1..12).forEach { f.retain(f.conversation(it)) }
        var scans = 0
        var pairs = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> {
                    scans++
                    assertEquals(spaceConversationMetadataPartition(f.local()), partition)
                    assertEquals(8, limit)
                    return f.database.replicas.observe(scope, partition, after, limit)
                }

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    pairs++
                    assertEquals(spaceConversationMetadataPartition(f.local()), partition)
                    assertEquals(2, queries.size)
                    assertTrue(queries.all { it.limit == 2 && it.tag?.startsWith("m-") == true })
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }

                override suspend fun workingDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    ids: List<String>,
                ): ReplicaPage = error("No staging reads")
            }
        val page =
            assertIs<CatalogRead.Page>(
                assertIs<AppResult.Success<CatalogRead>>(
                        f.catalog(store).read(f.local(), rawLimit = 7)
                    )
                    .value
            )
        assertEquals(1, scans)
        assertEquals(page.items.size, pairs)
        assertTrue(pairs <= 7)
        assertNotNull(page.next)
    }

    @Test
    fun insertsBehindTheCursorWaitForTheNextPassWithoutLosingOldPairs() = withDatabase { f ->
        // Reserve the lowest opaque conversation index for an insertion after the first page.
        f.retain(f.conversation(30))
        val candidates =
            CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { content, index ->
                val codec =
                    CollaborationSpaceConversationMetadataCodec(f.local(), f.cipher, content, index)
                (1..20)
                    .map { f.conversation(it) }
                    .map { codec.ids(it.localMetadata()).first() to it }
                    .sortedBy { it.first }
            }
        val inserted = candidates.first().second
        val originals = candidates.takeLast(3).map { it.second }
        originals.forEach { f.retain(it) }
        val first = f.page(limit = 7)
        val version = assertNotNull(first.version)
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { content, index ->
            val codec =
                CollaborationSpaceConversationMetadataCodec(f.local(), f.cipher, content, index)
            val position = codec.catalogPosition(assertNotNull(first.next))
            assertTrue(candidates.first().first < position.after)
        }
        f.retain(inserted)
        val found = first.items.toMutableList()
        var next = assertNotNull(first.next)
        var finished = false
        repeat(10) {
            if (!finished) {
                val page = f.page(next, limit = 1)
                assertEquals(version, page.version)
                assertTrue(page.changed)
                found += page.items
                if (page.next == null) finished = true else next = assertNotNull(page.next)
            }
        }
        assertTrue(finished)
        assertEquals(
            (originals + f.conversation(30)).map { it.id }.toSet(),
            found.map { it.id }.toSet(),
        )
        assertEquals(4, found.size)
        assertEquals(5, f.page().items.size)
        assertTrue(f.page().items.any { it.id == inserted.id })
    }

    @Test
    fun cursorSurvivesRenewedLoginButRejectsAnotherGroupAccountOrServer() = withDatabase { f ->
        f.retain(f.conversation(1))
        val cursor = assertNotNull(f.page(limit = 1).next)
        val renewed = f.access.copy(sessionId = "renewed", companyId = historyId(9890))
        f.reopen(renewed)
        assertIs<AppResult.Success<CatalogRead>>(f.catalog().read(f.local(renewed), cursor))
        for ((index, owner) in
            listOf(
                    f.access.copy(sessionId = "foreign", accountId = historyId(9891)),
                    f.access.copy(sessionId = "server", audience = "https://foreign.example.test/"),
                    f.access,
                )
                .withIndex()) {
            f.activate(owner)
            val access =
                f.local(owner).let { if (index == 2) it.copy(spaceId = historyId(9892)) else it }
            val value = f.conversation(1, access)
            assertEquals(AppResult.Success(true), f.metadata().retain(access, value))
            assertIs<AppResult.Failed>(f.catalog().read(access, cursor))
        }
    }

    @Test
    fun generationReplacementRequiresRestartInsteadOfAnEmptySuccessfulContinuation() =
        withDatabase { f ->
            val value = f.conversation(1)
            f.retain(value)
            val cursor = assertNotNull(f.page(limit = 1).next)
            val old = f.rawCatalog().checkpoint
            assertTrue(
                f.database.replicas.replace(
                    spaceConversationMetadataScope(f.local()),
                    spaceConversationMetadataPartition(f.local()),
                    old,
                    null,
                )
            )
            assertEquals(
                AppResult.Success(CatalogRead.Restart),
                f.catalog().read(f.local(), cursor),
            )
            f.retain(value)
            assertEquals(
                AppResult.Success(CatalogRead.Restart),
                f.catalog().read(f.local(), cursor),
            )
            assertEquals(listOf(value.localMetadata()), f.page().items)
        }

    @Test
    fun versionObservesOnlyAuthenticatedPublishedFactsAndSuppressesEqualEmissions() =
        withDatabase { f ->
            f.retain(f.conversation(1))
            val raw = assertNotNull(f.rawCatalog().checkpoint)
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observePublishedCheckpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ) = flowOf(raw, raw.copy(), raw)

                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> = error("No document payloads")
                }
            assertEquals(
                listOf(
                    AppResult.Success(
                        CollaborationSpaceConversationCatalogVersion(
                            raw.handle.generation,
                            raw.revision,
                        )
                    )
                ),
                f.catalog(store).observeVersion(f.local()).toList(),
            )
            val scope = spaceConversationMetadataScope(f.local())
            val partition = spaceConversationMetadataPartition(f.local())
            assertTrue(
                f.database.replicas.begin(
                    scope,
                    partition,
                    historyId(9893),
                    "unpublished",
                    raw.handle.generation,
                )
            )
            assertEquals(listOf(f.conversation(1).localMetadata()), f.page().items)
            assertEquals(
                AppResult.Success(
                    CollaborationSpaceConversationCatalogVersion(
                        raw.handle.generation,
                        raw.revision,
                    )
                ),
                f.catalog().observeVersion(f.local()).first(),
            )
        }

    @Test
    fun damagedCiphertextOrMissingPeerCannotPublishPartialCatalog() = withDatabase { f ->
        f.retain(f.conversation(1))
        val original = f.rawCatalog()
        for (brokenPair in listOf(false, true)) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ) =
                        f.database.replicas.observe(scope, partition, after, limit).map { page ->
                            if (brokenPair) page
                            else
                                page.copy(
                                    documents = page.documents.map { it.copy(payload = "bad") }
                                )
                        }

                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) =
                        f.database.replicas.observeQueries(scope, partition, queries).map {
                            it.copy(pages = listOf(it.pages.first(), emptyList()))
                        }
                }
            assertIs<AppResult.Failed>(f.catalog(store).read(f.local()))
        }
        assertEquals(original, f.rawCatalog())
    }

    @Test
    fun missingKeysBadCursorsAndBoundsCannotCreateOrRewriteInventory() = withDatabase { f ->
        for (limit in listOf(0, 51)) assertIs<AppResult.Failed>(
            f.catalog().read(f.local(), rawLimit = limit)
        )
        assertIs<AppResult.Failed>(f.catalog().read(f.local(), ""))
        assertIs<AppResult.Failed>(f.catalog().read(f.local(), "x".repeat(4097)))
        assertNull(f.keys.read())
        f.retain(f.conversation(1))
        val original = f.rawCatalog()
        assertIs<AppResult.Failed>(f.catalog().read(f.local(), "tampered"))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.catalog().read(f.local()))
        assertIs<AppResult.Failed>(f.catalog().observeVersion(f.local()).first())
        assertNull(f.keys.read())
        assertEquals(original, f.rawCatalog())
    }

    @Test
    fun cancellationDuringPairReadEscapesWithoutPublishingOrChangingFacts() = withDatabase { f ->
        f.retain(f.conversation(1))
        val original = f.rawCatalog()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow { throw CancellationException("catalog disposed") }
            }
        assertFailsWith<CancellationException> { f.catalog(store).read(f.local()) }
        assertEquals(original, f.rawCatalog())
    }

    @Test
    fun replacementDuringPairAuthenticationRetiresTheWholeCapturedPage() = withDatabase { f ->
        val value = f.conversation(1)
        f.retain(value)
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow {
                    val checkpoint = f.rawCatalog().checkpoint
                    assertTrue(f.database.replicas.replace(scope, partition, checkpoint, null))
                    f.retain(value)
                    emitAll(f.database.replicas.observeQueries(scope, partition, queries))
                }
            }
        assertEquals(AppResult.Success(CatalogRead.Restart), f.catalog(store).read(f.local()))
        assertEquals(listOf(value.localMetadata()), f.page().items)
    }

    @Test
    fun accountReplacementAtFinalPublicationCannotExposeTheCapturedRows() = withDatabase { f ->
        f.retain(f.conversation(1))
        var reads = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observePublishedCheckpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): Flow<ReplicaCheckpoint?> = flow {
                    reads++
                    if (reads == 2)
                        f.activate(
                            f.access.copy(sessionId = "replacement", accountId = historyId(9894))
                        )
                    emitAll(f.database.replicas.observePublishedCheckpoint(scope, partition))
                }
            }
        val result = f.catalog(store).read(f.local())
        assertTrue(result is AppResult.Failed || result == AppResult.Success(CatalogRead.Restart))
        assertEquals(2, reads)
        f.activate()
        assertEquals(listOf(f.conversation(1).localMetadata()), f.page().items)
    }

    private fun CollaborationHistoryFixture.local(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9880),
        )

    private fun CollaborationHistoryFixture.conversation(
        index: Int,
        access: CollaborationSpaceLocalAccess = local(),
    ): CollaborationSpaceConversation {
        val peer = historyId(12000 + index)
        val pair = listOf(access.accountId, peer).sorted()
        return CollaborationSpaceConversation(
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    access.sessionId,
                    access.audience,
                    access.accountId,
                    historyId(9881),
                ),
                access.spaceId,
            ),
            historyId(13000 + index),
            pair[0],
            pair[1],
            peer,
            Instant.parse("2026-11-13T00:00:00Z"),
            0,
        )
    }

    private fun CollaborationHistoryFixture.metadata() =
        EncryptedCollaborationSpaceConversationMetadataRepository(database.replicas, cipher, keys)

    private fun CollaborationHistoryFixture.catalog(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceConversationCatalogRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.retain(value: CollaborationSpaceConversation) =
        assertEquals(AppResult.Success(true), metadata().retain(local(), value))

    private suspend fun CollaborationHistoryFixture.rawCatalog() =
        database.replicas
            .observe(
                spaceConversationMetadataScope(local()),
                spaceConversationMetadataPartition(local()),
                limit = 200,
            )
            .first()

    private suspend fun CollaborationHistoryFixture.page(after: String? = null, limit: Int = 50) =
        assertIs<CatalogRead.Page>(
            assertIs<AppResult.Success<CatalogRead>>(catalog().read(local(), after, limit)).value
        )

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
