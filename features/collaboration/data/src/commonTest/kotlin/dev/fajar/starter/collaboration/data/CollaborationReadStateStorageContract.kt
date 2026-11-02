package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.data.readstate.readStatePartition
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationReadStateRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Identical persistence, race and cryptographic checks on real SQLite and IndexedDB. */
abstract class CollaborationReadStateStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun exactCoverageAndPendingIntentSurviveReopenWithoutPlaintextOrAgeLimit() = withDatabase { f ->
        assertEquals(CollaborationReadState(null, emptySet(), emptyList(), null), f.readState())
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(true), f.mark(null, listOf(event(1), event(2)), intent(2)))
        val before = f.readState(event(1), event(2), event(3))
        assertEquals(setOf(event(1), event(2)), before.readEventIds)
        assertEquals(listOf(intent(2)), before.pending)
        f.reopen(f.access.copy(sessionId = "renewed-login"))
        val renewed = f.access.copy(sessionId = "renewed-login")
        assertEquals(
            before,
            expect(
                f.readRepository()
                    .read(renewed, f.conversation, f.room, listOf(event(1), event(2), event(3)))
            ),
        )
        val raw =
            f.database.replicas
                .observe(
                    historyScope(renewed),
                    readStatePartition(renewed, f.conversation),
                    limit = 200,
                )
                .first()
        val encoded = raw.toString()
        for (secret in listOf(event(1), event(2), intent(2).id, f.room)) assertFalse(
            encoded.contains(secret)
        )
        assertEquals(2, raw.documents.size)
    }

    @Test
    fun staleMarkAndLateAcknowledgementCannotDiscardNewerReadIntentOrCoverage() =
        withDatabase { f ->
            expect(f.mark(null, listOf(event(1)), intent(1)))
            val first = f.readState(event(1))
            expect(f.mark(first.version, listOf(event(2)), intent(2)))
            assertEquals(
                AppResult.Success(false),
                f.mark(first.version, listOf(event(3)), intent(3)),
            )
            assertEquals(listOf(intent(1), intent(2)), f.readState().pending)
            assertEquals(AppResult.Success(true), f.ack(intent(1)))
            val second = f.readState(event(1), event(2), event(3))
            assertEquals(setOf(event(1), event(2)), second.readEventIds)
            assertEquals(listOf(intent(2)), second.pending)
            assertEquals(intent(1), second.acknowledged)
            assertEquals(AppResult.Success(true), f.ack(intent(2)))
            f.reopen()
            val after = f.readState(event(1), event(2))
            assertTrue(after.pending.isEmpty())
            assertEquals(intent(2), after.acknowledged)
            assertEquals(second.readEventIds, after.readEventIds)
        }

    @Test
    fun competingFirstWritersShareTheHistoryKeyAndRequireExplicitObservedRetry() =
        withDatabase { f ->
            val both = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                    ): Boolean {
                        if (++writes == 2) both.complete(Unit)
                        release.await()
                        return f.database.replicas.replace(scope, partition, expected, snapshot)
                    }
                }
            coroutineScope {
                val a = async {
                    f.readRepository(store)
                        .mark(f.access, f.conversation, f.room, null, listOf(event(1)), intent(1))
                }
                val b = async {
                    f.readRepository(store)
                        .mark(f.access, f.conversation, f.room, null, listOf(event(2)), intent(2))
                }
                both.await()
                release.complete(Unit)
                assertEquals(
                    listOf(false, true),
                    listOf(expect(a.await()), expect(b.await())).sorted(),
                )
            }
            val state = f.readState(event(1), event(2))
            assertEquals(1, state.readEventIds.size)
            val missing = if (event(1) in state.readEventIds) 2 else 1
            expect(f.mark(state.version, listOf(event(missing)), intent(missing)))
            f.reopen()
            assertEquals(setOf(event(1), event(2)), f.readState(event(1), event(2)).readEventIds)
            f.merge(f.event())
            assertNotNull(f.lookup())
        }

    @Test
    fun lostMarkAndAcknowledgementResponsesRetainTheirAtomicFactsAcrossRestart() =
        withDatabase { f ->
            val lostMark =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                    ): Boolean {
                        assertTrue(
                            f.database.replicas.replace(scope, partition, expected, snapshot)
                        )
                        error("Injected lost response")
                    }
                }
            assertIs<AppResult.Failed>(
                f.readRepository(lostMark)
                    .mark(f.access, f.conversation, f.room, null, listOf(event(1)), intent(1))
            )
            f.reopen()
            assertEquals(listOf(intent(1)), f.readState(event(1)).pending)
            val lostAck =
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
                        error("Injected lost response")
                    }
                }
            assertIs<AppResult.Failed>(
                f.readRepository(lostAck).acknowledge(f.access, f.conversation, f.room, intent(1))
            )
            f.reopen()
            val state = f.readState(event(1))
            assertEquals(setOf(event(1)), state.readEventIds)
            assertTrue(state.pending.isEmpty())
            assertEquals(intent(1), state.acknowledged)
        }

    @Test
    fun missingHistoryKeyNeverRegeneratesOverInactiveReadRecords() = withDatabase { f ->
        expect(f.mark(null, listOf(event(1)), intent(1)))
        val retained = requireNotNull(f.keys.read())
        f.keys.write(null)
        val other = f.access.copy(accountId = historyId(9900))
        f.activate(other)
        assertIs<AppResult.Failed>(
            f.readRepository()
                .mark(other, f.conversation, f.room, null, listOf(event(2)), intent(2))
        )
        assertNull(f.keys.read())
        f.keys.write(retained)
        f.activate()
        assertEquals(listOf(intent(1)), f.readState(event(1)).pending)
        f.merge(f.event())
        assertNotNull(f.lookup())
    }

    @Test
    fun changedAccountBeforeCommitCannotWriteIntoEitherOwnersReadState() = withDatabase { f ->
        expect(f.mark(null, listOf(event(1)), intent(1)))
        val before = f.readState(event(1), event(2))
        val other = f.access.copy(accountId = historyId(9901))
        val changed =
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
        assertNotEquals(
            AppResult.Success(true),
            f.readRepository(changed)
                .mark(f.access, f.conversation, f.room, before.version, listOf(event(2)), intent(2)),
        )
        assertEquals(
            CollaborationReadState(null, emptySet(), emptyList(), null),
            expect(
                f.readRepository().read(other, f.conversation, f.room, listOf(event(1), event(2)))
            ),
        )
        f.activate()
        assertEquals(before, f.readState(event(1), event(2)))
    }

    @Test
    fun substitutedMetadataCiphertextRoomAndLateCheckpointCannotPublishReadCoverage() =
        withDatabase { f ->
            expect(f.mark(null, listOf(event(1), event(2)), intent(2)))
            assertIs<AppResult.Failed>(
                f.readRepository().read(f.access, f.conversation, "!foreign", listOf(event(1)))
            )
            for (fault in 0..3) {
                val bad =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun document(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): ReplicaDocument? {
                            val raw =
                                f.database.replicas.document(scope, partition, id) ?: return null
                            return when (fault) {
                                0 -> raw.copy(payload = "corrupt")
                                1 -> raw.copy(tags = setOf("other"))
                                2 -> raw.copy(sortKey = "substituted")
                                else -> {
                                    f.ack(intent(2))
                                    raw
                                }
                            }
                        }
                    }
                assertIs<AppResult.Failed>(
                    f.readRepository(bad).read(f.access, f.conversation, f.room, listOf(event(1)))
                )
            }
            assertEquals(setOf(event(1), event(2)), f.readState(event(1), event(2)).readEventIds)
        }

    @Test
    fun boundsAndCallerMutationPreserveTheOriginalCapturedReadIntent() = withDatabase { f ->
        val repository = f.readRepository()
        for (events in
            listOf(emptyList(), listOf("bad"), listOf(event(1), event(1)), (1..129).map(::event))) {
            assertIs<AppResult.Failed>(
                repository.mark(f.access, f.conversation, f.room, null, events, intent(1))
            )
        }
        assertIs<AppResult.Failed>(repository.read(f.access, f.conversation, "", emptyList()))
        assertNull(f.keys.read())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf(event(1))
        val delayed =
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
            val write = async {
                f.readRepository(delayed)
                    .mark(f.access, f.conversation, f.room, null, values, intent(1))
            }
            entered.await()
            values[0] = event(2)
            release.complete(Unit)
            assertTrue(expect(write.await()))
        }
        assertEquals(setOf(event(1)), f.readState(event(1), event(2)).readEventIds)
    }

    @Test
    fun cancellationBeforeCommitKeepsEarlierFactsAndIntent() = withDatabase { f ->
        expect(f.mark(null, listOf(event(1)), intent(1)))
        val before = f.readState(event(1), event(2))
        val entered = CompletableDeferred<Unit>()
        val pending =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val writer = launch {
                f.readRepository(pending)
                    .mark(
                        f.access,
                        f.conversation,
                        f.room,
                        before.version,
                        listOf(event(2)),
                        intent(2),
                    )
            }
            entered.await()
            writer.cancelAndJoin()
        }
        assertEquals(before, f.readState(event(1), event(2)))
    }

    @Test
    fun queueRetainsDistinctReadPointsAndRejectsCapacityWithoutLosingAnyCoverage() =
        withDatabase { f ->
            var version: CollaborationReadStateRevision? = null
            for (index in 1..128) {
                assertTrue(expect(f.mark(version, listOf(event(index)), intent(index))))
                version = f.readState().version
            }
            f.reopen()
            val before = f.readState(event(128), event(129))
            assertEquals((1..128).map(::intent), before.pending)
            assertIs<AppResult.Failed>(f.mark(before.version, listOf(event(129)), intent(129)))
            assertEquals(before, f.readState(event(128), event(129)))
            assertTrue(expect(f.ack(intent(1))))
            assertTrue(expect(f.mark(f.readState().version, listOf(event(129)), intent(129))))
            val after = f.readState(event(1), event(128), event(129))
            assertEquals((2..129).map(::intent), after.pending)
            assertEquals(setOf(event(1), event(128), event(129)), after.readEventIds)
        }

    @Test
    fun replayDoesNotDuplicateQueuedIntentOrRebindAnObservedId() = withDatabase { f ->
        assertTrue(expect(f.mark(null, listOf(event(1)), intent(1))))
        assertTrue(expect(f.mark(f.readState().version, listOf(event(1)), intent(1))))
        val before = f.readState(event(1), event(2))
        assertEquals(listOf(intent(1)), before.pending)
        assertIs<AppResult.Failed>(
            f.mark(before.version, listOf(event(2)), intent(1).copy(eventId = event(2)))
        )
        assertEquals(before, f.readState(event(1), event(2)))
        assertEquals(
            AppResult.Success(false),
            f.ack(intent(1).copy(visibility = CollaborationReadReceiptVisibility.Public)),
        )
        assertTrue(expect(f.ack(intent(1))))
        assertTrue(expect(f.mark(f.readState().version, listOf(event(1)), intent(1))))
        assertTrue(f.readState().pending.isEmpty())
        assertEquals(intent(1), f.readState().acknowledged)
    }

    private fun CollaborationHistoryFixture.readRepository(
        store: ReplicaStore = database.replicas
    ) = EncryptedCollaborationReadStateRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.readState(vararg events: String) =
        expect(readRepository().read(access, conversation, room, events.toList()))

    private suspend fun CollaborationHistoryFixture.mark(
        expected: CollaborationReadStateRevision?,
        events: List<String>,
        value: CollaborationReadReceiptIntent,
    ) = readRepository().mark(access, conversation, room, expected, events, value)

    private suspend fun CollaborationHistoryFixture.ack(value: CollaborationReadReceiptIntent) =
        readRepository().acknowledge(access, conversation, room, value)

    private fun event(index: Int) = "\$read-event-$index"

    private fun intent(index: Int) =
        CollaborationReadReceiptIntent(
            historyId(index),
            event(index),
            if (index % 2 == 0) CollaborationReadReceiptVisibility.Public
            else CollaborationReadReceiptVisibility.Private,
        )

    private fun <T> expect(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
