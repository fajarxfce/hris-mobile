package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * The new namespace must preserve the established original/alias/replay behavior on real storage.
 */
abstract class CollaborationSpaceHistoryStorageContract {
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
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    arrived++
                    if (arrived == 2) ready.complete(Unit)
                    ready.await()
                    return f.database.replicas.mutate(scope, mutations, wake)
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
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
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
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.mutate(scope, mutations, wake)
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

    @Test
    fun companyAndSpaceWithIdenticalIdsKeepDifferentFactsAndIndexes() = withDatabase { f ->
        val company = f.event().copy(text = "Original Company history")
        val space = f.event().copy(text = "Original Space history", contentHash = "f".repeat(64))
        assertEquals(
            AppResult.Success(true),
            f.legacyRepository().merge(f.legacyAccess, f.conversation, f.room, listOf(company)),
        )
        f.merge(space)
        val old =
            f.database.replicas
                .observe(
                    historyScope(f.legacyAccess),
                    historyPartition(f.legacyAccess, f.conversation),
                    limit = 10,
                )
                .first()
        val current = f.raw()
        assertTrue(
            old.documents
                .map { it.id }
                .toSet()
                .intersect(current.documents.map { it.id }.toSet())
                .isEmpty()
        )
        assertNotEquals(old.checkpoint!!.handle.partition, current.checkpoint!!.handle.partition)
        val key = f.keys.read()
        f.reopen()
        val retained =
            assertIs<AppResult.Success<CollaborationHistoryPage>>(
                    f.legacyRepository().observe(f.legacyAccess, f.conversation, f.room).first()
                )
                .value
        assertEquals(company.text, retained.messages.single().text)
        assertEquals(space.text, f.read().messages.single().text)
        assertEquals(key, f.keys.read())
        assertEquals(
            old,
            f.database.replicas
                .observe(
                    historyScope(f.legacyAccess),
                    historyPartition(f.legacyAccess, f.conversation),
                    limit = 10,
                )
                .first(),
        )
        assertEquals(current, f.raw())
    }

    @Test
    fun copiedCiphertextCannotBecomeCompanyOrAnotherSpaceHistory() = withDatabase { f ->
        f.merge(f.event())
        val raw = f.raw()
        val checkpoint = requireNotNull(raw.checkpoint)
        val snapshot =
            ReplicaSnapshot(checkpoint.handle.generation, checkpoint.payload, raw.documents)
        assertTrue(
            f.database.replicas.replace(
                historyScope(f.legacyAccess),
                historyPartition(f.legacyAccess, f.conversation),
                null,
                snapshot,
            )
        )
        assertIs<AppResult.Failed>(
            f.legacyRepository().observe(f.legacyAccess, f.conversation, f.room).first()
        )
        val other = f.access.copy(spaceId = historyId(9500))
        assertTrue(
            f.database.replicas.replace(
                spaceHistoryScope(other),
                spaceHistoryPartition(other, f.conversation),
                null,
                snapshot,
            )
        )
        assertIs<AppResult.Failed>(f.repository.observe(other, f.conversation, f.room).first())
        assertEquals(f.event().text, f.read().messages.single().text)
        assertEquals(raw, f.raw())
    }

    @Test
    fun missingSharedKeyCannotBeReplacedOverLegacyCompanyHistory() = withDatabase { f ->
        assertEquals(
            AppResult.Success(true),
            f.legacyRepository().merge(f.legacyAccess, f.conversation, f.room, listOf(f.event())),
        )
        f.keys.write(null)
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, listOf(f.event(2)))
        )
        assertNull(f.keys.read())
        assertNull(f.raw().checkpoint)
    }

    @Test
    fun legacyByteFramesStayCompatibleAndSpaceHasItsOwnAuthenticatedProtocol() = withDatabase { f ->
        val captured = mutableListOf<String>()
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    captured += associatedData.decodeToString()
                    return f.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        val content = ByteArray(32) { (it + 32).toByte() }
        val index = ByteArray(32) { it.toByte() }
        try {
            val company =
                CollaborationHistoryCodec(
                    f.legacyAccess,
                    f.conversation,
                    f.room,
                    cipher,
                    content,
                    index,
                )
            val space =
                CollaborationHistoryCodec(f.access, f.conversation, f.room, cipher, content, index)
            assertEquals(
                "e-b5788f79e371328e3fffdc8caede4eb69a25e4069abab83cf1a4e2a6961a98c5",
                company.aliasId(f.event().eventId),
            )
            assertEquals(
                "e-19387bc71c4f61ded685f3439afbbbb3d57c5005faad8215dbbd0d633a154eb9",
                space.aliasId(f.event().eventId),
            )
            company.checkpoint(historyId(9800), 0, 1)
            space.checkpoint(historyId(9800), 0, 1)
            val rest =
                listOf(
                    "https://hris.example.test/",
                    historyId(9000),
                    historyId(9001),
                    historyId(9002),
                    "",
                    historyId(9800),
                    "checkpoint",
                    "0",
                    "",
                )
            assertEquals(
                listOf(
                    Json.encodeToString(listOf("hris-history-content-v1") + rest),
                    Json.encodeToString(listOf("hris-space-history-content-v1") + rest),
                ),
                captured,
            )
        } finally {
            content.fill(0)
            index.fill(0)
        }
    }

    private fun withDatabase(block: suspend (CollaborationSpaceHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationSpaceHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
