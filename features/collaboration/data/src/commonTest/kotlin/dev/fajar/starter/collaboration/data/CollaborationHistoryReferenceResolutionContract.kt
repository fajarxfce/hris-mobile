package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Real SQLite/IndexedDB transactions retain identity while removing resolved pending indexes. */
abstract class CollaborationHistoryReferenceResolutionContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun resolutionSurvivesReopenAndOverlapWithoutResettingSequenceOrProviderProgress() =
        withDatabase { f ->
            for ((i, batch) in (1..45).chunked(20).withIndex()) f.stage(
                batch.map(f::event),
                "next-$i",
            )
            val before = f.raw()
            val page = f.read(limit = 20)
            val progress = requireNotNull(page.progress)
            assertTrue(
                f.success(f.repository().resolve(f.access, f.traversal, progress, page.events))
            )
            f.local.reopen()
            assertEquals(progress.copy(revision = progress.revision + 1), f.progress())
            val remaining = f.read(limit = 17)
            assertEquals((21..37).map(f::event), remaining.events.map { it.event })
            assertEquals(
                (38..45).map(f::event),
                f.read(after = remaining.after, limit = 17).events.map { it.event },
            )
            val resolved = f.raw().documents.filter { it.tags.isEmpty() }
            assertEquals(20, resolved.size)
            assertEquals(
                before.documents.take(20).map { it.id to it.sortKey }.toSet(),
                resolved.map { it.id to it.sortKey }.toSet(),
            )
            f.stage(listOf(f.event(1), f.event(20), f.event(46)), null)
            assertEquals(46, f.progress()!!.references)
            assertTrue(f.progress()!!.exhausted)
            assertEquals((21..46).map(f::event), f.read().events.map { it.event })
            assertEquals(20, f.raw().documents.count { it.tags.isEmpty() })
        }

    @Test
    fun malformedOrConflictingBatchesCannotPartiallyResolve() = withDatabase { f ->
        f.stage((1..21).map(f::event).take(20))
        f.stage(listOf(f.event(21)), "next-two")
        val page = f.read()
        val progress = requireNotNull(page.progress)
        val first = page.events.first()
        val before = f.raw()
        val invalid =
            listOf(
                emptyList(),
                page.events,
                listOf(first, first),
                listOf(first.copy(resolved = true)),
                listOf(first, page.events[1].copy(sequence = 9)),
                listOf(
                    first,
                    page.events[1].copy(event = f.event(2).copy(sender = "@forged:test")),
                ),
                listOf(first.copy(event = f.event(90))),
            )
        for (references in invalid) {
            assertIs<AppResult.Failed>(
                f.repository().resolve(f.access, f.traversal, progress, references)
            )
            assertEquals(before, f.raw())
        }
        assertEquals(
            AppResult.Success(false),
            f.repository()
                .resolve(
                    f.access,
                    f.traversal,
                    progress.copy(revision = progress.revision + 1),
                    listOf(first),
                ),
        )
        assertEquals(before, f.raw())
        assertTrue(
            f.success(f.repository().resolve(f.access, f.traversal, progress, listOf(first)))
        )
        val committed = f.raw()
        assertIs<AppResult.Failed>(
            f.repository()
                .resolve(f.access, f.traversal, requireNotNull(f.progress()), listOf(first))
        )
        assertEquals(committed, f.raw())
    }

    @Test
    fun competingPageAndResolutionCommitExactlyOneCoherentRevision() = withDatabase { f ->
        coroutineScope {
            f.stage(listOf(f.event()))
            val page = f.read()
            val progress = requireNotNull(page.progress)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val store =
                object : ReplicaStore by f.local.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        val raw = f.local.database.replicas.checkpoint(scope, partition)
                        if (++reads == 2) entered.complete(Unit)
                        release.await()
                        return raw
                    }
                }
            val resolving = async {
                f.repository(store).resolve(f.access, f.traversal, progress, page.events)
            }
            val staging = async {
                f.repository(store)
                    .stage(
                        f.access,
                        f.traversal,
                        progress,
                        f.page(listOf(f.event(2)), progress.next, "new-position"),
                    )
            }
            entered.await()
            release.complete(Unit)
            val resolved = f.success(resolving.await())
            val staged = f.success(staging.await())
            assertNotEquals(resolved, staged)
            assertEquals(progress.revision + 1, f.progress()!!.revision)
            assertEquals(
                if (resolved) emptyList() else listOf(f.event(), f.event(2)),
                f.read().events.map { it.event },
            )
            assertEquals(if (resolved) progress.next else "new-position", f.progress()!!.next)
        }
    }

    @Test
    fun lostCommitResponseAndCancellationKeepResolutionAtomic() = withDatabase { f ->
        f.stage(listOf(f.event(), f.event(2)))
        for (cancel in listOf(false, true)) {
            val page = f.read(limit = 1)
            val progress = requireNotNull(page.progress)
            val store =
                object : ReplicaStore by f.local.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                    ): Boolean {
                        assertTrue(
                            f.local.database.replicas.commit(
                                handle,
                                expectedRevision,
                                checkpoint,
                                documents,
                                publish,
                            )
                        )
                        if (cancel) throw CancellationException("Fixture lost response")
                        error("Fixture lost response")
                    }
                }
            if (cancel)
                assertFailsWith<CancellationException> {
                    f.repository(store).resolve(f.access, f.traversal, progress, page.events)
                }
            else
                assertIs<AppResult.Failed>(
                    f.repository(store).resolve(f.access, f.traversal, progress, page.events)
                )
            f.local.reopen()
            assertEquals(progress.copy(revision = progress.revision + 1), f.progress())
            assertEquals(if (cancel) 0 else 1, f.read().events.size)
            assertEquals(
                AppResult.Success(false),
                f.repository().resolve(f.access, f.traversal, progress, page.events),
            )
        }
        assertEquals(2, f.raw().documents.size)
    }

    @Test
    fun pendingCancellationAndCallerMutationCannotDropOrExtendTheCapturedBatch() =
        withDatabase { f ->
            coroutineScope {
                f.stage(listOf(f.event(), f.event(2)))
                for (cancel in listOf(true, false)) {
                    val page = f.read(limit = 1)
                    val source = page.events.toMutableList()
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val store =
                        object : ReplicaStore by f.local.database.replicas {
                            override suspend fun checkpoint(
                                scope: ReplicaScope,
                                partition: String,
                            ): ReplicaCheckpoint? {
                                entered.complete(Unit)
                                release.await()
                                return f.local.database.replicas.checkpoint(scope, partition)
                            }
                        }
                    val pending = async {
                        f.repository(store)
                            .resolve(f.access, f.traversal, requireNotNull(page.progress), source)
                    }
                    entered.await()
                    source.clear()
                    if (cancel) pending.cancelAndJoin()
                    else {
                        release.complete(Unit)
                        assertTrue(f.success(pending.await()))
                    }
                    assertEquals(if (cancel) 2 else 1, f.read().events.size)
                }
            }
        }

    @Test
    fun accountReplacementBeforeCommitKeepsBothOwnersUnchanged() = withDatabase { f ->
        f.stage(listOf(f.event()))
        val page = f.read()
        val before = f.raw()
        val other = f.access.copy(sessionId = "another-login", accountId = historyId(999))
        val store =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    f.local.activate(other)
                    return f.local.database.replicas.commit(
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
            f.repository(store)
                .resolve(f.access, f.traversal, requireNotNull(page.progress), page.events),
        )
        assertTrue(f.read(other).events.isEmpty())
        f.local.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun alteredResolvedIndexAndMissingKeysCannotPublishOrRewriteFacts() = withDatabase { f ->
        f.stage(listOf(f.event()))
        val page = f.read()
        assertTrue(
            f.success(
                f.repository()
                    .resolve(f.access, f.traversal, requireNotNull(page.progress), page.events)
            )
        )
        val before = f.raw()
        val store =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun document(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): ReplicaDocument? =
                    f.local.database.replicas
                        .document(scope, partition, id)
                        ?.copy(tags = setOf(HISTORY_REFERENCES))
            }
        val progress = requireNotNull(f.progress())
        assertIs<AppResult.Failed>(
            f.repository(store)
                .stage(
                    f.access,
                    f.traversal,
                    progress,
                    f.page(listOf(f.event()), progress.next, "new-position"),
                )
        )
        f.local.keys.write(null)
        assertIs<AppResult.Failed>(
            f.repository().resolve(f.access, f.traversal, progress, page.events)
        )
        assertNull(f.local.keys.read())
        assertEquals(before, f.raw())
    }

    private fun withDatabase(block: suspend (CollaborationHistoryTraversalFixture) -> Unit) =
        runTest {
            val fixture = CollaborationHistoryTraversalFixture(databaseOwner())
            try {
                fixture.local.activate()
                block(fixture)
            } finally {
                fixture.local.close()
            }
        }
}
