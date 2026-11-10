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
    fun replayedOriginalsAndRedactionsKeepTheStoredRevisionAndCiphertext() = withDatabase { f ->
        val original = f.event()
        val alias = original.copy(eventId = "\$replayed-alias", timestamp = original.timestamp + 1)
        val redaction = CollaborationHistoryChange.Redacted(alias.eventId, alias.sender)
        val tombstone = CollaborationHistoryChange.Redacted("\$unseen", original.sender)
        f.merge(original, alias, f.event(2))
        for (redacted in listOf(false, true)) {
            if (redacted) f.merge(redaction, tombstone)
            val stored = f.raw()
            val page = f.read()
            val key = f.keys.read()
            f.reopen()
            f.merge(original, alias, original, f.event(2))
            if (redacted) f.merge(redaction, tombstone, redaction)
            assertEquals(stored, f.raw())
            assertEquals(page, f.read())
            assertEquals(key, f.keys.read())
        }
    }

    @Test
    fun aBatchWithNewMessagesDoesNotRewriteItsReplayedOriginals() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val before = f.raw()
        f.merge(original, f.event(2), original)
        val after = f.raw()
        assertEquals(before.checkpoint!!.revision + 1, after.checkpoint!!.revision)
        for (retained in before.documents) assertEquals(
            retained,
            after.documents.single { it.id == retained.id },
        )
        assertEquals(2, f.read().messages.size)
    }

    @Test
    fun aReplayDoesNotSucceedThroughOwnerReplacementOrCancelledCheckpointReads() =
        withDatabase { f ->
            val original = f.event()
            f.merge(original)
            val before = f.raw()
            for (cancel in listOf(false, true)) {
                var reads = 0
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun checkpoint(
                            scope: ReplicaScope,
                            partition: String,
                        ): ReplicaCheckpoint? {
                            if (++reads == 2) {
                                if (cancel) throw CancellationException("Fixture cancellation")
                                f.activate(f.access.copy(sessionId = "replacement-login"))
                            }
                            return f.database.replicas.checkpoint(scope, partition)
                        }

                        override suspend fun commit(
                            handle: ReplicaHandle,
                            expectedRevision: Long,
                            checkpoint: String,
                            documents: List<ReplicaDocument>,
                            publish: Boolean,
                            wake: ReplicaWakeRequest?,
                        ): Boolean = error("Replayed history must not commit")
                    }
                val replay = suspend {
                    f.repository(store).merge(f.access, f.conversation, f.room, listOf(original))
                }
                if (cancel) assertFailsWith<CancellationException> { replay() }
                else assertEquals(AppResult.Success(false), replay())
                f.activate()
                assertEquals(before, f.raw())
            }
        }

    @Test
    fun forwardedHistorySurvivesReopenAliasReplayAndRetainedRedaction() = withDatabase { f ->
        val event = f.event().copy(forwarded = true)
        val alias = event.copy(eventId = "\$forward-alias", timestamp = event.timestamp + 1)
        f.merge(event, alias)
        val original = assertNotNull(f.lookup(event.eventId))
        assertTrue(original.forwarded)
        val key = f.keys.read()
        f.reopen()
        assertEquals(original, f.lookup(alias.eventId))
        f.merge(alias)
        assertEquals(original, f.lookup(event.eventId))
        f.merge(CollaborationHistoryChange.Redacted(alias.eventId, alias.sender))
        f.reopen()
        val redacted = assertNotNull(f.lookup(event.eventId))
        assertTrue(redacted.redacted)
        assertTrue(redacted.forwarded)
        assertNull(redacted.text)
        assertEquals(key, f.keys.read())
    }

    @Test
    fun indexedQuotesObserveOffscreenDeletionAcrossAliasesAndRestart() = withDatabase { f ->
        for ((index, redact) in listOf(1 to false, 3 to true)) {
            val target = f.event(index)
            val alias =
                target.copy(eventId = "\$quote-alias-$index", timestamp = target.timestamp + 10)
            val reply = f.event(index + 1).copy(replyToEventId = alias.eventId)
            f.merge(target, alias, reply)
            assertEquals(
                listOf(reply.eventId),
                f.read(limit = 1).messages.map { it.primaryEventId },
            )
            val ids = listOf(target.eventId, alias.eventId)
            val key = f.keys.read()
            val values = mutableListOf<List<CollaborationQuotedMessage>>()
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val collection = launch {
                    f.repository
                        .observeQuotes(f.access, f.conversation, f.room, ids)
                        .take(2)
                        .collect {
                            values +=
                                assertIs<AppResult.Success<List<CollaborationQuotedMessage>>>(it)
                                    .value
                            ready.complete(Unit)
                        }
                }
                ready.await()
                assertEquals(ids, values.single().map { it.eventId })
                assertTrue(
                    values.single().all { it.text == target.text && it.sender == target.sender }
                )
                if (redact)
                    f.merge(CollaborationHistoryChange.Redacted(alias.eventId, alias.sender))
                else
                    assertEquals(
                        AppResult.Success(true),
                        f.repository.hide(
                            f.access,
                            f.conversation,
                            f.room,
                            listOf(
                                CollaborationMessageReference(
                                    target.sender,
                                    target.eventId,
                                    target.logicalId,
                                )
                            ),
                        ),
                    )
                collection.join()
            }
            assertTrue(values.last().isEmpty())
            f.reopen()
            assertEquals(reply.replyToEventId, f.lookup(reply.eventId)?.replyToEventId)
            assertEquals(
                AppResult.Success(emptyList()),
                f.repository.observeQuotes(f.access, f.conversation, f.room, ids).first(),
            )
            assertEquals(key, f.keys.read())
        }
    }

    @Test
    fun absentQuotesNeverCreateKeysAndWrongRoomOrSubstitutedAliasesCannotPublishText() =
        withDatabase { f ->
            val target = f.event()
            assertEquals(
                AppResult.Success(emptyList()),
                f.repository
                    .observeQuotes(f.access, f.conversation, f.room, listOf(target.eventId))
                    .first(),
            )
            assertNull(f.keys.read())
            f.merge(target, f.event(2))
            assertIs<AppResult.Failed>(
                f.repository
                    .observeQuotes(
                        f.access,
                        f.conversation,
                        "!foreign:example.test",
                        listOf(target.eventId),
                    )
                    .first()
            )
            val key = f.keys.read()
            val aliases = f.raw().documents.filter { it.id.startsWith("e-") }
            val substitute =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun document(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): ReplicaDocument? =
                        if (id.startsWith("e-")) aliases.first { it.id != id }
                        else f.database.replicas.document(scope, partition, id)
                }
            assertIs<AppResult.Failed>(
                f.repository(substitute)
                    .observeQuotes(f.access, f.conversation, f.room, listOf(target.eventId))
                    .first()
            )
            val replaced =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? = null
                }
            assertIs<AppResult.Failed>(
                f.repository(replaced)
                    .observeQuotes(f.access, f.conversation, f.room, listOf(target.eventId))
                    .first()
            )
            assertEquals(key, f.keys.read())
        }

    @Test
    fun eventFactsKeepAliasTimestampsAndTombstonesAcrossReopen() = withDatabase { f ->
        val first = f.event()
        val retry = first.copy(eventId = "\$retry-fact", timestamp = first.timestamp + 86_400_001)
        assertEquals(
            AppResult.Success(null),
            f.repository.getEventFact(f.access, f.conversation, f.room, first.eventId),
        )
        assertNull(f.keys.read())
        f.merge(first, retry)
        f.reopen()
        for (event in listOf(first, retry)) assertEquals(
            AppResult.Success(
                CollaborationHistoryEventFact(event.eventId, event.sender, event.timestamp, false)
            ),
            f.repository.getEventFact(f.access, f.conversation, f.room, event.eventId),
        )
        assertEquals(first.timestamp, f.lookup(retry.eventId)!!.timestamp)
        f.merge(CollaborationHistoryChange.Redacted(retry.eventId, retry.sender))
        assertEquals(
            AppResult.Success(
                CollaborationHistoryEventFact(first.eventId, first.sender, first.timestamp, true)
            ),
            f.repository.getEventFact(f.access, f.conversation, f.room, first.eventId),
        )
        val absent = f.event(3)
        f.merge(CollaborationHistoryChange.Redacted(absent.eventId, absent.sender))
        f.reopen()
        assertEquals(
            AppResult.Success(
                CollaborationHistoryEventFact(absent.eventId, absent.sender, null, true)
            ),
            f.repository.getEventFact(f.access, f.conversation, f.room, absent.eventId),
        )
        assertNull(f.lookup(absent.eventId))
    }

    @Test
    fun eventFactLookupRejectsWrongScopeSubstitutedAliasesAndLateCheckpointChanges() =
        withDatabase { f ->
            f.merge(f.event(), f.event(2))
            assertIs<AppResult.Failed>(
                f.repository.getEventFact(
                    f.access,
                    f.conversation,
                    "!another:test",
                    f.event().eventId,
                )
            )
            val aliases = f.raw().documents.filter { it.id.startsWith("e-") }
            for (kind in 0..2) {
                var reads = 0
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun document(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): ReplicaDocument? {
                            if (kind == 0 && id.startsWith("e-"))
                                return aliases.first { it.id != id }
                            return f.database.replicas.document(scope, partition, id)?.let {
                                if (kind == 1 && id.startsWith("e-")) it.copy(payload = "corrupt")
                                else it
                            }
                        }

                        override suspend fun checkpoint(
                            scope: ReplicaScope,
                            partition: String,
                        ): ReplicaCheckpoint? {
                            if (kind == 2 && ++reads == 2) f.merge(f.event(3))
                            return f.database.replicas.checkpoint(scope, partition)
                        }
                    }
                assertIs<AppResult.Failed>(
                    f.repository(store)
                        .getEventFact(f.access, f.conversation, f.room, f.event().eventId)
                )
            }
        }

    @Test
    fun eventFactLookupDoesNotPublishThroughLogoutOrCancelledStorage() = withDatabase { f ->
        f.merge(f.event())
        val entered = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun document(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): ReplicaDocument? {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val pending = async {
                f.repository(store)
                    .getEventFact(f.access, f.conversation, f.room, f.event().eventId)
            }
            entered.await()
            pending.cancelAndJoin()
        }
        var reads = 0
        val changed =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    if (++reads == 2)
                        f.activate(
                            f.access.copy(sessionId = "replaced", accountId = historyId(888))
                        )
                    return f.database.replicas.checkpoint(scope, partition)
                }
            }
        assertIs<AppResult.Failed>(
            f.repository(changed).getEventFact(f.access, f.conversation, f.room, f.event().eventId)
        )
        f.activate()
        assertNotNull(f.lookup())
    }

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
        val page =
            assertIs<AppResult.Success<CollaborationHistoryPage>>(
                    f.repository.observe(f.access, f.conversation).first()
                )
                .value
        assertNotNull(page.revision)
        assertEquals(
            CollaborationHistoryPage(listOf(expected), null, roomId = f.room),
            page.copy(revision = null),
        )
        for (raw in f.raw().documents) {
            assertFalse(raw.toString().contains("fictional"))
            assertFalse(raw.toString().contains(first.eventId))
            assertFalse(raw.toString().contains(first.logicalId!!))
        }
        val renewed = f.access.copy(sessionId = "renewed-login")
        f.reopen(renewed)
        assertEquals(listOf(expected), f.read(renewed).messages)
        assertEquals(f.room, f.read(renewed).roomId)
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
    fun overlappingWindowsTraverseBeyondFiveHundredInBothDirectionsAcrossRestart() =
        withDatabase { f ->
            for (batch in (1..603).map(f::event).chunked(16)) f.merge(*batch.toTypedArray())
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        assertEquals(1, queries.size)
                        assertEquals(151, queries.single().limit)
                        assertEquals(HISTORY_MESSAGES, queries.single().tag)
                        return f.database.replicas.observeQueries(scope, partition, queries)
                    }

                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> = error("No partition scan")
                }
            f.repository = f.repository(store)
            var page = f.read(limit = 150, overlap = 50)
            assertNull(page.previous)
            val visited = page.messages.map { it.primaryEventId }.toMutableSet()
            var moves = 0
            while (page.next != null) {
                val old = page
                page = f.read(after = page.next, limit = 150, overlap = 50)
                assertEquals(50, old.messages.intersect(page.messages.toSet()).size)
                assertTrue(page.messages.size <= 150)
                assertTrue(page.messages.zipWithNext().all { (a, b) -> a.timestamp > b.timestamp })
                visited += page.messages.map { it.primaryEventId }
                moves++
            }
            assertEquals((1..603).map { f.event(it).eventId }.toSet(), visited)
            assertEquals(5, moves)
            val oldest = page
            f.reopen()
            val oldAnchor = requireNotNull(oldest.previous)
            f.merge(f.event(604))
            page = f.read(before = oldAnchor, limit = 150, overlap = 50)
            assertEquals(50, oldest.messages.intersect(page.messages.toSet()).size)
            assertFalse(page.messages.any { it.primaryEventId == f.event(604).eventId })
            val returning = page.messages.map { it.primaryEventId }.toMutableSet()
            while (page.previous != null) {
                val old = page
                page = f.read(before = page.previous, limit = 150, overlap = 50)
                assertEquals(50, old.messages.intersect(page.messages.toSet()).size)
                returning += page.messages.map { it.primaryEventId }
            }
            assertEquals(f.event(604).eventId, page.messages.first().primaryEventId)
            assertEquals((54..604).map { f.event(it).eventId }.toSet(), returning)
            // A redaction changes the original in place without changing the retained reverse
            // anchor.
            f.merge(CollaborationHistoryChange.Redacted(f.event(104).eventId, f.event(104).sender))
            val revised = f.read(before = oldAnchor, limit = 150, overlap = 50)
            assertTrue(
                revised.messages.single { it.primaryEventId == f.event(104).eventId }.redacted
            )
            assertEquals(page.messages.first(), f.read(limit = 150, overlap = 50).messages.first())
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
            for (before in listOf(null, "9999999999999999/m-" + "f".repeat(64))) {
                assertIs<AppResult.Failed>(
                    f.repository(store)
                        .observe(f.access, f.conversation, f.room, before = before)
                        .first()
                )
            }
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
