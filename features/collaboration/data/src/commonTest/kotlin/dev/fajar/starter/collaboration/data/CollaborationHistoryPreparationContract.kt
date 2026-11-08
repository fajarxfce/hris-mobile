package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Interleaved real SQLite/IndexedDB publications while an encrypted mutation is being prepared. */
abstract class CollaborationHistoryPreparationContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun aConcurrentAliasIsRetainedWhenTheOriginalChangesBeforeItsRead() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val alias = original.copy(eventId = "\$concurrent-alias")
        val store = beforeRead(f) { if (it == 1) f.merge(alias) }
        assertEquals(AppResult.Success(true), merge(f, store, original))
        assertEquals(setOf(original.eventId, alias.eventId), f.lookup()!!.eventIds.toSet())
        f.reopen()
        assertEquals(1, f.read().messages.size)
        assertEquals(2, f.lookup()!!.eventIds.size)
    }

    @Test
    fun concurrentFirstPublicationReusesTheOriginalKeyAndGeneration() = withDatabase { f ->
        var retained: ReplicaPage? = null
        var key: String? = null
        val store =
            beforeRead(f) {
                if (it == 1) {
                    f.merge(f.event())
                    retained = f.raw()
                    key = f.keys.read()
                }
            }
        assertEquals(AppResult.Success(true), merge(f, store, f.event()))
        assertEquals(retained, f.raw())
        assertEquals(key, f.keys.read())
    }

    @Test
    fun aConcurrentHideIsAppliedBeforePublishingTheOriginal() = withDatabase { f ->
        f.merge(f.event())
        val original = f.event(2)
        val store =
            beforeRead(f) {
                if (it == 4) {
                    assertEquals(
                        AppResult.Success(true),
                        f.repository.hide(
                            f.access,
                            f.conversation,
                            f.room,
                            listOf(
                                CollaborationMessageReference(
                                    original.sender,
                                    original.eventId,
                                    original.logicalId,
                                )
                            ),
                        ),
                    )
                }
            }
        assertEquals(AppResult.Success(true), merge(f, store, original))
        assertTrue(f.lookup(original.eventId)!!.locallyHidden)
        assertEquals(listOf(f.event().eventId), f.read().messages.map { it.primaryEventId })
    }

    @Test
    fun editPreparationRetainsAnAliasPublishedDuringItsReads() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val replacement = edit(original, 1)
        val store =
            beforeRead(f) { if (it == 1) f.merge(original.copy(eventId = "\$concurrent-alias")) }
        assertEquals(
            AppResult.Success(true),
            f.repository(store).mergeEdits(f.access, f.conversation, f.room, listOf(replacement)),
        )
        val retained = f.lookup()!!
        assertEquals(2, retained.eventIds.size)
        assertEquals(replacement, retained.latestEdit?.edit)
    }

    @Test
    fun aRevisionIndexChangingDuringRedactionIsReadAgainLocally() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val first = edit(original, 1)
        val last = edit(original, 2)
        assertEquals(
            AppResult.Success(true),
            f.repository.mergeEdits(f.access, f.conversation, f.room, listOf(first, last)),
        )
        var reads = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    if (++reads == 1) f.merge(f.event(2))
                    emitAll(f.database.replicas.observeQueries(scope, partition, queries))
                }
            }
        assertEquals(
            AppResult.Success(true),
            merge(f, store, CollaborationHistoryChange.Redacted(last.eventId, last.sender)),
        )
        assertEquals(first, f.lookup()!!.latestEdit?.edit)
        assertEquals(2, f.read().messages.size)
        assertEquals(2, reads)
    }

    @Test
    fun sustainedContentionReturnsAConflictAfterThreeLocalPreparations() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        var reads = 0
        val store =
            beforeRead(f) {
                reads++
                f.merge(original.copy(eventId = "\$concurrent-$it"))
            }
        assertEquals(AppResult.Success(false), merge(f, store, original))
        assertEquals(3, reads)
        assertEquals(4, f.lookup()!!.eventIds.size)
    }

    @Test
    fun ownerReplacementCannotPublishIntoEitherAccount() = withDatabase { f ->
        f.merge(f.event())
        val retained = f.raw()
        val key = f.keys.read()
        val store =
            beforeRead(f) {
                if (it == 1)
                    f.activate(f.access.copy(sessionId = "replacement", accountId = historyId(888)))
            }
        val result = merge(f, store, f.event(2))
        assertNotEquals(AppResult.Success(true), result)
        f.activate()
        assertEquals(retained, f.raw())
        assertEquals(key, f.keys.read())
    }

    @Test
    fun cancellationDuringPreparationDoesNotPublishOrReplaceKeys() = withDatabase { f ->
        f.merge(f.event())
        val retained = f.raw()
        val key = f.keys.read()
        val entered = CompletableDeferred<Unit>()
        val store =
            beforeRead(f) {
                entered.complete(Unit)
                awaitCancellation()
            }
        coroutineScope {
            val pending = async { merge(f, store, f.event(2)) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
        assertEquals(retained, f.raw())
        assertEquals(key, f.keys.read())
    }

    @Test
    fun unchangedCheckpointCorruptionRemainsAStorageFailure() = withDatabase { f ->
        f.merge(f.event())
        val retained = f.raw()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun document(scope: ReplicaScope, partition: String, id: String) =
                    f.database.replicas
                        .document(scope, partition, id)
                        ?.copy(payload = "invalid ciphertext")

                override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
                    f.database.replicas.observeDocument(scope, partition, id).map { page ->
                        page.copy(
                            documents =
                                page.documents.map { it.copy(payload = "invalid ciphertext") }
                        )
                    }
            }
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(merge(f, store, f.event())).failure.kind,
        )
        assertEquals(retained, f.raw())
    }

    @Test
    fun changedOriginalContentIsNotTreatedAsConcurrentPublication() = withDatabase { f ->
        f.merge(f.event())
        val retained = f.raw()
        val result =
            merge(
                f,
                f.database.replicas,
                f.event().copy(text = "Substituted original", contentHash = "f".repeat(64)),
            )
        assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
        assertEquals(retained, f.raw())
    }

    private fun beforeRead(
        f: CollaborationHistoryFixture,
        before: suspend (Int) -> Unit,
    ): ReplicaStore {
        var reads = 0
        return object : ReplicaStore by f.database.replicas {
            override suspend fun document(
                scope: ReplicaScope,
                partition: String,
                id: String,
            ): ReplicaDocument? {
                before(++reads)
                return f.database.replicas.document(scope, partition, id)
            }

            override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
                flow {
                    before(++reads)
                    emitAll(f.database.replicas.observeDocument(scope, partition, id))
                }
        }
    }

    private suspend fun merge(
        f: CollaborationHistoryFixture,
        store: ReplicaStore,
        change: CollaborationHistoryChange,
    ) = f.repository(store).merge(f.access, f.conversation, f.room, listOf(change))

    private fun edit(original: CollaborationHistoryChange.Text, index: Int) =
        CollaborationMessageEdit(
            eventId = "\$edit-$index",
            sender = original.sender,
            targetEventId = original.eventId,
            timestamp = original.timestamp + index,
            text = "Edited fixture $index",
            contentHash = "b".repeat(64),
            operationId = historyId(100 + index),
            originalLogicalId = original.logicalId,
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
