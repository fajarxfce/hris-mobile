package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Same real encrypted storage contract on SQLite and IndexedDB. */
abstract class CollaborationHistoryStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun aliasesSurviveRestartReauthenticationAndReplayWithNoPlaintextIndexes() = withDatabase { f ->
        assertTrue(f.read().messages.isEmpty())
        assertNull(f.keys.read())
        val first = f.event()
        val retry = first.copy(eventId = "\$retry", timestamp = first.timestamp + 86_400_001)
        f.merge(first, retry, first)
        val expected = f.read().messages.single()
        assertEquals(first.timestamp, expected.timestamp)
        assertEquals(listOf(first.eventId, retry.eventId), expected.eventIds)
        assertEquals(expected, f.lookup(retry.eventId))
        assertEquals(
            AppResult.Success(expected),
            f.repository.getByLogicalId(
                f.access,
                f.conversation,
                f.room,
                first.sender,
                first.logicalId!!,
            ),
        )
        assertEquals(
            AppResult.Success(null),
            f.repository.getByLogicalId(
                f.access,
                f.conversation,
                f.room,
                "@forged:example.test",
                requireNotNull(first.logicalId),
            ),
        )
        assertEquals(
            AppResult.Success(CollaborationHistoryPage(listOf(expected), null)),
            f.repository.observe(f.access, f.conversation).first(),
        )
        for (raw in f.raw().documents) {
            assertFalse(raw.toString().contains("fictional"))
            assertFalse(raw.toString().contains(first.eventId))
            assertFalse(raw.toString().contains(first.logicalId!!))
        }
        val renewed = f.access.copy(sessionId = "renewed-login")
        f.reopen(renewed)
        assertEquals(listOf(expected), f.read(renewed).messages)
        assertTrue(f.read().messages.isEmpty())
        assertEquals(
            AppResult.Success(false),
            f.repository.merge(f.access, f.conversation, f.room, listOf(first)),
        )
        assertTrue(f.read(renewed.copy(companyId = historyId(8000))).messages.isEmpty())
        val other = renewed.copy(accountId = historyId(8001))
        f.activate(other)
        assertTrue(f.read(other).messages.isEmpty())
        f.activate(renewed)
        assertEquals(listOf(expected), f.read(renewed).messages)
    }

    @Test
    fun copiedLogicalIdFromAnotherSenderAndLegacyMessagesStaySeparate() = withDatabase { f ->
        val first = f.event()
        f.merge(
            first,
            first.copy(sender = "@another:example.test", eventId = "\$another"),
            first.copy(logicalId = null, eventId = "\$legacy-a"),
            first.copy(logicalId = null, eventId = "\$legacy-b"),
        )
        assertEquals(4, f.read().messages.size)
        assertNotEquals(f.lookup(), f.lookup("\$another"))
        assertEquals(listOf("\$legacy-a"), f.lookup("\$legacy-a")!!.eventIds)
    }

    @Test
    fun changedOriginalIdentityOrContentRejectsTheWholeBatch() = withDatabase { f ->
        val first = f.event()
        f.merge(first)
        val before = f.raw()
        val conflicts =
            listOf(
                first.copy(text = "Different original"),
                first.copy(contentHash = "f".repeat(64)),
                first.copy(sender = "@forged:example.test"),
                first.copy(logicalId = historyId(50)),
                first.copy(timestamp = first.timestamp + 1),
                first.copy(eventId = "\$retry-conflict", text = "Changed retry"),
            )
        for (conflict in conflicts) {
            assertIs<AppResult.Failed>(
                f.repository.merge(f.access, f.conversation, f.room, listOf(f.event(2), conflict))
            )
            assertEquals(before, f.raw())
        }
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, "!wrong:example.test", listOf(f.event(2)))
        )
        assertIs<AppResult.Failed>(
            f.repository.getByEvent(f.access, f.conversation, "!wrong:example.test", first.eventId)
        )
    }

    @Test
    fun redactionsBeforeOriginalAndAfterRetryNeverResurrectTheMessage() = withDatabase { f ->
        val first = f.event()
        f.merge(CollaborationHistoryChange.Redacted(first.eventId, first.sender))
        assertTrue(f.read().messages.isEmpty())
        assertNull(f.lookup())
        f.reopen()
        f.merge(first, first.copy(eventId = "\$late-retry"))
        val deleted = f.lookup()!!
        assertTrue(deleted.redacted)
        assertNull(deleted.text)
        assertEquals(deleted, f.lookup("\$late-retry"))
        f.merge(f.event(2), f.event(2).copy(eventId = "\$second-retry"))
        f.merge(CollaborationHistoryChange.Redacted("\$second-retry", first.sender))
        f.merge(f.event(2))
        assertTrue(f.lookup(f.event(2).eventId)!!.redacted)
        assertNull(f.lookup(f.event(2).eventId)!!.text)
        val before = f.raw()
        assertIs<AppResult.Failed>(
            f.repository.merge(
                f.access,
                f.conversation,
                f.room,
                listOf(CollaborationHistoryChange.Redacted(first.eventId, "@forged:example.test")),
            )
        )
        assertEquals(before, f.raw())
    }

    @Test
    fun historyPagesExceedOutboxCapacityWithoutFullConversationReads() = withDatabase { f ->
        for (batch in (1..137).map(f::event).chunked(16)) f.merge(*batch.toTypedArray())
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("No full conversation scan")

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    assertEquals(1, queries.size)
                    assertEquals(HISTORY_MESSAGES, queries.single().tag)
                    assertTrue(queries.single().limit <= 51)
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }
            }
        f.repository = f.repository(store)
        val received = mutableListOf<CollaborationHistoryMessage>()
        var cursor: String? = null
        do {
            val page = f.read(after = cursor, limit = 25)
            received += page.messages
            cursor = page.next
        } while (cursor != null)
        assertEquals((137 downTo 1).map { f.event(it).eventId }, received.map { it.primaryEventId })
        assertEquals(f.event(1).text, f.lookup()!!.text)
    }

    @Test
    fun competingWritersCannotLoseAnAliasAndReplayUsesTheOriginalIdentity() = withDatabase { f ->
        f.merge(f.event())
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
                    )
                }
            }
        val results = coroutineScope {
            listOf("\$retry-a", "\$retry-b")
                .map { event ->
                    async {
                        f.repository(store)
                            .merge(
                                f.access,
                                f.conversation,
                                f.room,
                                listOf(f.event().copy(eventId = event)),
                            )
                    }
                }
                .awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        f.merge(f.event().copy(eventId = "\$retry-a"), f.event().copy(eventId = "\$retry-b"))
        assertEquals(3, f.lookup()!!.eventIds.size)
        assertEquals(1, f.read().messages.size)
    }

    @Test
    fun lostCommitResponseAndCancellationRetainAtomicHistoryAndAliases() = withDatabase { f ->
        f.merge(f.event())
        for (cancel in listOf(false, true)) {
            val event = f.event(if (cancel) 3 else 2)
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                    ): Boolean {
                        assertTrue(
                            f.database.replicas.commit(
                                handle,
                                expectedRevision,
                                checkpoint,
                                documents,
                                publish,
                            )
                        )
                        if (cancel) throw CancellationException("Fixture cancellation")
                        error("Fixture response lost")
                    }
                }
            val operation = suspend {
                f.repository(store).merge(f.access, f.conversation, f.room, listOf(event))
            }
            if (cancel) assertFailsWith<CancellationException> { operation() }
            else assertIs<AppResult.Failed>(operation())
            f.reopen()
            assertEquals(event.text, f.lookup(event.eventId)!!.text)
            f.merge(event)
        }
        assertEquals(3, f.read().messages.size)
    }

    @Test
    fun accountChangeBeforeCommitCannotWriteToEitherAccount() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        val other = f.access.copy(sessionId = "other", accountId = historyId(8000))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                    )
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repository(store).merge(f.access, f.conversation, f.room, listOf(f.event(2))),
        )
        assertTrue(f.read(other).messages.isEmpty())
        f.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun missingKeyCannotRegenerateOverInactiveOwnedHistory() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repository.observe(f.access, f.conversation, f.room).first())
        val other = f.access.copy(accountId = historyId(8000), sessionId = "other")
        f.activate(other)
        assertIs<AppResult.Failed>(
            f.repository.merge(other, f.conversation, f.room, listOf(f.event(2)))
        )
        assertNull(f.keys.read())
        f.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun metadataAndCiphertextTamperingCannotPublishAnotherMessage() = withDatabase { f ->
        f.merge(f.event(), f.event(2))
        val raw = f.raw()
        val messages =
            raw.documents.filter { it.tags == setOf(HISTORY_MESSAGES) }.sortedBy { it.sortKey }
        val first = messages.first()
        val alterations =
            listOf(
                first.copy(sortKey = "invalid"),
                first.copy(tags = emptySet()),
                first.copy(version = 1),
                first.copy(payload = messages.last().payload),
                first.copy(id = messages.last().id),
            )
        for (altered in alterations) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) = flowOf(ReplicaQueryPage(raw.checkpoint, listOf(listOf(altered))))
                }
            assertIs<AppResult.Failed>(
                f.repository(store).observe(f.access, f.conversation, f.room).first()
            )
        }
        assertEquals(raw, f.raw())
    }

    @Test
    fun callerMutationAndCancelledPendingReadDoNotChangeTheCapturedBatch() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val changes = mutableListOf<CollaborationHistoryChange>(f.event())
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    entered.complete(Unit)
                    release.await()
                    return f.database.replicas.checkpoint(scope, partition)
                }
            }
        coroutineScope {
            val pending = async {
                f.repository(store).merge(f.access, f.conversation, f.room, changes)
            }
            entered.await()
            changes.clear()
            release.complete(Unit)
            assertEquals(AppResult.Success(true), pending.await())
        }
        assertEquals(f.event().text, f.lookup()!!.text)
        val pendingEntered = CompletableDeferred<Unit>()
        val blocked =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    pendingEntered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val pending = async {
                f.repository(blocked).merge(f.access, f.conversation, f.room, listOf(f.event(2)))
            }
            pendingEntered.await()
            pending.cancelAndJoin()
        }
        assertNull(f.lookup(f.event(2).eventId))
    }

    @Test
    fun aliasAndBatchBoundsPreserveEveryExistingRecord() = withDatabase { f ->
        for (batch in (1..128).map { f.event().copy(eventId = "\$alias-$it") }.chunked(16)) f.merge(
            *batch.toTypedArray()
        )
        val before = f.raw()
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, listOf(f.event()))
        )
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, (1..17).map(f::event))
        )
        assertEquals(before, f.raw())
        assertEquals(128, f.lookup("\$alias-1")!!.eventIds.size)
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
