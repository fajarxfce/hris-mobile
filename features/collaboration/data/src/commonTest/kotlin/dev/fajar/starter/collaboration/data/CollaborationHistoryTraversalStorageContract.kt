package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Same transactional encrypted storage cases on SQLite and IndexedDB. */
abstract class CollaborationHistoryTraversalStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    protected open val purpose = CollaborationHistoryPurpose.Browse

    @Test
    fun exactSourceAbsenceIsKeylessAndDoesNotCreateAProviderPosition() = withDatabase { f ->
        assertEquals(
            CollaborationHistorySource(null, null),
            f.success(f.repository().source(f.access, f.traversal, f.event().eventId)),
        )
        assertIs<AppResult.Failed>(f.repository().source(f.access, f.traversal, "invalid event"))
        assertNull(f.local.keys.read())
        assertNull(f.progress())
    }

    @Test
    fun exactSourceKeepsPendingAndResolvedIdentityBesideItsActualCheckpoint() = withDatabase { f ->
        f.stage(listOf(f.event(2), f.event(1)), null)
        val pending = f.success(f.repository().source(f.access, f.traversal, f.event(1).eventId))
        assertEquals(f.progress(), pending.progress)
        val reference = assertNotNull(pending.reference)
        assertEquals(1L, reference.sequence)
        assertEquals(f.event(1), reference.event)
        assertFalse(reference.resolved)
        assertTrue(
            f.success(
                f.repository()
                    .resolve(
                        f.access,
                        f.traversal,
                        assertNotNull(pending.progress),
                        listOf(reference),
                    )
            )
        )
        val resolved = f.success(f.repository().source(f.access, f.traversal, f.event(1).eventId))
        assertEquals(reference.copy(resolved = true), resolved.reference)
        assertEquals(f.progress(), resolved.progress)
        assertEquals(pending.progress!!.revision + 1, resolved.progress?.revision)
        assertNull(resolved.progress?.provider)
        assertEquals(listOf(f.event(2)), f.read().events.map { it.event })
        val absent = f.success(f.repository().source(f.access, f.traversal, f.event(3).eventId))
        assertEquals(resolved.progress, absent.progress)
        assertNull(absent.reference)
    }

    @Test
    fun exactSourceLookupUsesOneIndexedDocumentWithoutScanningPendingReferences() =
        withDatabase { f ->
            f.stage((1..20).map(f::event), null)
            var points = 0
            val indexed =
                object : ReplicaStore by f.local.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> = error("No partition scan")

                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> = error("No pending-reference scan")

                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): Flow<ReplicaPage> {
                        points++
                        return f.local.database.replicas.observeDocument(scope, partition, id)
                    }
                }
            val found =
                f.success(f.repository(indexed).source(f.access, f.traversal, f.event(17).eventId))
            assertEquals(f.event(17), found.reference?.event)
            assertEquals(16L, found.reference?.sequence)
            assertEquals(f.progress(), found.progress)
            assertEquals(1, points)
        }

    @Test
    fun companyDevicePurposeAndRoomCannotShareAnExactSourceByEventIdAlone() = withDatabase { f ->
        f.stage(listOf(f.event()), null)
        assertEquals(
            CollaborationHistorySource(null, null),
            f.success(
                f.repository()
                    .source(
                        f.access.copy(companyId = historyId(9200)),
                        f.traversal,
                        f.event().eventId,
                    )
            ),
        )
        for (other in
            listOf(
                f.traversal.copy(deviceId = historyId(9201)),
                f.traversal.copy(conversationId = historyId(9202)),
                f.traversal.copy(
                    purpose =
                        if (purpose == CollaborationHistoryPurpose.Browse)
                            CollaborationHistoryPurpose.Incoming
                        else CollaborationHistoryPurpose.Browse
                ),
            )) assertEquals(
            CollaborationHistorySource(null, null),
            f.success(f.repository().source(f.access, other, f.event().eventId)),
        )
        assertIs<AppResult.Failed>(
            f.repository()
                .source(
                    f.access,
                    f.traversal.copy(roomId = "!foreign:example.test"),
                    f.event().eventId,
                )
        )
        assertEquals(
            f.event(),
            f.success(f.repository().source(f.access, f.traversal, f.event().eventId))
                .reference
                ?.event,
        )
    }

    @Test
    fun exactSourceRejectsAlteredIdentityPayloadAndDuplicateDocuments() = withDatabase { f ->
        f.stage(listOf(f.event()), null)
        for (mode in 0..2) {
            val damaged =
                object : ReplicaStore by f.local.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): Flow<ReplicaPage> =
                        f.local.database.replicas.observeDocument(scope, partition, id).map { page
                            ->
                            val document = page.documents.single()
                            page.copy(
                                documents =
                                    when (mode) {
                                        0 -> listOf(document.copy(id = "other-source"))
                                        1 -> listOf(document.copy(payload = "AAAA"))
                                        else -> listOf(document, document)
                                    }
                            )
                        }
                }
            assertIs<AppResult.Failed>(
                f.repository(damaged).source(f.access, f.traversal, f.event().eventId)
            )
        }
        assertEquals(
            f.event(),
            f.success(f.repository().source(f.access, f.traversal, f.event().eventId))
                .reference
                ?.event,
        )
    }

    @Test
    fun cancellationDuringExactSourceReadPropagatesWithoutChangingReferences() = withDatabase { f ->
        f.stage(listOf(f.event()), null)
        val before = f.raw()
        val entered = CompletableDeferred<Unit>()
        val blocked =
            object : ReplicaStore by f.local.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flow {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        val job =
            CoroutineScope(currentCoroutineContext()).async {
                f.repository(blocked).source(f.access, f.traversal, f.event().eventId)
            }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(before, f.raw())
    }

    @Test
    fun exactSourceReopensWithItsOriginalDeviceAndCompanyUnderAReplacementLogin() =
        withDatabase { f ->
            f.stage(listOf(f.event()), null)
            val before = f.success(f.repository().source(f.access, f.traversal, f.event().eventId))
            val replacement = f.access.copy(sessionId = "replacement-source-session")
            f.local.reopen(replacement)
            assertEquals(
                before,
                f.success(f.repository().source(replacement, f.traversal, f.event().eventId)),
            )
            assertEquals(
                CollaborationHistorySource(null, null),
                f.success(f.repository().source(f.access, f.traversal, f.event().eventId)),
            )
        }

    @Test
    fun emptyAdvancingAndTerminalPagesSurviveReopenWithoutAcknowledgingReferences() =
        withDatabase { f ->
            assertNull(f.progress())
            assertTrue(f.read().events.isEmpty())
            assertNull(f.local.keys.read())
            f.stage(emptyList(), "gap-next")
            val first = requireNotNull(f.progress())
            assertFalse(first.exhausted)
            assertEquals(0, first.references)
            f.stage(listOf(f.event()), "older-next")
            f.stage(emptyList(), null)
            val finished = requireNotNull(f.progress())
            assertTrue(finished.exhausted)
            assertEquals(1, finished.references)
            assertEquals(first.generation, finished.generation)
            assertEquals(2, finished.revision)
            val renewed = f.access.copy(sessionId = "renewed-login")
            f.local.reopen(renewed)
            assertEquals(finished, f.progress(renewed))
            assertEquals(listOf(f.event()), f.read(renewed).events.map { it.event })
            assertNull(f.progress())
            assertTrue(f.read().events.isEmpty())
            assertFalse(finished.toString().contains("older-next"))
        }

    @Test
    fun overlappingPagesPreserveOrderAndIndexesPageBeyondOutboxCapacity() = withDatabase { f ->
        f.stage(listOf(f.event(3), f.event(1), f.event(2)), "position-1")
        f.stage(listOf(f.event(2), f.event(4)), "position-2")
        for ((i, batch) in (5..141).chunked(20).withIndex()) f.stage(
            batch.map(f::event),
            "position-${i + 3}",
        )
        val bounded =
            object : ReplicaStore by f.local.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("No full partition scan")

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    assertEquals(1, queries.size)
                    assertTrue(queries.single().limit <= 18)
                    assertEquals(HISTORY_REFERENCES, queries.single().tag)
                    return f.local.database.replicas.observeQueries(scope, partition, queries)
                }
            }
        val found = mutableListOf<CollaborationHistoryReference>()
        var after: String? = null
        do {
            val page =
                f.success(
                    f.repository(bounded)
                        .observeReferences(f.access, f.traversal, after, 17)
                        .first()
                )
            found += page.events
            after = page.after
        } while (after != null)
        assertEquals(
            listOf(3, 1, 2) + (4..141),
            found.map { it.event.eventId.removePrefix("\$reference-").toInt() },
        )
        assertEquals((0L..140L).toList(), found.map { it.sequence })
        assertEquals(141, f.progress()!!.references)
        for (document in f.raw().documents) {
            assertFalse(document.toString().contains("fictional"))
            assertFalse(document.toString().contains("reference-"))
        }
        assertFalse(f.raw().checkpoint!!.payload.contains("position-"))
    }

    @Test
    fun malformedAndForeignPagesFailBeforeCreatingAnyKey() = withDatabase { f ->
        val valid = f.page(listOf(f.event()))
        val invalid =
            listOf(
                valid.copy(owner = valid.owner.copy(accountId = historyId(400))),
                valid.copy(owner = valid.owner.copy(sessionId = "replaced")),
                valid.copy(roomId = "!foreign:test"),
                valid.copy(deviceId = historyId(401)),
                valid.copy(start = ""),
                valid.copy(next = valid.start),
                valid.copy(next = "x".repeat(4097)),
                valid.copy(events = listOf(f.event(), f.event())),
                valid.copy(events = (1..21).map(f::event)),
                valid.copy(events = listOf(f.event().copy(timestamp = -1))),
            )
        for (page in invalid) assertIs<AppResult.Failed>(
            f.repository().stage(f.access, f.traversal, null, page)
        )
        assertNull(f.local.keys.read())
        assertNull(f.progress())
    }

    @Test
    fun changedMetadataRejectsTheWholeBatchAndWrongExpectedProgressCannotAdvance() =
        withDatabase { f ->
            f.stage(listOf(f.event()))
            val before = f.raw()
            val progress = requireNotNull(f.progress())
            for (changed in
                listOf(
                    f.event().copy(sender = "@foreign:test"),
                    f.event().copy(timestamp = 22),
                    f.event().copy(type = "m.room.message"),
                )) {
                val page = f.page(listOf(f.event(2), changed), progress.next, "advanced")
                assertIs<AppResult.Failed>(
                    f.repository().stage(f.access, f.traversal, progress, page)
                )
                assertEquals(before, f.raw())
            }
            assertEquals(
                AppResult.Success(false),
                f.repository().stage(f.access, f.traversal, null, f.page(listOf(f.event(2)))),
            )
            assertEquals(before, f.raw())
            f.stage(emptyList(), null)
            assertIs<AppResult.Failed>(
                f.repository().stage(f.access, f.traversal, f.progress(), f.page(emptyList()))
            )
        }

    @Test
    fun competingWritersKeepExactlyOnePageAndTheLoserCanContinueFromItsObservedProgress() =
        withDatabase { f ->
            coroutineScope {
                f.stage(emptyList())
                val previous = requireNotNull(f.progress())
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var reads = 0
                val raced =
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
                val writes =
                    (1..2).map { i ->
                        async {
                            f.repository(raced)
                                .stage(
                                    f.access,
                                    f.traversal,
                                    previous,
                                    f.page(listOf(f.event(i)), previous.next, "position-$i"),
                                )
                        }
                    }
                entered.await()
                release.complete(Unit)
                val results = writes.awaitAll().map(f::success)
                assertEquals(1, results.count { it })
                assertEquals(1, results.count { !it })
                assertEquals(1, f.progress()!!.references)
                f.stage(listOf(f.event(1), f.event(2)), "after-race")
                assertEquals(
                    setOf(f.event(1), f.event(2)),
                    f.read().events.map { it.event }.toSet(),
                )
            }
        }

    @Test
    fun lostCommitResponseAndCancellationRetainPageReferencesAndProgressTogether() =
        withDatabase { f ->
            f.stage(emptyList())
            for (cancel in listOf(false, true)) {
                val previous = requireNotNull(f.progress())
                val i = if (cancel) 2 else 1
                val failing =
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
                            if (cancel)
                                throw CancellationException("Fixture cancelled after commit")
                            error("Fixture lost commit response")
                        }
                    }
                val operation = suspend {
                    f.repository(failing)
                        .stage(
                            f.access,
                            f.traversal,
                            previous,
                            f.page(listOf(f.event(i)), previous.next, "retained-$i"),
                        )
                }
                if (cancel) assertFailsWith<CancellationException> { operation() }
                else assertIs<AppResult.Failed>(operation())
                f.local.reopen()
                assertEquals("retained-$i", f.progress()!!.next)
                assertTrue(f.event(i) in f.read().events.map { it.event })
            }
            assertEquals(2, f.progress()!!.references)
        }

    @Test
    fun lostInitialPageResponseRetainsItsReferencesAndSharesTheOriginalHistoryKey() =
        withDatabase { f ->
            val failing =
                object : ReplicaStore by f.local.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertTrue(
                            f.local.database.replicas.replace(
                                scope,
                                partition,
                                expected,
                                snapshot,
                                wake,
                            )
                        )
                        error("Fixture lost initial commit response")
                    }
                }
            assertIs<AppResult.Failed>(
                f.repository(failing).stage(f.access, f.traversal, null, f.page(listOf(f.event())))
            )
            val key = f.local.keys.read()
            f.local.reopen()
            assertEquals("next-1", f.progress()!!.next)
            assertEquals(listOf(f.event()), f.read().events.map { it.event })
            f.local.merge(f.local.event())
            assertEquals(key, f.local.keys.read())
            assertEquals(f.local.event().text, f.local.read().messages.single().text)
            assertEquals(listOf(f.event()), f.read().events.map { it.event })
        }

    @Test
    fun accountReplacementBeforeCommitCannotAdvanceEitherAccount() = withDatabase { f ->
        f.stage(listOf(f.event()))
        val previous = requireNotNull(f.progress())
        val replacement = f.access.copy(sessionId = "foreign-login", accountId = historyId(999))
        val switching =
            object : ReplicaStore by f.local.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.local.activate(replacement)
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
            f.repository(switching)
                .stage(
                    f.access,
                    f.traversal,
                    previous,
                    f.page(listOf(f.event(2)), previous.next, "blocked"),
                ),
        )
        assertNull(f.progress(replacement))
        f.local.activate()
        assertEquals(previous, f.progress())
        assertEquals(listOf(f.event()), f.read().events.map { it.event })
    }

    @Test
    fun traversalOnlyRecordsProtectAMissingSharedKeyIncludingInactiveAccounts() =
        withDatabase { f ->
            f.stage(listOf(f.event()))
            assertNull(
                f.local.database.replicas.checkpoint(
                    historyScope(f.access),
                    historyPartition(f.access, f.local.conversation),
                )
            )
            val before = f.raw()
            f.local.keys.write(null)
            val replacement = f.access.copy(sessionId = "another-login", accountId = historyId(998))
            f.local.activate(replacement)
            val page =
                f.page(emptyList())
                    .copy(
                        owner =
                            f.owner.copy(
                                sessionId = replacement.sessionId,
                                accountId = replacement.accountId,
                            )
                    )
            assertIs<AppResult.Failed>(f.repository().stage(replacement, f.traversal, null, page))
            assertNull(f.local.keys.read())
            f.local.activate()
            assertEquals(before, f.raw())
        }

    @Test
    fun copiedRowsAndChangedIndexesCannotPublishReferences() = withDatabase { f ->
        f.stage(listOf(f.event()))
        for (kind in 0..3) {
            val altered =
                object : ReplicaStore by f.local.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) =
                        f.local.database.replicas.observeQueries(scope, partition, queries).map {
                            page ->
                            val row = page.pages.single().single()
                            val changed =
                                when (kind) {
                                    0 -> row.copy(sortKey = "0000000000000001/" + row.id)
                                    1 -> row.copy(id = "r-" + "f".repeat(64))
                                    2 -> row.copy(payload = row.payload!!.dropLast(1) + "?")
                                    else -> row.copy(tags = emptySet())
                                }
                            page.copy(pages = listOf(listOf(changed)))
                        }
                }
            assertIs<AppResult.Failed>(
                f.repository(altered).observeReferences(f.access, f.traversal).first()
            )
        }
        assertIs<AppResult.Failed>(
            f.repository().progress(f.access, f.traversal.copy(roomId = "!different:test"))
        )
        assertNull(
            f.success(
                f.repository().progress(f.access, f.traversal.copy(deviceId = historyId(555)))
            )
        )
    }

    @Test
    fun sourceMutationAndCancelledPendingAcquisitionDoNotLoseOrAddReferences() = withDatabase { f ->
        coroutineScope {
            for (cancel in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val source = mutableListOf(f.event())
                val waiting =
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
                val previous = f.progress()
                val pending = async {
                    f.repository(waiting)
                        .stage(
                            f.access,
                            f.traversal,
                            previous,
                            f.page(source, previous?.next, "mutation-${if (cancel) 2 else 1}"),
                        )
                }
                entered.await()
                source.clear()
                if (cancel) pending.cancelAndJoin()
                else {
                    release.complete(Unit)
                    assertTrue(f.success(pending.await()))
                }
                assertEquals(listOf(f.event()), f.read().events.map { it.event })
                assertEquals("mutation-1", f.progress()!!.next)
            }
        }
    }

    private fun withDatabase(block: suspend (CollaborationHistoryTraversalFixture) -> Unit) =
        runTest {
            val fixture = CollaborationHistoryTraversalFixture(databaseOwner(), purpose)
            try {
                fixture.local.activate()
                block(fixture)
            } finally {
                fixture.local.close()
            }
        }
}
