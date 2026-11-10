package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Real encrypted storage: provider coverage and pending decryption work must survive independently.
 */
abstract class CollaborationSpaceHistoryTraversalStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun emptyStorageDoesNotAllocateKeysOrInventExhaustion() = withDatabase { f ->
        assertNull(f.progress())
        assertEquals(CollaborationHistoryReferences(null, emptyList(), null), f.read())
        assertNull(f.local.keys.read())
    }

    @Test
    fun exactSourceAbsenceCreatesNoKeysOrFrontier() = withDatabase { f ->
        assertEquals(
            AppResult.Success(CollaborationHistorySource(null, null)),
            f.repository().source(f.access, f.traversal, f.event().eventId),
        )
        assertNull(f.local.keys.read())
        assertNull(f.progress())
    }

    @Test
    fun exactSourceLookupPreservesResolvedFactsAcrossReopenAndNewerWaves() = withDatabase { f ->
        f.stage(listOf(f.event(), f.event(2)), null)
        val pending = f.read()
        assertEquals(
            AppResult.Success(CollaborationHistorySource(pending.progress, pending.events.first())),
            f.repository().source(f.access, f.traversal, f.event().eventId),
        )
        assertEquals(
            AppResult.Success(true),
            f.repository()
                .resolve(f.access, f.traversal, pending.progress!!, listOf(pending.events.first())),
        )
        val resolved = pending.events.first().copy(resolved = true)
        val afterResolution = f.progress()
        val unchanged = f.raw()
        assertEquals(
            AppResult.Success(CollaborationHistorySource(afterResolution, resolved)),
            f.repository().source(f.access, f.traversal, f.event().eventId),
        )
        assertEquals(unchanged, f.raw())
        f.local.reopen()
        assertEquals(
            AppResult.Success(CollaborationHistorySource(afterResolution, resolved)),
            f.repository().source(f.access, f.traversal, f.event().eventId),
        )
        f.stage(listOf(f.event(3)), null, CollaborationHistoryPageOrigin.Latest, "newer-position")
        assertEquals(
            AppResult.Success(CollaborationHistorySource(f.progress(), resolved)),
            f.repository().source(f.access, f.traversal, f.event().eventId),
        )
        assertEquals(
            AppResult.Success(CollaborationHistorySource(f.progress(), null)),
            f.repository().source(f.access, f.traversal, "\$never-acquired"),
        )
    }

    @Test
    fun paginationTokenCannotBeMistakenForAnAcquiredEventId() = withDatabase { f ->
        f.stage(listOf(f.event()), null, head = "\$looks-like-an-event")
        assertEquals("\$looks-like-an-event", f.progress()!!.incoming!!.head)
        assertEquals(
            AppResult.Success(CollaborationHistorySource(f.progress(), null)),
            f.repository().source(f.access, f.traversal, "\$looks-like-an-event"),
        )
        assertNotNull(
            f.success(f.repository().source(f.access, f.traversal, f.event().eventId)).reference
        )
    }

    @Test
    fun exactSourceLookupRejectsSubstitutedRowsAndCheckpointGenerations() = withDatabase { f ->
        f.stage(listOf(f.event(), f.event(2)), null)
        val raw = f.raw()
        val requested = f.repository().source(f.access, f.traversal, f.event().eventId)
        for (substitution in 0..4) {
            val store =
                object : ReplicaStore by f.local.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) = flow {
                        val original =
                            f.local.database.replicas.observeDocument(scope, partition, id).first()
                        val checkpoint = requireNotNull(original.checkpoint)
                        emit(
                            when (substitution) {
                                0 ->
                                    original.copy(
                                        documents = raw.documents.filter { it.id != id }.take(1)
                                    )
                                1 ->
                                    original.copy(
                                        documents = original.documents + original.documents
                                    )
                                2 -> original.copy(checkpoint = null)
                                3 ->
                                    original.copy(
                                        checkpoint =
                                            checkpoint.copy(
                                                handle =
                                                    checkpoint.handle.copy(
                                                        generation = historyId(9876)
                                                    )
                                            )
                                    )
                                else ->
                                    original.copy(
                                        documents =
                                            listOf(original.documents.single().copy(payload = null))
                                    )
                            }
                        )
                    }
                }
            assertIs<AppResult.Failed>(
                f.repository(store).source(f.access, f.traversal, f.event().eventId)
            )
        }
        assertEquals(requested, f.repository().source(f.access, f.traversal, f.event().eventId))
        assertEquals(raw, f.raw())
    }

    @Test
    fun exactSourceKeepsAccountSpaceRoomAndLogicalDeviceBoundaries() = withDatabase { f ->
        f.stage(listOf(f.event()), null)
        val missing = AppResult.Success(CollaborationHistorySource(null, null))
        assertEquals(
            missing,
            f.repository()
                .source(f.access.copy(spaceId = historyId(9870)), f.traversal, f.event().eventId),
        )
        assertEquals(
            missing,
            f.repository()
                .source(f.access.copy(accountId = historyId(9871)), f.traversal, f.event().eventId),
        )
        for (key in
            listOf(
                f.traversal.copy(deviceId = historyId(9872)),
                f.traversal.copy(conversationId = historyId(9873)),
                f.traversal.copy(purpose = CollaborationHistoryPurpose.Browse),
            )) {
            assertEquals(missing, f.repository().source(f.access, key, f.event().eventId))
        }
        // Room identity is authenticated inside the existing conversation/device partition.
        assertIs<AppResult.Failed>(
            f.repository()
                .source(
                    f.access,
                    f.traversal.copy(roomId = "!other:example.test"),
                    f.event().eventId,
                )
        )
        val current = f.success(f.repository().source(f.access, f.traversal, f.event().eventId))
        assertEquals(f.provider, current.progress!!.provider)
        assertNotNull(current.reference)
    }

    @Test
    fun sourceReadUsesTheFinalAtomicPageWhenProgressChangesDuringIndexAcquisition() =
        withDatabase { f ->
            f.stage(listOf(f.event()), null)
            var reads = 0
            val store =
                object : ReplicaStore by f.local.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) = flow {
                        reads++
                        f.stage(
                            listOf(f.event(2)),
                            null,
                            CollaborationHistoryPageOrigin.Latest,
                            "newer-position",
                        )
                        emit(
                            f.local.database.replicas.observeDocument(scope, partition, id).first()
                        )
                    }
                }
            val result =
                f.success(f.repository(store).source(f.access, f.traversal, f.event().eventId))
            assertEquals(1, reads)
            assertEquals(f.progress(), result.progress)
            assertEquals(f.event(), result.reference!!.event)
            assertEquals(0, result.reference!!.sequence)
        }

    @Test
    fun exhaustedFrontierAndPendingReferencesSurviveAReopenAndANewWave() = withDatabase { f ->
        f.stage((1..20).map(f::event))
        f.stage(listOf(f.event(21)), null)
        val first = f.read()
        assertEquals(f.provider, first.progress!!.provider)
        assertTrue(first.progress!!.exhausted)
        assertEquals("first-head", first.progress!!.incoming!!.head)
        assertEquals(
            AppResult.Success(true),
            f.repository().resolve(f.access, f.traversal, first.progress!!, first.events.take(2)),
        )
        val pending = f.read()
        val retained = f.raw()
        f.local.reopen()
        assertEquals(pending, f.read())
        assertEquals(retained, f.raw())
        f.stage(
            listOf(f.event(22), f.event(1)),
            null,
            CollaborationHistoryPageOrigin.Latest,
            "second-head",
        )
        val progress = f.progress()!!
        assertEquals("first-head", progress.incoming!!.until)
        assertEquals("second-head", progress.incoming!!.head)
        assertEquals(22, progress.references)
        assertEquals((3..22).map(f::event), f.read().events.map { it.event })
        f.local.reopen()
        assertEquals(progress, f.progress())
    }

    @Test
    fun logicalDeviceNeverRelabelsPhysicalCursorAndChangedProviderCannotResetIt() =
        withDatabase { f ->
            assertNotEquals(f.runtime.deviceId, f.provider.deviceId)
            assertNotEquals(f.runtime.owner.spaceId, f.provider.companyId)
            f.stage(listOf(f.event()))
            val progress = f.progress()!!
            val before = f.raw()
            val next = f.page(listOf(f.event(2)), progress.next)
            for (page in
                listOf(
                    next.copy(physical = next.physical.copy(deviceId = historyId(9990))),
                    next.copy(
                        physical =
                            next.physical.copy(owner = f.physical.copy(companyId = historyId(9991)))
                    ),
                    next.copy(runtime = next.runtime.copy(deviceId = historyId(9992))),
                    next.copy(
                        runtime =
                            next.runtime.copy(
                                owner = next.runtime.owner.copy(spaceId = historyId(9993))
                            )
                    ),
                    next.copy(
                        physical =
                            next.physical.copy(owner = f.physical.copy(accountId = historyId(9994)))
                    ),
                )) {
                assertIs<AppResult.Failed>(
                    f.repository().stage(f.access, f.traversal, progress, page)
                )
                assertEquals(before, f.raw())
            }
            val changed =
                next.copy(
                    physical =
                        next.physical.copy(
                            owner = f.physical.copy(nativeSessionId = historyId(9995))
                        )
                )
            assertEquals(
                AppResult.Success(true),
                f.repository().stage(f.access, f.traversal, progress, changed),
            )
            assertEquals(f.provider, f.progress()!!.provider)
        }

    @Test
    fun conflictingMetadataStaleProgressAndInvalidBoundsNeverCommitAPartialPage() =
        withDatabase { f ->
            f.stage(listOf(f.event()))
            val progress = f.progress()!!
            val before = f.raw()
            for (page in
                listOf(
                    f.page(listOf(f.event(2), f.event().copy(timestamp = 0)), progress.next),
                    f.page((2..22).map(f::event), progress.next),
                    f.page(listOf(f.event(2)), "wrong"),
                    f.page(listOf(f.event(2)), progress.next, progress.next),
                    f.page(listOf(f.event(2)), progress.next, until = "wrong-bound"),
                    f.page(listOf(f.event(2), f.event(2)), progress.next),
                )) {
                assertIs<AppResult.Failed>(
                    f.repository().stage(f.access, f.traversal, progress, page)
                )
                assertEquals(before, f.raw())
            }
            assertEquals(
                AppResult.Success(false),
                f.repository()
                    .stage(
                        f.access,
                        f.traversal,
                        progress.copy(revision = progress.revision + 1),
                        f.page(listOf(f.event(2)), progress.next),
                    ),
            )
            assertEquals(before, f.raw())
        }

    @Test
    fun responseLossAndCancellationKeepEveryReferenceWithItsCommittedPosition() =
        withDatabase { f ->
            f.stage(listOf(f.event()))
            for (cancel in listOf(false, true)) {
                val previous = f.progress()!!
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
                            if (cancel) throw CancellationException("Fixture response lost")
                            error("Fixture response lost")
                        }
                    }
                val page =
                    f.page(
                        listOf(f.event(if (cancel) 3 else 2)),
                        previous.next,
                        if (cancel) null else "next-2",
                    )
                val operation = suspend {
                    f.repository(store).stage(f.access, f.traversal, previous, page)
                }
                if (cancel) assertFailsWith<CancellationException> { operation() }
                else assertIs<AppResult.Failed>(operation())
                f.local.reopen()
                assertEquals(previous.references + 1, f.progress()!!.references)
                assertEquals(page.physical.next, f.progress()!!.next)
                assertEquals(
                    AppResult.Success(false),
                    f.repository().stage(f.access, f.traversal, previous, page),
                )
            }
            assertEquals((1..3).map(f::event), f.read().events.map { it.event })
        }

    @Test
    fun capturedInputCannotChangeWhileStorageWaitsAndCancelledReadDoesNotWrite() =
        withDatabase { f ->
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
            val events = mutableListOf(f.event())
            coroutineScope {
                val pending = async {
                    f.repository(store).stage(f.access, f.traversal, null, f.page(events))
                }
                entered.await()
                events.clear()
                events += f.event(2)
                release.complete(Unit)
                assertEquals(AppResult.Success(true), pending.await())
            }
            assertEquals(listOf(f.event()), f.read().events.map { it.event })
            val before = f.raw()
            val blocked = CompletableDeferred<Unit>()
            val stalled =
                object : ReplicaStore by f.local.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        blocked.complete(Unit)
                        awaitCancellation()
                    }
                }
            coroutineScope {
                val pending = async {
                    f.repository(stalled)
                        .stage(
                            f.access,
                            f.traversal,
                            f.progress(),
                            f.page(listOf(f.event(2)), "next-1"),
                        )
                }
                blocked.await()
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
            }
            assertEquals(before, f.raw())
        }

    @Test
    fun pendingSeekIsBoundedWhileTheProviderFrontierAndResolutionsAdvance() = withDatabase { f ->
        f.stage((1..20).map(f::event))
        f.stage((21..40).map(f::event), "next-2")
        f.stage(listOf(f.event(41)), null)
        val first = f.read()
        assertEquals(
            AppResult.Success(true),
            f.repository().resolve(f.access, f.traversal, first.progress!!, first.events.take(10)),
        )
        assertEquals(
            AppResult.Success(true),
            f.repository().advanceIncomingReferences(f.access, f.traversal, first, true),
        )
        assertEquals(41, f.progress()!!.incoming!!.pendingThrough)
        assertTrue(f.progress()!!.incoming!!.pendingRetryRequired)
        f.local.reopen()
        f.stage((42..50).map(f::event), null, CollaborationHistoryPageOrigin.Latest, "second-head")
        val second =
            boundedCollaborationIncomingReferences(f.read(f.progress()!!.incoming!!.pendingAfter))!!
        assertEquals((21..40).map(f::event), second.events.map { it.event })
        assertEquals(
            AppResult.Success(true),
            f.repository().advanceIncomingReferences(f.access, f.traversal, second, false),
        )
        val last =
            boundedCollaborationIncomingReferences(f.read(f.progress()!!.incoming!!.pendingAfter))!!
        assertEquals(listOf(f.event(41)), last.events.map { it.event })
        assertNull(last.after)
        assertEquals(
            AppResult.Success(true),
            f.repository().resolve(f.access, f.traversal, last.progress!!, last.events),
        )
        assertEquals(
            AppResult.Success(true),
            f.repository().advanceIncomingReferences(f.access, f.traversal, last, false),
        )
        val progress = f.progress()!!
        assertNull(progress.incoming!!.pendingAfter)
        assertNull(progress.incoming!!.pendingThrough)
        assertFalse(progress.incoming!!.pendingRetryRequired)
        assertEquals("first-head", progress.incoming!!.until)
        assertEquals("second-head", progress.incoming!!.head)
        assertTrue(progress.exhausted)
        assertEquals(39, f.read(limit = 50).events.size)
    }

    @Test
    fun sameAccountLoginReplacementRetainsCursorsAndBlocksTheOldLocalOwner() = withDatabase { f ->
        f.stage(listOf(f.event()))
        val progress = f.progress()!!
        val key = f.local.keys.read()
        val current = f.access.copy(sessionId = "new-local-login")
        f.local.reopen(current)
        assertEquals(AppResult.Success(progress), f.repository().progress(current, f.traversal))
        assertEquals(AppResult.Success(null), f.repository().progress(f.access, f.traversal))
        val original = f.page(listOf(f.event(2)), progress.next)
        val page =
            original.copy(
                runtime =
                    original.runtime.copy(
                        owner =
                            original.runtime.owner.copy(
                                parent =
                                    original.runtime.owner.parent.copy(
                                        sessionId = current.sessionId,
                                        nativeSessionId = historyId(9900),
                                    )
                            )
                    ),
                physical =
                    original.physical.copy(
                        owner = original.physical.owner.copy(sessionId = current.sessionId)
                    ),
            )
        assertEquals(
            AppResult.Success(true),
            f.repository().stage(current, f.traversal, progress, page),
        )
        val acquired = f.success(f.repository().progress(current, f.traversal))!!
        assertEquals(f.provider, acquired.provider)
        assertEquals(2, acquired.references)
        assertEquals(key, f.local.keys.read())
    }

    @Test
    fun malformedReferenceEmitsFailureButTheSameObserverCanRecover() = withDatabase { f ->
        f.stage(listOf(f.event()))
        val raw = f.raw()
        val document = raw.documents.single()
        val source = kotlinx.coroutines.channels.Channel<ReplicaQueryPage>(2)
        val store =
            object : ReplicaStore by f.local.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = source.receiveAsFlow()
            }
        coroutineScope {
            val observing = async {
                f.repository(store).observeReferences(f.access, f.traversal).take(2).toList()
            }
            source.send(
                ReplicaQueryPage(
                    raw.checkpoint,
                    listOf(listOf(document.copy(payload = "malformed"))),
                )
            )
            source.send(ReplicaQueryPage(raw.checkpoint, listOf(raw.documents)))
            val values = observing.await()
            assertIs<AppResult.Failed>(values[0])
            assertEquals(listOf(f.event()), f.success(values[1]).events.map { it.event })
        }
        source.close()
        assertEquals(raw, f.raw())
    }

    @Test
    fun copiedCiphertextCannotBecomeCompanyAnotherSpaceOrAnotherDeviceProgress() =
        withDatabase { f ->
            f.stage(listOf(f.event()))
            val raw = f.raw()
            val checkpoint = raw.checkpoint!!
            val snapshot =
                ReplicaSnapshot(checkpoint.handle.generation, checkpoint.payload, raw.documents)
            assertTrue(
                f.local.database.replicas.replace(
                    historyScope(f.local.legacyAccess),
                    traversalPartition(f.local.legacyAccess, f.traversal),
                    null,
                    snapshot,
                )
            )
            assertIs<AppResult.Failed>(
                f.legacyRepository().progress(f.local.legacyAccess, f.traversal)
            )
            val other = f.access.copy(spaceId = historyId(9910))
            assertTrue(
                f.local.database.replicas.replace(
                    spaceHistoryScope(other),
                    traversalPartition(other, f.traversal),
                    null,
                    snapshot,
                )
            )
            assertIs<AppResult.Failed>(f.repository().progress(other, f.traversal))
            val device = f.traversal.copy(deviceId = historyId(9911))
            assertTrue(
                f.local.database.replicas.replace(
                    spaceHistoryScope(f.access),
                    traversalPartition(f.access, device),
                    null,
                    snapshot,
                )
            )
            assertIs<AppResult.Failed>(f.repository().progress(f.access, device))
            assertIs<AppResult.Failed>(
                f.repository()
                    .progress(f.access, f.traversal.copy(roomId = "!foreign:example.test"))
            )
            assertEquals(raw, f.raw())
            assertEquals(f.provider, f.progress()!!.provider)
        }

    @Test
    fun missingEncryptionKeyCannotReplaceRetainedCompanyOrSpaceHistory() = withDatabase { f ->
        f.local
            .legacyRepository()
            .merge(
                f.local.legacyAccess,
                f.local.conversation,
                f.local.room,
                listOf(f.local.event()),
            )
            .let { assertEquals(AppResult.Success(true), it) }
        f.local.keys.write(null)
        assertIs<AppResult.Failed>(
            f.repository().stage(f.access, f.traversal, null, f.page(listOf(f.event())))
        )
        assertNull(f.local.keys.read())
        assertNull(f.raw().checkpoint)
    }

    @Test
    fun companyFramesAndKeyedIndexesStayExactWhileSpaceUsesItsOwnProtocol() = withDatabase { f ->
        val aad = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val cipher =
            object : ContentCipher by f.local.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    aad += associatedData.decodeToString()
                    bodies += plaintext.decodeToString()
                    return f.local.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        val content = ByteArray(32) { (it + 32).toByte() }
        val index = ByteArray(32) { it.toByte() }
        try {
            val browse =
                CollaborationHistoryTraversalCodec(
                    f.local.legacyAccess,
                    f.traversal.copy(purpose = CollaborationHistoryPurpose.Browse),
                    cipher,
                    content,
                    index,
                )
            val incoming =
                CollaborationHistoryTraversalCodec(
                    f.local.legacyAccess,
                    f.traversal,
                    cipher,
                    content,
                    index,
                )
            val space =
                CollaborationHistoryTraversalCodec(
                    CollaborationHistoryCoordinates.Space(f.access, f.traversal.conversationId),
                    f.traversal,
                    cipher,
                    content,
                    index,
                )
            assertEquals(
                "r-d67394f6762e4d948d627b0aac362a568c0326c6df9233c3cea5b180cf299776",
                browse.id(f.event().eventId),
            )
            assertEquals(
                "r-8924fa2a8ec5dc1e035c2d921414987718da312c20a4f53423135894f19e629a",
                incoming.id(f.event().eventId),
            )
            assertEquals(
                "r-c306cce2e3ea90639f9b8df053f2d3d1f020d9dc136d3a03c86b37ab0d9d0aa4",
                space.id(f.event().eventId),
            )
            val progress = CollaborationHistoryProgress(historyId(9800), 0, "next", 1)
            browse.checkpoint(progress)
            incoming.checkpoint(
                progress.copy(incoming = CollaborationIncomingHistoryPosition("head", null))
            )
            space.checkpoint(
                progress.copy(
                    incoming = CollaborationIncomingHistoryPosition("head", null),
                    provider = f.provider,
                )
            )
            val rest =
                listOf(
                    f.access.audience,
                    f.access.accountId,
                    f.access.spaceId,
                    f.traversal.conversationId,
                    f.traversal.roomId,
                    f.traversal.deviceId,
                    historyId(9800),
                    "checkpoint",
                    "0",
                    "",
                )
            assertEquals(
                listOf(
                        "hris-history-pages-v1",
                        "hris-incoming-history-pages-v1",
                        "hris-space-incoming-history-pages-v1",
                    )
                    .map { Json.encodeToString(listOf(it) + rest) },
                aad,
            )
            assertEquals(
                """{"roomId":"!history:example.test","next":"next","references":1}""",
                bodies[0],
            )
            assertEquals(
                """{"roomId":"!history:example.test","next":"bmV4dA==","references":1,"schema":2,"incoming":{"head":"aGVhZA==","until":null}}""",
                bodies[1],
            )
            assertTrue(bodies[2].contains("\"schema\":3"))
            assertTrue(bodies[2].contains("\"provider\""))
            assertFailsWith<IllegalArgumentException> { space.checkpoint(progress) }
            assertFailsWith<IllegalArgumentException> {
                incoming.checkpoint(progress.copy(provider = f.provider))
            }
        } finally {
            content.fill(0)
            index.fill(0)
        }
    }

    @Test
    fun competingPageCommitsKeepExactlyOneWholePageAndTheOriginalReferences() = withDatabase { f ->
        f.stage(listOf(f.event()))
        val previous = f.progress()!!
        var arrived = 0
        val release = CompletableDeferred<Unit>()
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
                    arrived++
                    if (arrived == 2) release.complete(Unit)
                    release.await()
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
        val results = coroutineScope {
            (2..3)
                .map { i ->
                    async {
                        f.repository(store)
                            .stage(
                                f.access,
                                f.traversal,
                                previous,
                                f.page(listOf(f.event(i)), previous.next, "next-$i"),
                            )
                    }
                }
                .awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        val page = f.read()
        assertEquals(2, page.events.size)
        assertEquals(f.event(), page.events[0].event)
        val winner = if (page.events[1].event == f.event(2)) 2 else 3
        assertEquals("next-$winner", page.progress!!.next)
        assertEquals(previous.generation, page.progress!!.generation)
        f.local.reopen()
        assertEquals(page, f.read())
    }

    @Test
    fun sixteenReferenceSeekSurvivesResolutionReopenAndLaterProviderAppends() = withDatabase { f ->
        f.stage((1..20).map(f::event))
        f.stage((21..25).map(f::event), null)
        val first = f.read(limit = 16)
        val before = f.raw()
        assertIs<AppResult.Failed>(
            f.repository().advanceIncomingReferences(f.access, f.traversal, first, true)
        )
        assertEquals(before, f.raw())
        assertEquals(
            AppResult.Success(true),
            f.repository().resolve(f.access, f.traversal, first.progress!!, first.events.take(8)),
        )
        assertEquals(
            AppResult.Success(true),
            f.repository().advanceIncomingReferences(f.access, f.traversal, first, true, limit = 16),
        )
        assertEquals(25, f.progress()!!.incoming!!.pendingThrough)
        f.local.reopen()
        f.stage((26..30).map(f::event), null, CollaborationHistoryPageOrigin.Latest, "new-head")
        val final =
            boundedCollaborationIncomingReferences(
                f.read(f.progress()!!.incoming!!.pendingAfter, 16),
                16,
            )!!
        assertEquals((17..25).map(f::event), final.events.map { it.event })
        assertNull(final.after)
        assertTrue(final.progress!!.incoming!!.pendingRetryRequired)
        assertEquals(
            AppResult.Success(true),
            f.repository().resolve(f.access, f.traversal, final.progress!!, final.events),
        )
        assertEquals(
            AppResult.Success(true),
            f.repository()
                .advanceIncomingReferences(f.access, f.traversal, final, false, limit = 16),
        )
        f.local.reopen()
        assertNull(f.progress()!!.incoming!!.pendingAfter)
        assertEquals(((9..16) + (26..30)).map(f::event), f.read().events.map { it.event })
    }

    @Test
    fun wrongBoundAndCopiedSeekNeverSkipAnUnattemptedReference() = withDatabase { f ->
        f.stage((1..20).map(f::event))
        val first = f.read(limit = 16)
        val before = f.raw()
        for (limit in listOf(0, 15, 21)) {
            assertIs<AppResult.Failed>(
                f.repository().advanceIncomingReferences(f.access, f.traversal, first, false, limit)
            )
            assertEquals(before, f.raw())
        }
        for (page in
            listOf(
                first.copy(after = "unowned-cursor"),
                first.copy(events = first.events.dropLast(1)),
                first.copy(
                    events =
                        first.events.toMutableList().also {
                            it[0] = it[0].copy(event = it[0].event.copy(timestamp = 1))
                        }
                ),
            )) {
            assertIs<AppResult.Failed>(
                f.repository().advanceIncomingReferences(f.access, f.traversal, page, false, 16)
            )
            assertEquals(before, f.raw())
        }
    }

    @Test
    fun unchangedPendingCoordinatesCannotAdvanceForAnotherPhysicalProvider() = withDatabase { f ->
        f.stage((1..20).map(f::event))
        val first = f.read(limit = 16)
        val before = f.raw()
        for (provider in
            listOf(
                f.provider.copy(companyId = historyId(9981)),
                f.provider.copy(deviceId = historyId(9982)),
            )) {
            val source = first.copy(progress = first.progress!!.copy(provider = provider))
            assertEquals(
                AppResult.Success(false),
                f.repository().advanceIncomingReferences(f.access, f.traversal, source, false, 16),
            )
            assertEquals(before, f.raw())
        }
    }

    private fun withDatabase(block: suspend (CollaborationSpaceHistoryTraversalFixture) -> Unit) =
        runTest {
            val f = CollaborationSpaceHistoryTraversalFixture(databaseOwner())
            try {
                f.local.activate()
                block(f)
            } finally {
                f.local.close()
            }
        }
}
