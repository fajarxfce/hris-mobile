package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** One contract against real SQLite/IndexedDB and authenticated encryption. */
abstract class CollaborationReceiptStorageContract {
    protected abstract fun databaseOwner(): CollaborationReceiptDatabaseOwner

    @Test
    fun originalFactsAndEveryAliasSurviveRestartReauthenticationAndReplay() = withDatabase { f ->
        assertTrue(f.read().isEmpty())
        val first = f.receipt()
        f.append(first)
        f.append(first.copy(eventIds = listOf("\$second", first.eventIds.single())))
        f.append(first)
        val expected = first.copy(eventIds = first.eventIds + "\$second")
        assertEquals(listOf(expected), f.read())
        val raw = f.raw()
        assertFalse(raw.documents.single().payload!!.contains("fictional"))
        assertFalse(raw.documents.single().payload!!.contains(first.transactionId))
        val renewed = f.access.copy(sessionId = "new-login")
        f.reopen(renewed)
        assertEquals(listOf(expected), f.read(renewed))
        assertTrue(f.read().isEmpty())
        assertEquals(
            AppResult.Success(false),
            f.repository.append(f.access, f.conversation, listOf(first)),
        )
        assertTrue(f.read(renewed.copy(companyId = receiptId(8000))).isEmpty())
        val other = renewed.copy(accountId = receiptId(8001))
        f.activate(other)
        assertTrue(f.read(other).isEmpty())
        f.activate(renewed)
        assertEquals(listOf(expected), f.read(renewed))
    }

    @Test
    fun changedIntentOrOriginalMappingRejectsEntireBatch() = withDatabase { f ->
        val first = f.receipt()
        f.append(first)
        val invalid =
            listOf(
                first.copy(intent = first.intent.copy(text = "Changed content")),
                first.copy(
                    intent =
                        first.intent.copy(
                            createdAt = first.intent.createdAt + kotlin.time.Duration.parse("1s")
                        )
                ),
                first.copy(intent = first.intent.copy(conversationId = receiptId(999))),
                first.copy(
                    intent =
                        first.intent.copy(
                            owner = first.intent.owner.copy(accountId = receiptId(999))
                        )
                ),
                first.copy(device = first.device.copy(nativeSessionId = receiptId(999))),
                first.copy(transactionId = "replacement"),
            )
        for (receipt in invalid) {
            assertIs<AppResult.Failed>(
                f.repository.append(f.access, f.conversation, listOf(f.receipt(2), receipt))
            )
            assertEquals(listOf(first), f.read())
        }
    }

    @Test
    fun eventAndNativeTransactionAliasesCannotBelongToTwoLogicalMessages() = withDatabase { f ->
        val first = f.receipt()
        f.append(first)
        for (second in
            listOf(
                f.receipt(2).copy(eventIds = first.eventIds),
                f.receipt(2).copy(transactionId = first.transactionId),
                f.receipt(2)
                    .copy(
                        transactionId = first.transactionId,
                        device = first.device.copy(bootstrapOperationId = receiptId(999)),
                    ),
            )) {
            assertIs<AppResult.Failed>(
                f.repository.append(f.access, f.conversation, listOf(second))
            )
            assertEquals(listOf(first), f.read())
        }
    }

