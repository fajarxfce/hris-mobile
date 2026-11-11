package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

abstract class CollaborationHistoryRecoveryStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun oldCheckpointsGainAnIndependentEncryptedPositionAndReopen() = withDatabases { f ->
        f.stage()
        val before = f.raw()
        val expected = f.progress()!!
        val value =
            f.recovery()
                .copy(
                    after = f.read(limit = 1).after,
                    unresolved = true,
                    requestedGeneration = historyId(9801),
                )
        assertEquals(AppResult.Success(true), f.replace(expected, value))
        val saved = f.raw()
        assertEquals(before.documents, saved.documents)
        assertNotEquals(before.checkpoint!!.payload, saved.checkpoint!!.payload)
        assertFalse(saved.checkpoint!!.payload.contains(value.generation))
        f.local.reopen()
        assertEquals(
            expected.copy(revision = expected.revision + 1, recovery = value),
            f.progress(),
        )
        assertEquals(3, f.read().events.size)
    }

    @Test
    fun providerAppendsPreserveTheFiniteRecoveryBoundary() = withDatabases { f ->
        f.stage()
        val value = f.recovery().copy(after = f.read(limit = 1).after)
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, value))
        f.stage(listOf(f.event(4)), "next-2")
        val current = f.progress()!!
        assertEquals(value, current.recovery)
        assertEquals(4, current.references)
        assertEquals("next-2", current.next)
    }

    @Test
    fun latestProviderWavePreservesRecoveryAndPreviousWaveBoundary() = withDatabases { f ->
        f.stage(next = null)
        val value = f.recovery().copy(complete = true, unresolved = true)
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, value))
        f.stage(listOf(f.event(4)), "new-wave", CollaborationHistoryPageOrigin.Latest)
        assertEquals(value, f.progress()!!.recovery)
        assertEquals("head", f.progress()!!.incoming!!.until)
        assertEquals(4, f.read().events.size)
    }

    @Test
    fun concurrentPrimaryProgressIsMergedWithoutRewritingReferences() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        f.stage(listOf(f.event(4)), "next-2")
        assertEquals(AppResult.Success(true), f.advance(f.read(limit = 1)))
        val primary = f.progress()!!
        val documents = f.raw().documents
        assertEquals(AppResult.Success(true), f.replace(expected, f.recovery()))
        assertEquals(
            primary.copy(revision = primary.revision + 1, recovery = f.recovery()),
            f.progress(),
        )
        assertEquals(documents, f.raw().documents)
    }

    @Test
    fun primaryPendingSeekAndResolutionPreserveRecovery() = withDatabases { f ->
        f.stage()
        val value = f.recovery().copy(requestedGeneration = historyId(9801))
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, value))
        assertEquals(AppResult.Success(true), f.advance(f.read(limit = 1)))
        val primary = f.progress()!!.incoming
        assertNotNull(primary!!.pendingAfter)
        assertEquals(AppResult.Success(true), f.resolve(f.read(limit = 1)))
        assertEquals(value, f.progress()!!.recovery)
        assertEquals(primary, f.progress()!!.incoming)
        assertEquals(2, f.read().events.size)
    }

    @Test
    fun exactReplayAfterPrimaryAppendDoesNotWriteAgain() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        assertEquals(AppResult.Success(true), f.replace(expected, f.recovery()))
        f.stage(listOf(f.event(4)), "later")
        val before = f.raw()
        assertEquals(AppResult.Success(true), f.replace(expected, f.recovery()))
        assertEquals(before, f.raw())
    }

    @Test
    fun competingRecoveryUpdateCannotLoseALaterRequest() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val expected = f.progress()!!
        val requested = f.recovery().copy(requestedGeneration = historyId(9801))
        assertEquals(AppResult.Success(true), f.replace(expected, requested))
        val before = f.raw()
        assertEquals(
            AppResult.Success(false),
            f.replace(expected, f.recovery().copy(complete = true)),
        )
        assertEquals(before, f.raw())
        assertEquals(requested, f.progress()!!.recovery)
    }

    @Test
    fun databaseCommitConflictPreservesTheWinningPrimaryWrite() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        var calls = 0
        val racing =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    calls++
                    f.stage(listOf(f.event(4)), "winner")
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
        assertEquals(AppResult.Success(false), f.replace(expected, f.recovery(), racing))
        assertEquals(1, calls)
        assertNull(f.progress()!!.recovery)
        assertEquals("winner", f.progress()!!.next)
        assertEquals(4, f.read().events.size)
    }

    @Test
    fun cancellationBeforeCommitKeepsTheOriginalCheckpoint() = withDatabases { f ->
        f.stage()
        val before = f.raw()
        val entered = CompletableDeferred<Unit>()
        val waiting =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val pending = async { f.replace(f.progress()!!, f.recovery(), waiting) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
        assertEquals(before, f.raw())
    }

    @Test
    fun lostCommitReplyIsAFailureUntilExactLocalConfirmation() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        val lost =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
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
                    throw IllegalStateException("simulated lost storage reply")
                }
            }
        assertIs<AppResult.Failed>(f.replace(expected, f.recovery(), lost))
        val committed = f.raw()
        assertEquals(f.recovery(), f.progress()!!.recovery)
        assertEquals(AppResult.Success(true), f.replace(expected, f.recovery()))
        assertEquals(committed, f.raw())
    }

    @Test
    fun absentHistoryDoesNotAcquireKeysOrCreateAProviderPosition() = withDatabases { f ->
        var acquisitions = 0
        val keys =
            object : AtomicCredentialStore by f.local.keys {
                override suspend fun read(): String? {
                    acquisitions++
                    error("unexpected key read")
                }

                override suspend fun writeIfAbsent(value: String): String {
                    acquisitions++
                    error("unexpected key allocation")
                }
            }
        val expected =
            CollaborationHistoryProgress(
                historyId(9802),
                0,
                null,
                0,
                CollaborationIncomingHistoryPosition("real-head", null),
                if (f.space) f.provider else null,
            )
        assertEquals(
            AppResult.Success(false),
            f.replace(expected, f.recovery(0).copy(complete = true), credentials = keys),
        )
        assertEquals(0, acquisitions)
        assertNull(f.raw().checkpoint)
        assertNull(f.local.keys.read())
    }

    @Test
    fun referenceGenerationAndProviderCannotBeSubstituted() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        val before = f.raw()
        val foreign =
            listOf(
                expected.copy(generation = historyId(9802)),
                expected.copy(revision = expected.revision + 1),
                expected.copy(references = 4),
                expected.copy(
                    provider =
                        if (f.space) f.provider.copy(deviceId = historyId(9899)) else f.provider
                ),
            )
        for (value in foreign) assertEquals(
            AppResult.Success(false),
            f.replace(value, f.recovery()),
        )
        assertEquals(before, f.raw())
    }

    @Test
    fun boundaryCannotCaptureReferencesBeyondTheCallersSnapshot() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        f.stage(listOf(f.event(4)), "later")
        val before = f.raw()
        assertIs<AppResult.Failed>(f.replace(expected, f.recovery(4)))
        assertEquals(before, f.raw())
    }

    @Test
    fun reauthenticationRetainsAccountOwnedRecoveryWhileOldSessionCannotWrite() =
        withDatabases { f ->
            f.stage()
            assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
            val expected = f.progress()!!
            f.local.reopen(f.access.copy(sessionId = "renewed"))
            val stale = f.replace(expected, f.recovery().copy(complete = true))
            assertEquals(AppResult.Success(false), stale)
            f.sessionId = "renewed"
            assertEquals(expected, f.progress())
            assertEquals(
                AppResult.Success(true),
                f.replace(expected, f.recovery().copy(complete = true)),
            )
        }

    @Test
    fun anotherDeviceConversationRoomOrNamespaceCannotReplaceThisHistory() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        val before = f.raw()
        for (key in
            listOf(
                f.traversal.copy(deviceId = historyId(9898)),
                f.traversal.copy(conversationId = historyId(9898)),
                f.traversal.copy(roomId = "!other:example.test"),
                f.traversal.copy(purpose = CollaborationHistoryPurpose.Browse),
            )) {
            val result =
                if (f.space) f.repository().replace(f.spaceAccess, key, expected, f.recovery())
                else f.repository().replace(f.access, key, expected, f.recovery())
            if (key.roomId != f.traversal.roomId || key.purpose != f.traversal.purpose)
                assertIs<AppResult.Failed>(result)
            else assertEquals(AppResult.Success(false), result)
        }
        val other =
            if (f.space) f.repository().replace(f.access, f.traversal, expected, f.recovery())
            else f.repository().replace(f.spaceAccess, f.traversal, expected, f.recovery())
        assertEquals(AppResult.Success(false), other)
        assertEquals(before, f.raw())
    }

    @Test
    fun foreignAccountAudienceAndCompanyOrSpaceCannotReplaceOwnedHistory() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        val before = f.raw()
        val results =
            if (f.space)
                listOf(
                        f.spaceAccess.copy(accountId = historyId(9897)),
                        f.spaceAccess.copy(audience = "https://other.example.test/"),
                        f.spaceAccess.copy(spaceId = historyId(9897)),
                    )
                    .map { f.repository().replace(it, f.traversal, expected, f.recovery()) }
            else
                listOf(
                        f.access.copy(accountId = historyId(9897)),
                        f.access.copy(audience = "https://other.example.test/"),
                        f.access.copy(companyId = historyId(9897)),
                    )
                    .map { f.repository().replace(it, f.traversal, expected, f.recovery()) }
        for (result in results) assertEquals(AppResult.Success(false), result)
        assertEquals(before, f.raw())
    }

    @Test
    fun cancellationAfterCommitRetainsEvidenceForAReopenedConfirmation() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        val cancelled =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
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
                    throw CancellationException("reply cancelled after storage committed")
                }
            }
        assertFailsWith<CancellationException> { f.replace(expected, f.recovery(), cancelled) }
        f.local.reopen()
        val saved = f.raw()
        assertEquals(f.recovery(), f.progress()!!.recovery)
        assertEquals(AppResult.Success(true), f.replace(expected, f.recovery()))
        assertEquals(saved, f.raw())
    }

    @Test
    fun replayMustReadBackTheStillOwnedCheckpoint() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        assertEquals(AppResult.Success(true), f.replace(expected, f.recovery()))
        var reads = 0
        val switched =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    if (partition == f.coordinates.traversalPartition(f.traversal)) {
                        reads++
                        if (reads == 2) f.local.activate(f.access.copy(sessionId = "renewed"))
                    }
                    return f.local.database.replicas.checkpoint(scope, partition)
                }
            }
        assertEquals(AppResult.Success(false), f.replace(expected, f.recovery(), switched))
        assertEquals(2, reads)
        f.sessionId = "renewed"
        assertEquals(f.recovery(), f.progress()!!.recovery)
    }

    @Test
    fun emptyPublishedHistoryCanRetainACompletedPassBeforeLaterAppends() = withDatabases { f ->
        f.stage(emptyList())
        val completed = f.recovery(0).copy(complete = true)
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, completed))
        f.stage(listOf(f.event()), "later")
        assertEquals(completed, f.progress()!!.recovery)
        assertEquals(1, f.read().events.size)
    }

    @Test
    fun recoveryCursorBeyondItsFiniteBoundaryCannotReachStorage() = withDatabases { f ->
        f.stage()
        val expected = f.progress()!!
        val before = f.raw()
        for (cursor in listOf("opaque", "0000000000000003/r-" + "a".repeat(64))) {
            assertIs<AppResult.Failed>(f.replace(expected, f.recovery().copy(after = cursor)))
        }
        assertEquals(before, f.raw())
    }

    @Test
    fun recoveryAdvancesAnExactIndexedPageWithoutChangingPrimaryPositions() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val before = f.progress()!!
        val rows = f.raw().documents
        assertEquals(AppResult.Success(true), f.advanceRecovery(page, limit = 1))
        assertEquals(
            before.copy(
                revision = before.revision + 1,
                recovery = f.recovery().copy(after = page.after, unresolved = true),
            ),
            f.progress(),
        )
        assertEquals(rows, f.raw().documents)
    }

    @Test
    fun partialPageCannotClaimAnEmptyCursorWhilePendingRowsRemain() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val before = f.raw()
        assertIs<AppResult.Failed>(f.advanceRecovery(page.copy(after = null), limit = 1))
        assertEquals(before, f.raw())
    }

    @Test
    fun emptyFabricatedPageCannotCompleteAnUnvisitedPendingInterval() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val before = f.raw()
        assertIs<AppResult.Failed>(
            f.advanceRecovery(CollaborationHistoryReferences(f.progress(), emptyList(), null))
        )
        assertEquals(before, f.raw())
    }

    @Test
    fun omittedMiddleReferenceCannotBeCrossedByALaterCursor() = withDatabases { f ->
        f.stage((1..4).map(f::event))
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery(4)))
        val full = f.recoveryPage(3)
        val forged = full.copy(events = listOf(full.events[0], full.events[2]))
        val before = f.raw()
        assertIs<AppResult.Failed>(f.advanceRecovery(forged, limit = 2))
        assertEquals(before, f.raw())
    }

    @Test
    fun lookaheadDetectsAnUnattemptedSeventeenthReferenceAtClaimedEof() = withDatabases { f ->
        f.stage((1..20).map(f::event))
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery(20)))
        val page = f.recoveryPage()
        assertEquals(16, page.events.size)
        val before = f.raw()
        assertIs<AppResult.Failed>(f.advanceRecovery(page.copy(after = null)))
        assertEquals(before, f.raw())
    }

    @Test
    fun concurrentResolutionMayRemoveAttemptedRowsFromThePendingIndex() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(2)
        assertEquals(AppResult.Success(true), f.resolve(f.read(limit = 1)))
        assertEquals(AppResult.Success(true), f.advanceRecovery(page, unresolved = 1, limit = 2))
        assertEquals(page.after, f.progress()!!.recovery!!.after)
        assertEquals(listOf(f.event(3)), f.recoveryPage().events.map { it.event })
    }

    @Test
    fun emptyRealPendingProjectionCanFinishAfterConcurrentResolution() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        assertEquals(AppResult.Success(true), f.resolve(f.read()))
        val page = f.recoveryPage()
        assertTrue(page.events.isEmpty())
        assertEquals(AppResult.Success(true), f.advanceRecovery(page))
        assertEquals(f.recovery().copy(complete = true), f.progress()!!.recovery)
    }

    @Test
    fun recoveryPageCannotSubstituteReferenceMetadata() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val row = page.events.single()
        val changed = row.copy(event = row.event.copy(timestamp = row.event.timestamp + 1))
        val before = f.raw()
        assertIs<AppResult.Failed>(
            f.advanceRecovery(page.copy(events = listOf(changed)), limit = 1)
        )
        assertEquals(before, f.raw())
    }

    @Test
    fun newerRequestIsPreservedWhenAnOlderCapturedPageAdvances() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        assertEquals(
            AppResult.Success(true),
            f.replace(f.progress()!!, f.recovery().copy(requestedGeneration = historyId(9801))),
        )
        assertEquals(AppResult.Success(true), f.advanceRecovery(page, limit = 1))
        assertEquals(
            f.recovery()
                .copy(after = page.after, unresolved = true, requestedGeneration = historyId(9801)),
            f.progress()!!.recovery,
        )
    }

    @Test
    fun finalPagePromotesLatestRequestAndCapturesLaterPrimaryAppends() = withDatabases { f ->
        f.stage()
        assertEquals(
            AppResult.Success(true),
            f.replace(f.progress()!!, f.recovery().copy(requestedGeneration = historyId(9801))),
        )
        val page = f.recoveryPage()
        f.stage(listOf(f.event(4)), "later")
        assertEquals(
            AppResult.Success(true),
            f.replace(f.progress()!!, f.recovery().copy(requestedGeneration = historyId(9802))),
        )
        assertEquals(AppResult.Success(true), f.advanceRecovery(page))
        assertEquals(
            CollaborationHistoryRecoveryPosition(historyId(9802), 4),
            f.progress()!!.recovery,
        )
        assertEquals(4, f.recoveryPage().events.size)
    }

    @Test
    fun newPrimaryReferencesDoNotExtendTheCurrentFiniteAttempt() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        f.stage(listOf(f.event(4), f.event(5)), "later")
        val page = f.recoveryPage(3)
        assertEquals(3, page.events.size)
        assertNull(page.after)
        assertEquals(AppResult.Success(true), f.advanceRecovery(page, limit = 3))
        assertEquals(f.recovery().copy(complete = true, unresolved = true), f.progress()!!.recovery)
        assertEquals(5, f.read().events.size)
    }

    @Test
    fun aChangedQueryCheckpointCannotBeAcceptedAsCoverage() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val racing =
            object : ReplicaStore by f.local.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    f.stage(listOf(f.event(4)), "query-race")
                    emitAll(f.local.database.replicas.observeQueries(scope, partition, queries))
                }
            }
        assertEquals(AppResult.Success(false), f.advanceRecovery(page, limit = 1, store = racing))
        assertEquals(f.recovery(), f.progress()!!.recovery)
        assertEquals(4, f.progress()!!.references)
    }

    @Test
    fun aPrimaryCommitAfterCoverageCheckWinsWithoutRecoveryOverwrite() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val racing =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.stage(listOf(f.event(4)), "commit-race")
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
        assertEquals(AppResult.Success(false), f.advanceRecovery(page, limit = 1, store = racing))
        assertEquals(f.recovery(), f.progress()!!.recovery)
        assertEquals("commit-race", f.progress()!!.next)
    }

    @Test
    fun cancelledPageCommitKeepsItsCursorAndPendingRows() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val before = f.raw()
        val entered = CompletableDeferred<Unit>()
        val waiting =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = async { f.advanceRecovery(page, limit = 1, store = waiting) }
            entered.await()
            job.cancelAndJoin()
        }
        assertEquals(before, f.raw())
    }

    @Test
    fun lostReplyKeepsAnAdvancedCursorAcrossReopenWithoutReplayingTheOldPage() =
        withDatabases { f ->
            f.stage()
            assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
            val page = f.recoveryPage(1)
            val lost =
                object : ReplicaStore by f.local.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: WorkWakeRequest?,
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
                        error("reply lost after page advancement")
                    }
                }
            assertIs<AppResult.Failed>(f.advanceRecovery(page, limit = 1, store = lost))
            f.local.reopen()
            val before = f.raw()
            assertEquals(AppResult.Success(false), f.advanceRecovery(page, limit = 1))
            assertEquals(before, f.raw())
            assertEquals(listOf(f.event(2), f.event(3)), f.recoveryPage().events.map { it.event })
        }

    @Test
    fun completionCommitsAllReferenceFlagsAndRecoveryInOneRevision() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        val before = f.progress()!!
        val rows = f.raw().documents
        assertEquals(AppResult.Success(true), f.completeRecovery(page))
        val current = f.progress()!!
        assertEquals(before.revision + 1, current.revision)
        assertEquals(before.references, current.references)
        assertEquals(before.incoming, current.incoming)
        assertEquals(before.next, current.next)
        assertEquals(before.provider, current.provider)
        assertEquals(f.recovery().copy(complete = true), current.recovery)
        assertTrue(f.read().events.isEmpty())
        for (reference in page.events) assertEquals(
            reference.copy(resolved = true),
            f.source(reference.event.eventId).reference,
        )
        assertEquals(rows.map { it.id }.toSet(), f.raw().documents.map { it.id }.toSet())
        assertFalse(f.raw().checkpoint!!.payload.contains(f.recovery().generation))
    }

    @Test
    fun partialCompletionLeavesUnresolvedSourcesBehindItsFiniteCursor() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(2)
        assertEquals(
            AppResult.Success(true),
            f.completeRecovery(page, listOf(page.events.first()), 2),
        )
        assertEquals(
            f.recovery().copy(after = page.after, unresolved = true),
            f.progress()!!.recovery,
        )
        assertEquals(listOf(f.event(2), f.event(3)), f.read().events.map { it.event })
        assertEquals(listOf(f.event(3)), f.recoveryPage().events.map { it.event })
        assertTrue(f.source(f.event(1).eventId).reference!!.resolved)
        assertFalse(f.source(f.event(2).eventId).reference!!.resolved)
    }

    @Test
    fun completionWithNoResolvedOutcomesAdvancesWithoutRewritingSources() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        val rows = f.raw().documents
        assertEquals(AppResult.Success(true), f.completeRecovery(page, emptyList()))
        assertEquals(rows, f.raw().documents)
        assertEquals(f.recovery().copy(complete = true, unresolved = true), f.progress()!!.recovery)
        assertEquals(3, f.read().events.size)
    }

    @Test
    fun completionPreservesIndependentPrimaryAppendsAndPendingSeek() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        f.stage(listOf(f.event(4)), "new-primary")
        assertEquals(AppResult.Success(true), f.advance(f.read(limit = 1)))
        val primary = f.progress()!!
        assertEquals(AppResult.Success(true), f.completeRecovery(page))
        val current = f.progress()!!
        assertEquals(primary.incoming, current.incoming)
        assertEquals(primary.next, current.next)
        assertEquals(primary.references, current.references)
        assertEquals(primary.provider, current.provider)
        assertEquals(listOf(f.event(4)), f.read().events.map { it.event })
        assertEquals(f.recovery().copy(complete = true), current.recovery)
    }

    @Test
    fun completionPromotesTheLatestRequestWithItsNewReferenceBoundary() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        f.stage(listOf(f.event(4)), "new-primary")
        assertEquals(
            AppResult.Success(true),
            f.replace(f.progress()!!, f.recovery().copy(requestedGeneration = historyId(9810))),
        )
        assertEquals(AppResult.Success(true), f.completeRecovery(page))
        assertEquals(
            CollaborationHistoryRecoveryPosition(historyId(9810), 4),
            f.progress()!!.recovery,
        )
        assertEquals(listOf(f.event(4)), f.recoveryPage().events.map { it.event })
        assertTrue(f.source(f.event(1).eventId).reference!!.resolved)
    }

    @Test
    fun completionAcceptsAnIdenticalSourceResolvedByConcurrentPrimaryWork() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        assertEquals(
            AppResult.Success(true),
            f.resolve(page.copy(events = listOf(page.events.first()))),
        )
        val current = f.progress()!!
        assertEquals(AppResult.Success(true), f.completeRecovery(page))
        assertEquals(current.revision + 1, f.progress()!!.revision)
        assertTrue(f.read().events.isEmpty())
        for (reference in page.events) assertEquals(
            reference.copy(resolved = true),
            f.source(reference.event.eventId).reference,
        )
    }

    @Test
    fun completedPageReplayDoesNotRewriteOrClaimAnotherCompletion() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        assertEquals(AppResult.Success(true), f.completeRecovery(page))
        f.local.reopen()
        val before = f.raw()
        assertEquals(AppResult.Success(false), f.completeRecovery(page))
        assertEquals(before, f.raw())
    }

    @Test
    fun completingAbsentHistoryDoesNotReadKeysOrAllocateARecord() = withDatabases { f ->
        var reads = 0
        val keys =
            object : AtomicCredentialStore by f.local.keys {
                override suspend fun read(): String? {
                    reads++
                    error("unexpected key read")
                }

                override suspend fun writeIfAbsent(value: String): String {
                    reads++
                    error("unexpected key allocation")
                }
            }
        val progress =
            CollaborationHistoryProgress(
                historyId(9802),
                0,
                null,
                3,
                CollaborationIncomingHistoryPosition("head", null),
                if (f.space) f.provider else null,
                f.recovery(),
            )
        val page = CollaborationHistoryReferences(progress, emptyList(), null)
        val repo = f.repository(credentials = keys)
        val result =
            if (f.space) repo.complete(f.spaceAccess, f.traversal, page, emptyList(), 16)
            else repo.complete(f.access, f.traversal, page, emptyList(), 16)
        assertEquals(AppResult.Success(false), result)
        assertEquals(0, reads)
        assertNull(f.raw().checkpoint)
        assertTrue(f.raw().documents.isEmpty())
    }

    @Test
    fun invalidResolvedSubsetsAndLimitsCannotChangeAnyReference() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        val before = f.raw()
        val first = page.events.first()
        for (resolved in
            listOf(
                List(17) { first },
                listOf(first, first),
                listOf(first.copy(resolved = true)),
                listOf(first.copy(sequence = 90)),
            )) {
            assertIs<AppResult.Failed>(f.completeRecovery(page, resolved))
            assertEquals(before, f.raw())
        }
        assertIs<AppResult.Failed>(f.completeRecovery(page, limit = 0))
        assertIs<AppResult.Failed>(f.completeRecovery(page, limit = 17))
        assertEquals(before, f.raw())
    }

    @Test
    fun forgedSourceMetadataCannotBeMadeValidByMatchingItsResolvedSubset() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        val before = f.raw()
        val changed =
            page.events
                .first()
                .copy(event = page.events.first().event.copy(sender = "@other:example.test"))
        val forged = page.copy(events = listOf(changed) + page.events.drop(1))
        assertIs<AppResult.Failed>(f.completeRecovery(forged, listOf(changed)))
        assertEquals(before, f.raw())
    }

    @Test
    fun completionCannotSkipAnUnattemptedPendingSourceAtEof() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        val before = f.raw()
        val omitted = page.copy(events = listOf(page.events.first(), page.events.last()))
        assertIs<AppResult.Failed>(f.completeRecovery(omitted))
        assertEquals(before, f.raw())
    }

    @Test
    fun completionRejectsChangedCoverageWithoutResolvingTheCapturedRows() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val racing =
            object : ReplicaStore by f.local.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    f.stage(listOf(f.event(4)), "query-race")
                    emitAll(f.local.database.replicas.observeQueries(scope, partition, queries))
                }
            }
        assertEquals(AppResult.Success(false), f.completeRecovery(page, limit = 1, store = racing))
        assertFalse(f.source(page.events.first().event.eventId).reference!!.resolved)
        assertEquals(f.recovery(), f.progress()!!.recovery)
        assertEquals(4, f.read().events.size)
    }

    @Test
    fun completionLosingItsCommitCannotLeavePartiallyResolvedReferences() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val racing =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.stage(listOf(f.event(4)), "commit-race")
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
        assertEquals(AppResult.Success(false), f.completeRecovery(page, limit = 1, store = racing))
        assertFalse(f.source(page.events.first().event.eventId).reference!!.resolved)
        assertEquals(f.recovery(), f.progress()!!.recovery)
        assertEquals(4, f.read().events.size)
    }

    @Test
    fun cancellationBeforeAtomicCompletionKeepsBothProgressAndReferences() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val before = f.raw()
        val entered = CompletableDeferred<Unit>()
        val waiting =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = async { f.completeRecovery(page, limit = 1, store = waiting) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertEquals(before, f.raw())
    }

    @Test
    fun aLostCompletionReplyRetainsBothChangesAcrossReopen() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val lost =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
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
                    error("reply lost after atomic completion")
                }
            }
        assertIs<AppResult.Failed>(f.completeRecovery(page, limit = 1, store = lost))
        f.local.reopen()
        val before = f.raw()
        assertTrue(f.source(page.events.first().event.eventId).reference!!.resolved)
        assertEquals(page.after, f.progress()!!.recovery!!.after)
        assertEquals(listOf(f.event(2), f.event(3)), f.recoveryPage().events.map { it.event })
        assertEquals(AppResult.Success(false), f.completeRecovery(page, limit = 1))
        assertEquals(before, f.raw())
    }

    @Test
    fun cancellationAfterAtomicCommitPreservesBothChangesForRecovery() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage(1)
        val committed = CompletableDeferred<Unit>()
        val waiting =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
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
                    committed.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = async { f.completeRecovery(page, limit = 1, store = waiting) }
            committed.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        f.local.reopen()
        assertTrue(f.source(page.events.first().event.eventId).reference!!.resolved)
        assertEquals(page.after, f.progress()!!.recovery!!.after)
        assertEquals(listOf(f.event(2), f.event(3)), f.recoveryPage().events.map { it.event })
    }

    @Test
    fun completionCannotCrossAccountAudienceCompanyOrSpaceNamespace() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        val before = f.raw()
        val results =
            if (f.space)
                listOf(
                        f.spaceAccess.copy(accountId = historyId(9897)),
                        f.spaceAccess.copy(audience = "https://other.example.test/"),
                        f.spaceAccess.copy(spaceId = historyId(9897)),
                    )
                    .map { f.repository().complete(it, f.traversal, page, page.events, 16) }
            else
                listOf(
                        f.access.copy(accountId = historyId(9897)),
                        f.access.copy(audience = "https://other.example.test/"),
                        f.access.copy(companyId = historyId(9897)),
                    )
                    .map { f.repository().complete(it, f.traversal, page, page.events, 16) }
        assertTrue(results.all { it == AppResult.Success(false) })
        val namespace =
            if (f.space) f.repository().complete(f.access, f.traversal, page, page.events, 16)
            else f.repository().complete(f.spaceAccess, f.traversal, page, page.events, 16)
        assertEquals(AppResult.Success(false), namespace)
        assertEquals(before, f.raw())
    }

    @Test
    fun replacedPassOrDisappearingKnownRequestCannotResolveTheOldPage() = withDatabases { f ->
        f.stage()
        val requested = f.recovery().copy(requestedGeneration = historyId(9810))
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, requested))
        val page = f.recoveryPage()
        for (next in listOf(f.recovery(), f.recovery().copy(generation = historyId(9811)))) {
            assertEquals(AppResult.Success(true), f.replace(f.progress()!!, next))
            val before = f.raw()
            assertEquals(AppResult.Success(false), f.completeRecovery(page))
            assertEquals(before, f.raw())
            assertEquals(3, f.read().events.size)
        }
    }

    @Test
    fun mutableAttemptedAndResolvedListsAreCapturedBeforeStorageSuspends() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val original = f.recoveryPage()
        val attempted = original.events.toMutableList()
        val resolved = original.events.toMutableList()
        val store =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    attempted.clear()
                    resolved.clear()
                    return f.local.database.replicas.checkpoint(scope, partition)
                }
            }
        assertEquals(
            AppResult.Success(true),
            f.completeRecovery(original.copy(events = attempted), resolved, store = store),
        )
        assertTrue(f.read().events.isEmpty())
        assertEquals(f.recovery().copy(complete = true), f.progress()!!.recovery)
    }

    @Test
    fun sixteenResolvedSourcesDoNotConsumeTheFollowingPage() = withDatabases { f ->
        f.stage((1..20).map { f.event(it) })
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery(20)))
        val first = f.recoveryPage()
        assertEquals(16, first.events.size)
        assertEquals(AppResult.Success(true), f.completeRecovery(first))
        assertEquals(first.after, f.progress()!!.recovery!!.after)
        assertEquals(false, f.progress()!!.recovery!!.complete)
        assertEquals((17..20).map { f.event(it) }, f.read().events.map { it.event })
        val last = f.recoveryPage()
        assertEquals(4, last.events.size)
        assertEquals(AppResult.Success(true), f.completeRecovery(last))
        assertTrue(f.read().events.isEmpty())
        assertEquals(f.recovery(20).copy(complete = true), f.progress()!!.recovery)
    }

    @Test
    fun failedOrCancelledReferenceEncodingCannotPublishPartOfACompletion() = withDatabases { f ->
        f.stage()
        assertEquals(AppResult.Success(true), f.replace(f.progress()!!, f.recovery()))
        val page = f.recoveryPage()
        val before = f.raw()
        for (cancel in listOf(false, true)) {
            var encryptions = 0
            val cipher =
                object : ContentCipher by f.local.cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        encryptions++
                        if (encryptions == 3 && !cancel)
                            error("reference encryption failed after earlier entries were encoded")
                        val encoded = f.local.cipher.encrypt(key, associatedData, plaintext)
                        if (encryptions == 3 && cancel) currentCoroutineContext().cancel()
                        return encoded
                    }
                }
            val repo = f.repository(cipher = cipher)
            suspend fun complete() =
                if (f.space) repo.complete(f.spaceAccess, f.traversal, page, page.events, 16)
                else repo.complete(f.access, f.traversal, page, page.events, 16)
            if (cancel)
                coroutineScope {
                    val job = async { complete() }
                    job.join()
                    assertTrue(job.isCancelled)
                }
            else assertIs<AppResult.Failed>(complete())
            assertTrue(encryptions >= 3)
            assertEquals(before, f.raw())
        }
    }

    private fun withDatabases(block: suspend (CollaborationHistoryRecoveryFixture) -> Unit) =
        runTest {
            for (space in listOf(false, true)) {
                val fixture = CollaborationHistoryRecoveryFixture(databaseOwner(), space)
                try {
                    fixture.local.activate()
                    block(fixture)
                } finally {
                    fixture.local.close()
                }
            }
        }
}
