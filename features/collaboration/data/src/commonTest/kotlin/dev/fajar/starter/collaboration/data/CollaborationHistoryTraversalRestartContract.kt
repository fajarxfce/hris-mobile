package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

/** Explicit new provider origins retain unresolved work and already resolved reference identity. */
abstract class CollaborationHistoryTraversalRestartContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun latestAfterExhaustionPreservesResolvedAndUnseenReferencesAcrossReopen() =
        withDatabase { f ->
            f.stage(listOf(f.event(), f.event(2)), null)
            val initial = f.read()
            assertTrue(
                f.success(
                    f.repository()
                        .resolve(
                            f.access,
                            f.traversal,
                            requireNotNull(initial.progress),
                            initial.events.take(1),
                        )
                )
            )
            val before = f.read()
            val rows = f.raw().documents
            val previous = requireNotNull(before.progress)
            assertTrue(
                f.success(
                    f.repository()
                        .stage(
                            f.access,
                            f.traversal,
                            previous,
                            f.page(listOf(f.event(), f.event(3)), next = "fresh-position"),
                            CollaborationHistoryPageOrigin.Latest,
                        )
                )
            )
            f.local.reopen()
            assertEquals(
                previous.copy(
                    revision = previous.revision + 1,
                    references = 3,
                    next = "fresh-position",
                ),
                f.progress(),
            )
            assertEquals(listOf(f.event(2), f.event(3)), f.read().events.map { it.event })
            for (row in rows) assertEquals(row, f.raw().documents.single { it.id == row.id })
            f.stage(listOf(f.event(), f.event(2), f.event(3)), null)
            assertEquals(3, f.progress()!!.references)
            assertEquals(listOf(f.event(2), f.event(3)), f.read().events.map { it.event })
            assertEquals(listOf(1L, 2L), f.read().events.map { it.sequence })
        }

    @Test
    fun wrongOriginPositionConflictingMetadataAndStaleProgressCannotReset() = withDatabase { f ->
        f.stage(listOf(f.event()), "old-position")
        val progress = requireNotNull(f.progress())
        val before = f.raw()
        assertIs<AppResult.Failed>(
            f.repository().stage(f.access, f.traversal, progress, f.page(listOf(f.event(2))))
        )
        assertIs<AppResult.Failed>(
            f.repository()
                .stage(
                    f.access,
                    f.traversal,
                    progress,
                    f.page(emptyList(), progress.next),
                    CollaborationHistoryPageOrigin.Latest,
                )
        )
        assertIs<AppResult.Failed>(
            f.repository()
                .stage(
                    f.access,
                    f.traversal,
                    progress,
                    f.page(listOf(f.event(2), f.event().copy(timestamp = 9))),
                    CollaborationHistoryPageOrigin.Latest,
                )
        )
        assertEquals(
            AppResult.Success(false),
            f.repository()
                .stage(
                    f.access,
                    f.traversal,
                    progress.copy(revision = progress.revision + 1),
                    f.page(listOf(f.event(2))),
                    CollaborationHistoryPageOrigin.Latest,
                ),
        )
        assertEquals(before, f.raw())
    }

    @Test
    fun lostResponseAndCancellationRetainTheNewCursorWithoutClearingOldReferences() =
        withDatabase { f ->
            f.stage(listOf(f.event()), "old-position")
            for (cancel in listOf(false, true)) {
                val progress = requireNotNull(f.progress())
                val store =
                    object : ReplicaStore by f.local.database.replicas {
                        override suspend fun commit(
                            handle: ReplicaHandle,
                            expectedRevision: Long,
                            checkpoint: String,
                            documents: List<ReplicaDocument>,
                            publish: Boolean,
                            wake: ReplicaWakeRequest?,
                        ): Boolean {
                            assertTrue(
                                f.local.database.replicas.commit(
                                    handle,
                                    expectedRevision,
                                    checkpoint,
                                    documents,
                                    publish,
                                    wake,
                                )
                            )
                            if (cancel) throw CancellationException("Fixture lost response")
                            error("Fixture lost response")
                        }
                    }
                val next = if (cancel) null else "fresh-position"
                val operation = suspend {
                    f.repository(store)
                        .stage(
                            f.access,
                            f.traversal,
                            progress,
                            f.page(listOf(f.event(2)), next = next),
                            CollaborationHistoryPageOrigin.Latest,
                        )
                }
                if (cancel) assertFailsWith<CancellationException> { operation() }
                else assertIs<AppResult.Failed>(operation())
                f.local.reopen()
                assertEquals(
                    progress.copy(revision = progress.revision + 1, references = 2, next = next),
                    f.progress(),
                )
                assertEquals(listOf(f.event(), f.event(2)), f.read().events.map { it.event })
            }
        }

    @Test
    fun competingRestartAndResolutionCannotSplitTheCheckpointFromReferenceState() =
        withDatabase { f ->
            coroutineScope {
                f.stage(listOf(f.event(), f.event(2)))
                val page = f.read()
                val previous = requireNotNull(page.progress)
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
                val restarting = async {
                    f.repository(store)
                        .stage(
                            f.access,
                            f.traversal,
                            previous,
                            f.page(listOf(f.event(3)), next = "fresh"),
                            CollaborationHistoryPageOrigin.Latest,
                        )
                }
                val resolving = async {
                    f.repository(store)
                        .resolve(f.access, f.traversal, previous, page.events.take(1))
                }
                entered.await()
                release.complete(Unit)
                val restarted = f.success(restarting.await())
                assertNotEquals(restarted, f.success(resolving.await()))
                assertEquals(previous.revision + 1, f.progress()!!.revision)
                assertEquals(if (restarted) "fresh" else previous.next, f.progress()!!.next)
                assertEquals(
                    if (restarted) listOf(f.event(), f.event(2), f.event(3))
                    else listOf(f.event(2)),
                    f.read().events.map { it.event },
                )
            }
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