    @Test
    fun competingWritersCannotLoseAliasesAndLoserCanReread() = withDatabase { f ->
        f.append(f.receipt())
        val ready = CompletableDeferred<Unit>()
        var arrived = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    arrived++
                    if (arrived == 2) ready.complete(Unit)
                    ready.await()
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
        val results = coroutineScope {
            listOf("\$alias-a", "\$alias-b")
                .map { event ->
                    async {
                        f.repository(store)
                            .append(
                                f.access,
                                f.conversation,
                                listOf(f.receipt().copy(eventIds = listOf(event))),
                            )
                    }
                }
                .awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        f.append(f.receipt().copy(eventIds = listOf("\$alias-a", "\$alias-b")))
        assertEquals(
            setOf("\$event-1", "\$alias-a", "\$alias-b"),
            f.read().single().eventIds.toSet(),
        )
    }

    @Test
    fun lostCommitResponseAndCancellationRetainRecoverableReceipt() = withDatabase { f ->
        f.append(f.receipt())
        for (cancel in listOf(false, true)) {
            val event = if (cancel) "\$cancelled-response" else "\$lost-response"
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: ReplicaWakeRequest?,
                    ): Boolean {
                        assertTrue(
                            f.database.replicas.commit(
                                handle,
                                expectedRevision,
                                checkpoint,
                                documents,
                                publish,
                                wake,
                            )
                        )
                        if (cancel) throw CancellationException("Fixture cancellation")
                        error("Fixture response lost")
                    }
                }
            val operation = suspend {
                f.repository(store)
                    .append(
                        f.access,
                        f.conversation,
                        listOf(f.receipt().copy(eventIds = listOf(event))),
                    )
            }
            if (cancel) assertFailsWith<CancellationException> { operation() }
            else assertIs<AppResult.Failed>(operation())
            f.reopen()
            assertTrue(event in f.read().single().eventIds)
        }
    }

    @Test
    fun accountSwitchAtCommitCannotWriteIntoReplacementAccount() = withDatabase { f ->
        f.append(f.receipt())
        val before = f.raw()
        val other = f.access.copy(accountId = receiptId(8001), sessionId = "another-account")
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    f.activate(other)
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
        assertEquals(
            AppResult.Success(false),
            f.repository(store).append(f.access, f.conversation, listOf(f.receipt(2))),
        )
        assertTrue(f.read(other).isEmpty())
        f.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun missingKeyCannotRegenerateOverAnotherInactiveAccount() = withDatabase { f ->
        f.append(f.receipt())
        val before = f.raw()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repository.read(f.access, f.conversation))
        val other = f.access.copy(accountId = receiptId(8001), sessionId = "another-account")
        f.activate(other)
        val receipt =
            f.receipt(2).let { it.copy(intent = it.intent.copy(owner = other.messageOwner())) }
        assertIs<AppResult.Failed>(f.repository.append(other, f.conversation, listOf(receipt)))
        assertNull(f.keys.read())
        f.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun authenticatedMetadataAndCompleteLedgerRejectTampering() = withDatabase { f ->
        f.append(f.receipt(), f.receipt(2))
        val raw = f.raw()
        val doc = raw.documents.first()
        val alterations =
            listOf(
                raw.copy(documents = raw.documents.reversed()),
                raw.copy(documents = raw.documents.drop(1)),
                raw.copy(documents = listOf(doc.copy(version = 1)) + raw.documents.drop(1)),
                raw.copy(documents = listOf(doc.copy(sortKey = "wrong")) + raw.documents.drop(1)),
                raw.copy(
                    documents = listOf(doc.copy(tags = setOf("wrong"))) + raw.documents.drop(1)
                ),
                raw.copy(
                    documents =
                        listOf(doc.copy(payload = raw.documents.last().payload)) +
                            raw.documents.drop(1)
                ),
                raw.copy(checkpoint = raw.checkpoint!!.copy(revision = 1)),
            )
        for (altered in alterations) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ) = flowOf(altered)
                }
            assertIs<AppResult.Failed>(f.repository(store).read(f.access, f.conversation))
            assertIs<AppResult.Failed>(
                f.repository(store).append(f.access, f.conversation, listOf(f.receipt(3)))
            )
            assertEquals(raw, f.raw())
        }
    }

    @Test
    fun callerMutationDuringReadCannotChangeAcknowledgedAliases() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf("\$original")
        val receipts = mutableListOf(f.receipt().copy(eventIds = events))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ) = flow {
                    entered.complete(Unit)
                    release.await()
                    emitAll(f.database.replicas.observe(scope, partition, after, limit))
                }
            }
        coroutineScope {
            val pending = async { f.repository(store).append(f.access, f.conversation, receipts) }
            entered.await()
            events.clear()
            receipts.clear()
            release.complete(Unit)
            assertEquals(AppResult.Success(true), pending.await())
        }
        assertEquals(listOf("\$original"), f.read().single().eventIds)
    }

    @Test
    fun boundedJournalNeverEvictsExistingReceipts() = withDatabase { f ->
        for (batch in (1..128).map(f::receipt).chunked(32)) f.append(*batch.toTypedArray())
        assertIs<AppResult.Failed>(
            f.repository.append(f.access, f.conversation, listOf(f.receipt(129)))
        )
        assertEquals(128, f.read().size)
        val aliases = (1..128).map { "\$extra-$it" }
        assertIs<AppResult.Failed>(
            f.repository.append(
                f.access,
                f.conversation,
                listOf(f.receipt().copy(eventIds = aliases)),
            )
        )
        assertEquals(listOf("\$event-1"), f.read().first().eventIds)
        assertIs<AppResult.Failed>(
            f.repository.append(f.access, f.conversation, (1..33).map(f::receipt))
        )
        assertEquals(128, f.read().size)
    }

    @Test
    fun totalAliasCapacityRejectsWholeNewBatch() = withDatabase { f ->
        val full =
            (1..4).map { index ->
                f.receipt(index).copy(eventIds = (1..128).map { "\$event-$index-$it" })
            }
        f.append(*full.toTypedArray())
        assertIs<AppResult.Failed>(
            f.repository.append(f.access, f.conversation, listOf(f.receipt(5)))
        )
        assertEquals(full, f.read())
    }

    @Test
    fun exactCleanupKeepsAnAuthenticatedEmptyCheckpointAcrossReopen() = withDatabase { f ->
        val first = f.receipt()
        val second = f.receipt(2)
        f.append(first, second)
        val initial = assertNotNull(f.raw().checkpoint)
        assertEquals(
            AppResult.Success(true),
            f.repository.removeReceipts(f.access, f.conversation, listOf(first)),
        )
        assertEquals(listOf(second), f.read())
        assertEquals(initial.handle, f.raw().checkpoint!!.handle)
        assertEquals(
            AppResult.Success(true),
            f.repository.removeReceipts(f.access, f.conversation, listOf(second)),
        )
        assertTrue(f.read().isEmpty())
        assertTrue(f.raw().documents.isEmpty())
        val empty = assertNotNull(f.raw().checkpoint)
        assertEquals(initial.revision + 2, empty.revision)
        f.reopen()
        assertTrue(f.read().isEmpty())
        assertEquals(empty, f.raw().checkpoint)
        val key = f.keys.read()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repository.read(f.access, f.conversation))
        assertIs<AppResult.Failed>(
            f.repository.append(f.access, f.conversation, listOf(f.receipt(3)))
        )
        assertNull(f.keys.read())
        f.keys.write(key)
        assertEquals(
            AppResult.Success(false),
            f.repository.removeReceipts(f.access, f.conversation, listOf(first)),
        )
        f.append(first.copy(eventIds = listOf("$" + "later")))
        assertEquals(listOf("$" + "later"), f.read().single().eventIds)
        assertEquals(initial.handle, f.raw().checkpoint!!.handle)
    }

    @Test
    fun changedReceiptOrInvalidBatchCannotEraseTheObservedLedger() = withDatabase { f ->
        val first = f.receipt()
        val second = f.receipt(2)
        f.append(first, second)
        for (changed in
            listOf(
                first.copy(eventIds = first.eventIds + ("$" + "unseen")),
                first.copy(transactionId = "changed"),
                first.copy(intent = first.intent.copy(text = "changed")),
                f.receipt(3),
            )) {
            assertEquals(
                AppResult.Success(false),
                f.repository.removeReceipts(f.access, f.conversation, listOf(second, changed)),
            )
            assertEquals(listOf(first, second), f.read())
        }
        for (invalid in
            listOf(
                emptyList(),
                listOf(first, first),
                (1..33).map(f::receipt),
                listOf(first.copy(eventIds = emptyList())),
            )) {
            assertIs<AppResult.Failed>(
                f.repository.removeReceipts(f.access, f.conversation, invalid)
            )
        }
        assertEquals(listOf(first, second), f.read())
    }

    @Test
    fun concurrentAliasAppendRejectsCleanupCheckpointWithoutLosingEitherReceipt() =
        withDatabase { f ->
            val first = f.receipt()
            f.append(first, f.receipt(2))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = f.database.replicas
            val held =
                object : ReplicaStore by store {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: ReplicaWakeRequest?,
                    ): Boolean {
                        entered.complete(Unit)
                        release.await()
                        return store.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    }
                }
            coroutineScope {
                val pending = async {
                    f.repository(held).removeReceipts(f.access, f.conversation, listOf(first))
                }
                try {
                    entered.await()
                    f.append(first.copy(eventIds = listOf("$" + "late")))
                } finally {
                    release.complete(Unit)
                }
                assertEquals(AppResult.Success(false), pending.await())
            }
            assertEquals(first.eventIds + ("$" + "late"), f.read().first().eventIds)
            assertEquals(2, f.read().size)
        }

    @Test
    fun ownerReplacementAtCleanupCommitPreservesTheOriginalJournal() = withDatabase { f ->
        val first = f.receipt()
        f.append(first)
        val store = f.database.replicas
        val changed =
            object : ReplicaStore by store {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    f.activate(f.access.copy(sessionId = "other", accountId = receiptId(8001)))
                    return store.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                        wake,
                    )
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(changed).removeReceipts(f.access, f.conversation, listOf(first)),
        )
        f.activate()
        assertEquals(listOf(first), f.read())
    }

    @Test
    fun cancellationBeforeCleanupCommitLeavesAllReceiptsForAnExplicitRetry() = withDatabase { f ->
        val first = f.receipt()
        f.append(first)
        val entered = CompletableDeferred<Unit>()
        val store = f.database.replicas
        val held =
            object : ReplicaStore by store {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val pending = launch {
                f.repository(held).removeReceipts(f.access, f.conversation, listOf(first))
            }
            entered.await()
            pending.cancelAndJoin()
        }
        assertEquals(listOf(first), f.read())
        assertEquals(
            AppResult.Success(true),
            f.repository.removeReceipts(f.access, f.conversation, listOf(first)),
        )
    }

    @Test
    fun cleanupCommitWithLostResponseRemainsRemovedAfterRestart() = withDatabase { f ->
        val first = f.receipt()
        f.append(first, f.receipt(2))
        val store = f.database.replicas
        val lost =
            object : ReplicaStore by store {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    assertTrue(
                        store.commit(handle, expectedRevision, checkpoint, documents, publish, wake)
                    )
                    error("Fixture lost cleanup response")
                }
            }
        assertIs<AppResult.Failed>(
            f.repository(lost).removeReceipts(f.access, f.conversation, listOf(first))
        )
        f.reopen()
        assertEquals(listOf(f.receipt(2)), f.read())
        assertEquals(
            AppResult.Success(false),
            f.repository.removeReceipts(f.access, f.conversation, listOf(first)),
        )
    }

    @Test
    fun cleanupDetachesNestedCallerAliasesBeforeReadingStorage() = withDatabase { f ->
        val first = f.receipt()
        f.append(first)
        val aliases = first.eventIds.toMutableList()
        val batch = mutableListOf(first.copy(eventIds = aliases))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = f.database.replicas
        val held =
            object : ReplicaStore by store {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ) = flow {
                    entered.complete(Unit)
                    release.await()
                    emitAll(store.observe(scope, partition, after, limit))
                }
            }
        coroutineScope {
            val pending = async {
                f.repository(held).removeReceipts(f.access, f.conversation, batch)
            }
            try {
                entered.await()
                aliases.clear()
                batch.clear()
            } finally {
                release.complete(Unit)
            }
            assertEquals(AppResult.Success(true), pending.await())
        }
        assertTrue(f.read().isEmpty())
    }

    @Test
    fun boundedCleanupReleasesLedgerCapacityWithoutEvictingUnselectedMessages() =
        withDatabase { f ->
            val full = (1..128).map(f::receipt)
            for (batch in full.chunked(32)) f.append(*batch.toTypedArray())
            assertEquals(
                AppResult.Success(true),
                f.repository.removeReceipts(f.access, f.conversation, full.take(32)),
            )
            assertEquals(full.drop(32), f.read())
            val added = (129..160).map(f::receipt)
            f.append(*added.toTypedArray())
            assertEquals(full.drop(32) + added, f.read())
            assertIs<AppResult.Failed>(
                f.repository.append(f.access, f.conversation, listOf(f.receipt(161)))
            )
            assertEquals(128, f.raw().documents.size)
        }

    private fun withDatabase(block: suspend (CollaborationReceiptFixture) -> Unit) = runTest {
        val fixture = CollaborationReceiptFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
