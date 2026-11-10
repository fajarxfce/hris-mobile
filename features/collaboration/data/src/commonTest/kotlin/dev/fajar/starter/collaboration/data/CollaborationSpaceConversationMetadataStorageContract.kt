package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceConversationMetadataRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceConversationMetadataStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.local(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9900),
        )

    private fun conversation(
        access: CollaborationSpaceLocalAccess,
        index: Int = 1,
    ): CollaborationSpaceConversation {
        val peer = historyId(10000 + index)
        val pair = listOf(access.accountId, peer).sorted()
        return CollaborationSpaceConversation(
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    access.sessionId,
                    access.audience,
                    access.accountId,
                    historyId(9901),
                ),
                access.spaceId,
            ),
            historyId(11000 + index),
            pair[0],
            pair[1],
            peer,
            Instant.parse("2026-11-07T00:00:00.123456Z"),
            0,
        )
    }

    private fun CollaborationHistoryFixture.metadata(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceConversationMetadataRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.rawMetadata(
        access: CollaborationSpaceLocalAccess = local()
    ) =
        database.replicas
            .observe(
                spaceConversationMetadataScope(access),
                spaceConversationMetadataPartition(access),
                limit = 200,
            )
            .first()

    private suspend fun CollaborationHistoryFixture.assertMetadata(
        value: CollaborationSpaceConversation,
        access: CollaborationSpaceLocalAccess = local(),
    ) {
        val peer =
            if (value.firstAccountId == access.accountId) value.secondAccountId
            else value.firstAccountId
        assertEquals(AppResult.Success(value.localMetadata()), metadata().getById(access, value.id))
        assertEquals(AppResult.Success(value.localMetadata()), metadata().findByPeer(access, peer))
    }

    @Test
    fun emptyReadsAreKeylessAndCanonicalFactsSurviveLoginParentAndCompanyReplacement() =
        withDatabase { f ->
            val value = conversation(f.local())
            assertEquals(AppResult.Success(null), f.metadata().getById(f.local(), value.id))
            assertEquals(
                AppResult.Success(null),
                f.metadata().findByPeer(f.local(), value.secondAccountId),
            )
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
            val original = f.rawMetadata()
            assertEquals(2, original.documents.size)
            assertEquals(0, original.checkpoint?.revision)
            assertTrue(
                original.documents.all {
                    it.payload != null &&
                        it.tags == setOf(it.id) &&
                        it.id.matches(Regex("m-[0-9a-f]{64}"))
                }
            )
            assertFalse(original.documents.toString().contains(value.id))
            assertFalse(original.documents.toString().contains(value.secondAccountId))
            f.assertMetadata(value)
            val renewed = f.access.copy(sessionId = "new-login", companyId = historyId(9902))
            f.reopen(renewed)
            f.assertMetadata(value, f.local(renewed))
            val rebound =
                value.copy(
                    owner =
                        value.owner.copy(
                            parent =
                                value.owner.parent.copy(
                                    sessionId = renewed.sessionId,
                                    nativeSessionId = historyId(9903),
                                )
                        )
                )
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(renewed), rebound))
            val after = f.rawMetadata(f.local(renewed))
            assertEquals(original.documents, after.documents)
            assertEquals(original.checkpoint?.payload, after.checkpoint?.payload)
            assertEquals(original.checkpoint?.revision, after.checkpoint?.revision)
            assertIs<AppResult.Failed>(f.metadata().retain(f.local(renewed), value))
        }

    @Test
    fun appendsKeepEarlierCiphertextAndExactLookupsReadBoundedPublishedIndexes() =
        withDatabase { f ->
            val first = conversation(f.local())
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
            val before = f.rawMetadata()
            val second = conversation(f.local(), 2)
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), second))
            val after = f.rawMetadata()
            assertEquals(4, after.documents.size)
            assertEquals(1, after.checkpoint?.revision)
            assertTrue(after.documents.containsAll(before.documents))
            var indexedReads = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        indexedReads++
                        assertEquals(2, queries.size)
                        assertTrue(
                            queries.all {
                                it.tag != null &&
                                    it.limit == 2 &&
                                    it.after == null &&
                                    it.before == null
                            }
                        )
                        return f.database.replicas.observeQueries(scope, partition, queries)
                    }

                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> = error("No partition scan")

                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ): ReplicaPage = error("No staging projection")
                }
            assertEquals(
                AppResult.Success(first.localMetadata()),
                f.metadata(store).getById(f.local(), first.id),
            )
            assertEquals(
                AppResult.Success(second.localMetadata()),
                f.metadata(store).findByPeer(f.local(), second.secondAccountId),
            )
            assertEquals(2, indexedReads)
        }

    @Test
    fun changedFactsOrReassignedConversationAndPeerCannotOverwriteCanonicalIndexes() =
        withDatabase { f ->
            val value = conversation(f.local())
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
            val before = f.rawMetadata()
            for (changed in
                listOf(
                    value.copy(createdBy = value.firstAccountId),
                    value.copy(createdAt = Instant.parse("2026-11-06T00:00:00Z")),
                )) {
                assertEquals(AppResult.Success(false), f.metadata().retain(f.local(), changed))
            }
            assertIs<AppResult.Failed>(
                f.metadata().retain(f.local(), value.copy(id = historyId(11200)))
            )
            assertIs<AppResult.Failed>(
                f.metadata().retain(f.local(), value.copy(secondAccountId = historyId(11201)))
            )
            assertEquals(before, f.rawMetadata())
            f.assertMetadata(value)
        }

    @Test
    fun concurrentFirstInsertReturnsConflictAndExplicitReconciliationKeepsBothConversations() =
        withDatabase { f ->
            val first = conversation(f.local())
            val second = conversation(f.local(), 2)
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertEquals(
                            AppResult.Success(true),
                            f.metadata().retain(f.local(), second),
                        )
                        return f.database.replicas.replace(
                            scope,
                            partition,
                            expected,
                            snapshot,
                            wake,
                        )
                    }
                }
            assertEquals(AppResult.Success(false), f.metadata(store).retain(f.local(), first))
            assertEquals(AppResult.Success(null), f.metadata().getById(f.local(), first.id))
            f.assertMetadata(second)
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
            f.assertMetadata(first)
            f.assertMetadata(second)
        }

    @Test
    fun concurrentAppendDoesNotOverwriteWinningCheckpointOrPublishHalfAnIndex() =
        withDatabase { f ->
            val first = conversation(f.local())
            val second = conversation(f.local(), 2)
            val third = conversation(f.local(), 3)
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
            var calls = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        calls++
                        assertEquals(2, documents.size)
                        assertTrue(publish)
                        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), third))
                        return f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    }
                }
            assertEquals(AppResult.Success(false), f.metadata(store).retain(f.local(), second))
            assertEquals(1, calls)
            assertEquals(4, f.rawMetadata().documents.size)
            assertEquals(
                AppResult.Success(null),
                f.metadata().findByPeer(f.local(), second.secondAccountId),
            )
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), second))
            listOf(first, second, third).forEach { f.assertMetadata(it) }
        }

    @Test
    fun lostInsertAndAppendResponsesRetainExactCiphertextAfterReopen() = withDatabase { f ->
        fun lossyStore(): ReplicaStore =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    check(f.database.replicas.replace(scope, partition, expected, snapshot, wake))
                    throw CancellationException("Lost first publication response")
                }

                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    check(
                        f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    )
                    throw CancellationException("Lost append response")
                }
            }
        for (index in 1..2) {
            val value = conversation(f.local(), index)
            assertFailsWith<CancellationException> {
                f.metadata(lossyStore()).retain(f.local(), value)
            }
            val before = f.rawMetadata()
            f.reopen()
            f.assertMetadata(value)
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
            assertEquals(before, f.rawMetadata())
        }
    }

    @Test
    fun accountReplacementAtBothWriteBoundariesPublishesIntoNeitherAccount() = withDatabase { f ->
        val foreign = f.access.copy(sessionId = "other", accountId = historyId(9904))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(foreign)
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }

                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(foreign)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                        wake,
                    )
                }
            }
        val first = conversation(f.local())
        assertEquals(AppResult.Success(false), f.metadata(store).retain(f.local(), first))
        assertEquals(AppResult.Success(null), f.metadata().getById(f.local(foreign), first.id))
        f.activate()
        assertNull(f.rawMetadata().checkpoint)
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
        val before = f.rawMetadata()
        assertEquals(
            AppResult.Success(false),
            f.metadata(store).retain(f.local(), conversation(f.local(), 2)),
        )
        f.activate()
        assertEquals(before, f.rawMetadata())
    }

    @Test
    fun accountServerAndSpaceHaveSeparateCiphertextAndOpaqueIndexes() = withDatabase { f ->
        val value = conversation(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        val before = f.rawMetadata()
        val variants =
            listOf(
                f.access.copy(sessionId = "foreign-account", accountId = historyId(9905)),
                f.access.copy(
                    sessionId = "foreign-server",
                    audience = "https://other.example.test/",
                ),
                f.access,
            )
        for ((index, owner) in variants.withIndex()) {
            f.activate(owner)
            val access =
                f.local(owner).let { if (index == 2) it.copy(spaceId = historyId(9906)) else it }
            assertEquals(AppResult.Success(null), f.metadata().getById(access, value.id))
            val other = conversation(access)
            assertEquals(AppResult.Success(true), f.metadata().retain(access, other))
            val raw = f.rawMetadata(access)
            assertTrue(
                raw.documents
                    .map { it.id }
                    .toSet()
                    .intersect(before.documents.map { it.id }.toSet())
                    .isEmpty()
            )
            f.assertMetadata(other, access)
            f.activate()
            assertEquals(before, f.rawMetadata())
        }
    }

    @Test
    fun metadataOnlyRecordsFenceLostSharedKeysIncludingInactiveAccountsAndHistoryWriters() =
        withDatabase { f ->
            val value = conversation(f.local())
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
            val before = f.rawMetadata()
            f.keys.write(null)
            assertIs<AppResult.Failed>(f.metadata().getById(f.local(), value.id))
            assertIs<AppResult.Failed>(f.metadata().retain(f.local(), value))
            assertIs<AppResult.Failed>(
                f.repository.merge(f.access, f.conversation, f.room, listOf(f.event()))
            )
            val foreign = f.access.copy(sessionId = "foreign", accountId = historyId(9907))
            f.activate(foreign)
            assertIs<AppResult.Failed>(
                f.metadata().retain(f.local(foreign), conversation(f.local(foreign)))
            )
            assertNull(f.keys.read())
            f.activate()
            assertEquals(before, f.rawMetadata())
        }

    @Test
    fun existingHistoryKeyIsReusedWithoutChangingEitherProjection() = withDatabase { f ->
        f.merge(f.event())
        val key = f.keys.read()
        val history = f.raw()
        val value = conversation(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        f.assertMetadata(value)
        assertEquals(key, f.keys.read())
        assertEquals(history, f.raw())
        assertNotNull(f.lookup())
    }

    @Test
    fun missingDuplicateOrTamperedComplementaryIndexCannotPublishARead() = withDatabase { f ->
        val value = conversation(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        for (change in 0..5) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) =
                        f.database.replicas.observeQueries(scope, partition, queries).map { raw ->
                            val peer = raw.pages[1].single()
                            raw.copy(
                                pages =
                                    listOf(
                                        raw.pages[0],
                                        when (change) {
                                            0 -> emptyList()
                                            1 -> listOf(peer, peer)
                                            2 -> listOf(peer.copy(payload = "corrupt"))
                                            3 -> listOf(peer.copy(version = 1))
                                            4 -> listOf(peer.copy(tags = emptySet()))
                                            else -> listOf(peer.copy(sortKey = "foreign"))
                                        },
                                    )
                            )
                        }
                }
            assertIs<AppResult.Failed>(f.metadata(store).getById(f.local(), value.id))
        }
        f.assertMetadata(value)
    }

    @Test
    fun corruptCheckpointStagingAndOneSidedWriteInventoryCannotReplaceFacts() = withDatabase { f ->
        val value = conversation(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        val before = f.rawMetadata()
        val checkpoint = assertNotNull(before.checkpoint)
        for (changed in
            listOf(
                checkpoint.copy(payload = "corrupt"),
                checkpoint.copy(payload = "x".repeat(4097)),
                checkpoint.copy(published = false),
                checkpoint.copy(revision = 1),
                checkpoint.copy(handle = checkpoint.handle.copy(generation = historyId(9910))),
                checkpoint.copy(handle = checkpoint.handle.copy(scope = ReplicaScope("foreign"))),
                checkpoint.copy(handle = checkpoint.handle.copy(partition = "foreign")),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ) = before.copy(checkpoint = changed)

                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) = flowOf(ReplicaPage(changed, before.documents.filter { it.id == id }))
                }
            assertIs<AppResult.Failed>(f.metadata(store).retain(f.local(), value))
            assertIs<AppResult.Failed>(f.metadata(store).getById(f.local(), value.id))
        }
        val missing =
            object : ReplicaStore by f.database.replicas {
                override suspend fun workingDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    ids: List<String>,
                ) = before.copy(documents = before.documents.take(1))
            }
        assertIs<AppResult.Failed>(f.metadata(missing).retain(f.local(), value))
        assertEquals(before, f.rawMetadata())
    }

    @Test
    fun changedOwnerDuringReadAndCancellationCannotPublishLateFacts() = withDatabase { f ->
        val value = conversation(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        val foreign = f.access.copy(sessionId = "foreign", accountId = historyId(9911))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    val page = f.database.replicas.observeQueries(scope, partition, queries).first()
                    f.activate(foreign)
                    emit(page)
                }
            }
        assertIs<AppResult.Failed>(f.metadata(store).getById(f.local(), value.id))
        f.activate()
        val entered = CompletableDeferred<Unit>()
        val blocked =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) =
                    flow<ReplicaQueryPage> {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
            }
        val job =
            CoroutineScope(currentCoroutineContext()).async {
                f.metadata(blocked).findByPeer(f.local(), value.secondAccountId)
            }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        f.assertMetadata(value)
    }

    @Test
    fun invalidCanonicalFactsAndScopesFailBeforeCreatingAKey() = withDatabase { f ->
        val access = f.local()
        val value = conversation(access)
        for (bad in
            listOf(
                value.copy(version = 1),
                value.copy(id = "00000000-0000-0000-0000-000000000000"),
                value.copy(secondAccountId = value.firstAccountId),
                value.copy(createdBy = historyId(9912)),
                value.copy(createdAt = Instant.parse("2026-11-07T00:00:00.000000001Z")),
                value.copy(owner = value.owner.copy(spaceId = historyId(9913))),
            )) {
            assertIs<AppResult.Failed>(f.metadata().retain(access, bad))
        }
        for (bad in
            listOf(
                access.copy(sessionId = ""),
                access.copy(audience = "bad\n"),
                access.copy(accountId = "invalid"),
                access.copy(spaceId = "00000000-0000-0000-0000-000000000000"),
            )) {
            assertIs<AppResult.Failed>(f.metadata().getById(bad, value.id))
            assertIs<AppResult.Failed>(f.metadata().retain(bad, value))
        }
        assertIs<AppResult.Failed>(f.metadata().getById(access, "invalid"))
        assertIs<AppResult.Failed>(f.metadata().findByPeer(access, access.accountId))
        assertNull(f.keys.read())
    }

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
