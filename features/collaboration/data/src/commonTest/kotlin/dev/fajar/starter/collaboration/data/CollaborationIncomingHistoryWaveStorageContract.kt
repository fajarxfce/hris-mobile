package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.boundedCollaborationIncomingReferences
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

abstract class CollaborationIncomingHistoryWaveStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun completedBoundsAdvanceWhilePartialWavesAndExplicitRestartsRetainTheirOldStop() =
        withDatabase { f ->
            f.commit(1..20, "head-one", "next-one")
            f.local.reopen()
            f.commit(21..25, "next-one", null)
            assertEquals(
                CollaborationIncomingHistoryPosition("head-one", null),
                f.progress()!!.incoming,
            )
            f.commit(
                26..30,
                "head-two",
                "next-two",
                "head-one",
                CollaborationHistoryPageOrigin.Latest,
            )
            f.local.reopen()
            assertEquals(
                CollaborationIncomingHistoryPosition("head-two", "head-one"),
                f.progress()!!.incoming,
            )
            // Explicit recovery cannot promote the head of a wave that has not reached its stop.
            f.commit(
                31..32,
                "head-three",
                "next-three",
                "head-one",
                CollaborationHistoryPageOrigin.Latest,
            )
            f.commit(33..34, "next-three", null, "head-one")
            f.commit(35..36, "head-four", null, "head-three", CollaborationHistoryPageOrigin.Latest)
            f.local.reopen()
            assertEquals(
                CollaborationIncomingHistoryPosition("head-four", "head-three"),
                f.progress()!!.incoming,
            )
            assertEquals(36, f.progress()!!.references)
            assertTrue(f.progress()!!.exhausted)
            assertEquals(36, f.read().events.size)
        }

    @Test
    fun wrongWaveBoundsCannotCommitEvenAnEmptyPage() = withDatabase { f ->
        f.commit(1..2, "head-one", null)
        val previous = f.progress()!!
        val before = f.raw()
        for (until in listOf(null, "foreign-stop", "bad\nstop")) {
            val page = f.page(emptyList(), next = null).copy(requestedUntilPosition = until)
            assertIs<AppResult.Failed>(
                f.repository()
                    .stage(
                        f.access,
                        f.traversal,
                        previous,
                        page,
                        CollaborationHistoryPageOrigin.Latest,
                    )
            )
            assertEquals(before, f.raw())
        }
        val browse = f.traversal.copy(purpose = CollaborationHistoryPurpose.Browse)
        assertIs<AppResult.Failed>(
            f.repository()
                .stage(
                    f.access,
                    browse,
                    null,
                    f.page(emptyList()).copy(requestedUntilPosition = "head-one"),
                )
        )
        assertNull(f.success(f.repository().progress(f.access, browse)))
    }

    @Test
    fun missingReferencesCannotStarveLaterOnesAndConcurrentAppendsCannotExtendThePendingPass() =
        withDatabase { f ->
            f.commit(1..20, "head", "one")
            f.commit(21..40, "one", "two")
            f.commit(41..45, "two", "more")
            val first = f.pending()
            // Original acquisition resolved one item after the pending page was captured.
            assertTrue(
                f.success(
                    f.repository()
                        .resolve(f.access, f.traversal, first.progress!!, first.events.take(1))
                )
            )
            assertTrue(f.advance(first, retry = true))
            assertEquals(45, f.progress()!!.incoming!!.pendingThrough)
            assertTrue(f.progress()!!.incoming!!.pendingRetryRequired)
            f.local.reopen()
            f.commit(46..65, "more", "more-again")
            val second = f.pending()
            assertEquals((21..40).map(f::event), second.events.map { it.event })
            assertTrue(f.advance(second))
            f.local.reopen()
            val last = f.pending()
            assertEquals((41..45).map(f::event), last.events.map { it.event })
            assertNull(last.after)
            assertTrue(last.progress!!.incoming!!.pendingRetryRequired)
            assertTrue(f.advance(last))
            val after = f.progress()!!
            assertEquals(CollaborationIncomingHistoryPosition("head", null), after.incoming)
            assertEquals("more-again", after.next)
            assertEquals(65, after.references)
            // The unavailable original is still pending when the next finite pass wraps.
            assertEquals(f.event(2), f.pending().events.first().event)
            assertTrue(f.read().events.none { it.resolved })
        }

    @Test
    fun stalePassesAndForgedEndpointsCannotMoveTheSeekOrAcknowledgeContent() = withDatabase { f ->
        f.commit(1..20, "head", "one")
        f.commit(21..40, "one", "two")
        f.commit(41..45, "two", null)
        val first = f.pending()
        val next =
            f.success(
                f.repository().observeReferences(f.access, f.traversal, first.after, 20).first()
            )
        val before = f.raw()
        assertIs<AppResult.Failed>(
            f.repository()
                .advanceIncomingReferences(
                    f.access,
                    f.traversal,
                    first.copy(after = next.after),
                    false,
                )
        )
        assertIs<AppResult.Failed>(
            f.repository()
                .advanceIncomingReferences(
                    f.access,
                    f.traversal,
                    first.copy(
                        events =
                            first.events.mapIndexed { i, r ->
                                if (i == 0) r.copy(event = r.event.copy(timestamp = 1)) else r
                            }
                    ),
                    false,
                )
        )
        assertEquals(before, f.raw())
        assertTrue(f.advance(first))
        val moved = f.raw()
        assertFalse(f.advance(first))
        assertEquals(moved, f.raw())
        val current = f.pending()
        f.commit(
            46..47,
            "replacement-head",
            "new-next",
            "head",
            CollaborationHistoryPageOrigin.Latest,
        )
        assertFalse(f.advance(current))
        assertEquals(47, f.read().events.size)
    }

    @Test
    fun cancellationAfterCommitRetainsTheSeekAndEveryUnresolvedReferenceAcrossReopen() =
        withDatabase { f ->
            f.commit(1..20, "head", "one")
            f.commit(21..25, "one", null)
            val page = f.pending()
            val store =
                object : ReplicaStore by f.local.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        check(
                            f.local.database.replicas.commit(
                                handle,
                                expectedRevision,
                                checkpoint,
                                documents,
                                publish,
                                wake,
                            )
                        )
                        throw CancellationException(
                            "Fixture lost response after pending seek commit"
                        )
                    }
                }
            assertFailsWith<CancellationException> {
                f.repository(store).advanceIncomingReferences(f.access, f.traversal, page, true)
            }
            f.local.reopen()
            assertEquals(page.after, f.progress()!!.incoming!!.pendingAfter)
            assertEquals(25, f.progress()!!.incoming!!.pendingThrough)
            assertTrue(f.progress()!!.incoming!!.pendingRetryRequired)
            assertEquals((21..25).map(f::event), f.pending().events.map { it.event })
            assertEquals(25, f.read().events.size)
        }

    @Test
    fun accountReplacementDuringPendingCommitCannotWriteEitherAccount() = withDatabase { f ->
        f.commit(1..20, "head", "one")
        f.commit(21..25, "one", null)
        val page = f.pending()
        val before = f.raw()
        val other = f.access.copy(sessionId = "replacement", accountId = historyId(999))
        val store =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.local.activate(other)
                    return f.local.database.replicas.commit(
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
            f.repository(store).advanceIncomingReferences(f.access, f.traversal, page, false),
        )
        assertNull(f.success(f.repository().progress(other, f.traversal)))
        f.local.activate()
        assertEquals(before, f.raw())
    }

    @Test
    fun legacyIncomingSeekResumesWithoutInventingACompletedWaveHead() = withDatabase { f ->
        val generation = historyId(9400)
        val context =
            listOf(
                "hris-incoming-history-pages-v1",
                f.access.audience,
                f.access.accountId,
                f.access.companyId,
                f.traversal.conversationId,
                f.traversal.roomId,
                f.traversal.deviceId,
                generation,
                "checkpoint",
                "0",
                "",
            )
        val payload =
            """{"roomId":"${f.traversal.roomId}","next":"legacy-next","references":0,"schema":1}"""
        val encrypted =
            CollaborationHistoryKeys(f.local.database.replicas, f.local.cipher, f.local.keys).use(
                create = true
            ) { key, _ ->
                Base64.encode(
                    f.local.cipher.encrypt(
                        key,
                        Json.encodeToString(context).encodeToByteArray(),
                        payload.encodeToByteArray(),
                    )
                )
            }
        assertTrue(
            f.local.database.replicas.replace(
                historyScope(f.access),
                traversalPartition(f.access, f.traversal),
                null,
                ReplicaSnapshot(generation, encrypted, emptyList()),
            )
        )
        f.local.reopen()
        assertNull(f.progress()!!.incoming)
        f.commit(1..2, "legacy-next", null)
        assertEquals(
            CollaborationIncomingHistoryPosition("legacy-next", null),
            f.progress()!!.incoming,
        )
        f.commit(3..4, "new-head", null, "legacy-next", CollaborationHistoryPageOrigin.Latest)
        assertEquals(4, f.read().events.size)
    }

    @Test
    fun worstCaseEscapedProviderBoundsStayWithinTheIncomingCheckpointBudget() = withDatabase { f ->
        val head = "\"".repeat(4095) + "a"
        val second = "\\".repeat(4095) + "b"
        val next = "\"".repeat(4095) + "c"
        f.commit(1..0, head, null)
        f.commit(1..0, second, next, head, CollaborationHistoryPageOrigin.Latest)
        f.local.reopen()
        assertEquals(next, f.progress()!!.next)
        assertEquals(CollaborationIncomingHistoryPosition(second, head), f.progress()!!.incoming)
        assertTrue(f.raw().checkpoint!!.payload.length <= 32_768)
    }

    private suspend fun CollaborationHistoryTraversalFixture.commit(
        ids: IntRange,
        start: String,
        next: String?,
        until: String? = null,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
    ) {
        val previous = progress()
        val page =
            page(
                    ids.map(::event),
                    if (origin == CollaborationHistoryPageOrigin.Latest) null else previous?.next,
                    next,
                )
                .copy(start = start, requestedUntilPosition = until)
        assertTrue(success(repository().stage(access, traversal, previous, page, origin)))
    }

    private suspend fun CollaborationHistoryTraversalFixture.pending():
        CollaborationHistoryReferences {
        val after = progress()!!.incoming!!.pendingAfter
        return assertNotNull(
            boundedCollaborationIncomingReferences(
                success(repository().observeReferences(access, traversal, after, 20).first())
            )
        )
    }

    private suspend fun CollaborationHistoryTraversalFixture.advance(
        page: CollaborationHistoryReferences,
        retry: Boolean = false,
    ) = success(repository().advanceIncomingReferences(access, traversal, page, retry))

    private fun withDatabase(block: suspend (CollaborationHistoryTraversalFixture) -> Unit) =
        runTest {
            val fixture =
                CollaborationHistoryTraversalFixture(
                    databaseOwner(),
                    CollaborationHistoryPurpose.Incoming,
                )
            try {
                fixture.local.activate()
                block(fixture)
            } finally {
                fixture.local.close()
            }
        }
}
