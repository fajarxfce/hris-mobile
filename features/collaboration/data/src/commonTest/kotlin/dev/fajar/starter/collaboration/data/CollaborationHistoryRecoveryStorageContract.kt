package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import kotlin.test.*
import kotlinx.coroutines.*
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
