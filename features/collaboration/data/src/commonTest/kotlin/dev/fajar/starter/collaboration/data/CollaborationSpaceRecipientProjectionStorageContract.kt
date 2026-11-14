package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRecipientProjectionStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun missingLocalSourceDoesNotCreateKeysOrScheduleWork() = withDatabase { f ->
        assertEquals(CollaborationSpaceRecipientProjection(null, emptyList()), f.readProjection())
        assertNull(f.keys.read())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun encryptedOriginalsAndIndependentDraftSurviveReopenForTheExactPeer() = withDatabase { f ->
        val first = f.original(0)
        val other =
            f.original(1).let {
                it.copy(original = it.original.copy(peerAccountId = historyId(9990)))
            }
        val later = first.nextDraft.copy(revision = historyId(9950), text = "Later unsent typing")
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, first),
        )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, other),
        )
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, first.nextDraft, later))
        val before = f.readProjection()
        assertEquals(CollaborationSpaceRecipientProjection(later, listOf(first)), before)
        f.reopen(f.access.copy(sessionId = "resumed"))
        val resumed = f.access.copy(sessionId = "resumed")
        assertEquals(
            before,
            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                    f.projection().observe(resumed, first.original.peerAccountId).first()
                )
                .value,
        )
        assertEquals(
            CollaborationSpaceRecipientProjection(null, emptyList()),
            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                    f.projection()
                        .observe(
                            resumed.copy(spaceId = historyId(9991)),
                            first.original.peerAccountId,
                        )
                        .first()
                )
                .value,
        )
    }

    @Test
    fun typingDoesNotReloadOrDecryptTheRetainedOriginalInventory() = withDatabase { f ->
        val original = f.original(0)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, original),
        )
        withContext(Dispatchers.Default) {
            var inventories = 0
            var originalDecrypts = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        if (queries.any { it.tag == RECIPIENT_OUTBOX_TAG }) inventories++
                        return f.database.replicas.observeQueries(scope, partition, queries)
                    }
                }
            val cipher =
                object : ContentCipher by f.cipher {
                    override suspend fun decrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        ciphertext: ByteArray,
                    ): ByteArray {
                        if (
                            associatedData
                                .decodeToString()
                                .contains("hris-space-recipient-submission-v1")
                        )
                            originalDecrypts++
                        return f.cipher.decrypt(key, associatedData, ciphertext)
                    }
                }
            val values =
                Channel<AppResult<CollaborationSpaceRecipientProjection>>(Channel.UNLIMITED)
            val job = launch {
                f.projection(store, cipher)
                    .observe(f.access, original.original.peerAccountId)
                    .collect(values::send)
            }
            try {
                assertEquals(
                    listOf(original),
                    withTimeout(10_000) {
                            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                    values.receive()
                                )
                                .value
                        }
                        .submissions,
                )
                assertEquals(1, inventories)
                assertEquals(1, originalDecrypts)
                var draft = original.nextDraft
                repeat(4) { n ->
                    val next = draft.copy(revision = historyId(9700 + n), text = "Typed $n")
                    assertEquals(AppResult.Success(true), f.drafts().replace(f.access, draft, next))
                    draft = next
                    val value =
                        withTimeout(10_000) {
                            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                    values.receive()
                                )
                                .value
                        }
                    assertEquals(next, value.draft)
                    assertEquals(listOf(original), value.submissions)
                    assertEquals(1, inventories)
                    assertEquals(1, originalDecrypts)
                }
                val second = f.original(2)
                assertEquals(
                    AppResult.Success(CollaborationComposerHandoffResult.STORED),
                    f.outbox().handoff(f.access, draft, second),
                )
                val value =
                    withTimeout(10_000) {
                        assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                values.receive()
                            )
                            .value
                    }
                assertEquals(listOf(original, second), value.submissions)
                assertEquals(2, inventories)
                assertEquals(3, originalDecrypts)
                assertEquals(AppResult.Success(true), f.outbox().remove(f.access, original))
                assertEquals(
                    listOf(second),
                    withTimeout(10_000) {
                            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                    values.receive()
                                )
                                .value
                        }
                        .submissions,
                )
                assertEquals(3, inventories)
                assertEquals(4, originalDecrypts)
            } finally {
                job.cancelAndJoin()
                values.cancel()
            }
        }
    }

    @Test
    fun cancellationStopsThePendingRawHeaderWithoutCreatingContentKeys() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        coroutineScope {
            val job = launch { f.projection(store).observe(f.access, historyId(9200)).collect() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(stopped.isCompleted)
        }
        assertNull(f.keys.read())
    }

    @Test
    fun aDraftChangeDuringInventoryAcquisitionPublishesOnlyTheCoherentNewFrame() =
        withDatabase { f ->
            val original = f.original(0)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, null, original),
            )
            val later =
                original.nextDraft.copy(revision = historyId(9770), text = "Changed while reading")
            var inventoryReads = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> = flow {
                        if (
                            queries.any { it.tag == RECIPIENT_OUTBOX_TAG } && ++inventoryReads == 1
                        ) {
                            assertEquals(
                                AppResult.Success(true),
                                f.drafts().replace(f.access, original.nextDraft, later),
                            )
                        }
                        emitAll(f.database.replicas.observeQueries(scope, partition, queries))
                    }
                }
            val result =
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) {
                        f.projection(store)
                            .observe(f.access, original.original.peerAccountId)
                            .first()
                    }
                }
            assertEquals(
                CollaborationSpaceRecipientProjection(later, listOf(original)),
                assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(result).value,
            )
            assertEquals(2, inventoryReads)
        }

    @Test
    fun fullSourceIsBoundedAndKeepsOnlyTheSelectedPeersOrderedOriginals() = withDatabase { f ->
        val originals =
            (0 until 128).map { index ->
                f.original(index).let {
                    if (index % 2 == 0) it
                    else it.copy(original = it.original.copy(peerAccountId = historyId(9990)))
                }
            }
        val draft = originals[126].nextDraft
        f.seed(originals, draft, marker = true)
        var inventoryReads = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    if (queries.any { it.tag == RECIPIENT_OUTBOX_TAG }) {
                        inventoryReads++
                        assertEquals(
                            listOf(ReplicaQuery(RECIPIENT_OUTBOX_TAG, limit = 129)),
                            queries,
                        )
                    } else
                        assertEquals(
                            listOf(
                                ReplicaQuery(id = draft.peerAccountId, limit = 1),
                                ReplicaQuery(id = RECIPIENT_OUTBOX_VERSION_ID, limit = 1),
                            ),
                            queries,
                        )
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }
            }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                    f.projection(store).observe(f.access, draft.peerAccountId).first()
                )
                .value
        assertEquals(
            CollaborationSpaceRecipientProjection(
                draft,
                originals.filter { it.original.peerAccountId == draft.peerAccountId },
            ),
            result,
        )
        assertEquals(1, inventoryReads)
    }

    @Test
    fun legacyMarkerUpgradePreservesTheOriginalCacheAcrossTyping() = withDatabase { f ->
        val original = f.original(0)
        f.seed(listOf(original), original.nextDraft, marker = false)
        withContext(Dispatchers.Default) {
            var inventories = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        if (queries.any { it.tag == RECIPIENT_OUTBOX_TAG }) inventories++
                        return f.database.replicas.observeQueries(scope, partition, queries)
                    }
                }
            val values =
                Channel<AppResult<CollaborationSpaceRecipientProjection>>(Channel.UNLIMITED)
            val job = launch {
                f.projection(store)
                    .observe(f.access, original.original.peerAccountId)
                    .collect(values::send)
            }
            try {
                assertEquals(
                    listOf(original),
                    withTimeout(10_000) {
                            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                    values.receive()
                                )
                                .value
                        }
                        .submissions,
                )
                val later =
                    original.nextDraft.copy(
                        revision = historyId(9771),
                        text = "Legacy later typing",
                    )
                assertEquals(
                    AppResult.Success(true),
                    f.drafts().replace(f.access, original.nextDraft, later),
                )
                val updated =
                    withTimeout(10_000) {
                        assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                values.receive()
                            )
                            .value
                    }
                assertEquals(
                    CollaborationSpaceRecipientProjection(later, listOf(original)),
                    updated,
                )
                assertEquals(1, inventories)
            } finally {
                job.cancelAndJoin()
                values.cancel()
            }
        }
    }

    @Test
    fun malformedHeaderClearsCachedOriginalsBeforeTheNextValidAcquisition() = withDatabase { f ->
        val original = f.original(0)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, original),
        )
        val raw =
            f.database.replicas
                .observeQueries(
                    spaceHistoryScope(f.access),
                    recipientDraftPartition(f.access),
                    listOf(
                        ReplicaQuery(id = original.original.peerAccountId, limit = 1),
                        ReplicaQuery(id = RECIPIENT_OUTBOX_VERSION_ID, limit = 1),
                    ),
                )
                .first()
        withContext(Dispatchers.Default) {
            val headers = MutableStateFlow(raw)
            var inventories = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        if (queries.any { it.tag == RECIPIENT_OUTBOX_TAG }) {
                            inventories++
                            return f.database.replicas.observeQueries(scope, partition, queries)
                        }
                        return headers
                    }
                }
            val values =
                Channel<AppResult<CollaborationSpaceRecipientProjection>>(Channel.UNLIMITED)
            val job = launch {
                f.projection(store)
                    .observe(f.access, original.original.peerAccountId)
                    .collect(values::send)
            }
            try {
                assertEquals(
                    listOf(original),
                    withTimeout(10_000) {
                            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                    values.receive()
                                )
                                .value
                        }
                        .submissions,
                )
                headers.value =
                    raw.copy(
                        pages =
                            listOf(
                                raw.pages[0],
                                listOf(raw.pages[1].single().copy(payload = "invalid-ciphertext")),
                            )
                    )
                assertIs<AppResult.Failed>(withTimeout(10_000) { values.receive() })
                headers.value = raw
                assertEquals(
                    listOf(original),
                    withTimeout(10_000) {
                            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                    values.receive()
                                )
                                .value
                        }
                        .submissions,
                )
                assertEquals(2, inventories)
            } finally {
                job.cancelAndJoin()
                values.cancel()
            }
        }
    }

    @Test
    fun mutableHeaderBuffersAreDetachedBeforeCipherSuspension() = withDatabase { f ->
        val original = f.original(0)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, original),
        )
        val raw =
            f.database.replicas
                .observeQueries(
                    spaceHistoryScope(f.access),
                    recipientDraftPartition(f.access),
                    listOf(
                        ReplicaQuery(id = original.original.peerAccountId, limit = 1),
                        ReplicaQuery(id = RECIPIENT_OUTBOX_VERSION_ID, limit = 1),
                    ),
                )
                .first()
        val draft = raw.pages[0].toMutableList()
        val marker = raw.pages[1].toMutableList()
        val pages = mutableListOf<List<ReplicaDocument>>(draft, marker)
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> =
                    if (queries.any { it.tag == RECIPIENT_OUTBOX_TAG })
                        f.database.replicas.observeQueries(scope, partition, queries)
                    else flowOf(raw.copy(pages = pages))
            }
        var released = false
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun decrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    ciphertext: ByteArray,
                ): ByteArray {
                    if (!released) {
                        released = true
                        draft.clear()
                        marker.clear()
                        pages.clear()
                    }
                    return f.cipher.decrypt(key, associatedData, ciphertext)
                }
            }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                    f.projection(store, cipher)
                        .observe(f.access, original.original.peerAccountId)
                        .first()
                )
                .value
        assertTrue(released)
        assertEquals(
            CollaborationSpaceRecipientProjection(original.nextDraft, listOf(original)),
            result,
        )
    }

    @Test
    fun replacementGenerationDoesNotReuseAnEqualRevisionFromThePreviousSource() =
        withDatabase { f ->
            val first = f.original(0)
            f.seed(listOf(first), first.nextDraft, marker = true)
            withContext(Dispatchers.Default) {
                val values =
                    Channel<AppResult<CollaborationSpaceRecipientProjection>>(Channel.UNLIMITED)
                val job = launch {
                    f.projection()
                        .observe(f.access, first.original.peerAccountId)
                        .collect(values::send)
                }
                try {
                    assertEquals(
                        listOf(first),
                        withTimeout(10_000) {
                                assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                        values.receive()
                                    )
                                    .value
                            }
                            .submissions,
                    )
                    val second = f.original(1)
                    val previous =
                        f.database.replicas.checkpoint(
                            spaceHistoryScope(f.access),
                            recipientDraftPartition(f.access),
                        )
                    f.seed(
                        listOf(second),
                        second.nextDraft,
                        marker = true,
                        generation = historyId(9781),
                        expected = previous,
                    )
                    assertEquals(
                        CollaborationSpaceRecipientProjection(second.nextDraft, listOf(second)),
                        withTimeout(10_000) {
                            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                                    values.receive()
                                )
                                .value
                        },
                    )
                } finally {
                    job.cancelAndJoin()
                    values.cancel()
                }
            }
        }

    @Test
    fun oversizedRawOriginalInventoryFailsWithoutPublishingATruncatedThread() = withDatabase { f ->
        val originals = (0 until 129).map { f.original(it) }
        f.seed(originals, originals.last().nextDraft, marker = true)
        assertIs<AppResult.Failed>(
            f.projection().observe(f.access, originals.first().original.peerAccountId).first()
        )
    }

    @Test
    fun releasingTheOneShotInventoryBufferCannotEraseCapturedOriginals() = withDatabase { f ->
        val original = f.original(0)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, original),
        )
        val raw =
            f.database.replicas
                .observeQueries(
                    spaceHistoryScope(f.access),
                    recipientDraftPartition(f.access),
                    listOf(ReplicaQuery(RECIPIENT_OUTBOX_TAG, limit = 129)),
                )
                .first()
        val documents = raw.pages.single().toMutableList()
        val pages = mutableListOf<List<ReplicaDocument>>(documents)
        var released = false
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> =
                    if (queries.any { it.tag == RECIPIENT_OUTBOX_TAG })
                        flow {
                            try {
                                emit(raw.copy(pages = pages))
                            } finally {
                                documents.clear()
                                pages.clear()
                                released = true
                            }
                        }
                    else f.database.replicas.observeQueries(scope, partition, queries)
            }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                    f.projection(store).observe(f.access, original.original.peerAccountId).first()
                )
                .value
        assertTrue(released)
        assertEquals(
            CollaborationSpaceRecipientProjection(original.nextDraft, listOf(original)),
            result,
        )
    }

    private suspend fun CollaborationSpaceHistoryFixture.seed(
        originals: List<CollaborationSpaceRecipientSubmission>,
        draft: CollaborationSpaceRecipientDraft,
        marker: Boolean,
        generation: String = historyId(9780),
        expected: ReplicaCheckpoint? = null,
    ) {
        CollaborationHistoryKeys(database.replicas, cipher, keys).use(create = true) { key, _ ->
            val drafts = CollaborationSpaceRecipientDraftCodec(access, cipher, key)
            val submissions = CollaborationSpaceRecipientSubmissionCodec(access, cipher, key)
            val documents =
                originals.map { submissions.encode(generation, 0, it) } +
                    drafts.encode(generation, 0, draft) +
                    if (marker)
                        listOf(
                            CollaborationSpaceRecipientOutboxVersionCodec(access, cipher, key)
                                .encode(generation, 0, 0)
                        )
                    else emptyList()
            assertTrue(
                database.replicas.replace(
                    spaceHistoryScope(access),
                    recipientDraftPartition(access),
                    expected,
                    ReplicaSnapshot(generation, drafts.checkpoint(generation, 0), documents),
                )
            )
        }
    }

    private fun CollaborationSpaceHistoryFixture.projection(
        store: ReplicaStore = database.replicas,
        cipher: ContentCipher = this.cipher,
    ) = EncryptedCollaborationSpaceRecipientProjectionRepository(store, cipher, keys)

    private fun CollaborationSpaceHistoryFixture.outbox() =
        EncryptedCollaborationSpaceRecipientOutboxRepository(database.replicas, cipher, keys)

    private fun CollaborationSpaceHistoryFixture.drafts() =
        EncryptedCollaborationSpaceRecipientDraftRepository(database.replicas, cipher, keys)

    private suspend fun CollaborationSpaceHistoryFixture.readProjection() =
        assertIs<AppResult.Success<CollaborationSpaceRecipientProjection>>(
                projection().observe(access, historyId(9200)).first()
            )
            .value

    private fun CollaborationSpaceHistoryFixture.original(index: Int) =
        CollaborationSpaceRecipientSubmission(
            CollaborationSpaceRecipientDraft(
                historyId(9200),
                historyId(9300 + index * 2),
                "Original $index",
                Instant.fromEpochMilliseconds(1_793_491_200_000L + index),
            ),
            historyId(9301 + index * 2),
            Instant.fromEpochMilliseconds(1_793_491_200_000L + index),
        )

    private fun withDatabase(block: suspend (CollaborationSpaceHistoryFixture) -> Unit) = runTest {
        val f = CollaborationSpaceHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
