package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceHistorySnapshotStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absentAndInvalidInputsNeverCreateAContentKey() = withDatabase { f ->
        val absent = f.snapshot("\$missing")
        assertNull(absent.revision)
        assertEquals(
            listOf(CollaborationSpaceHistorySnapshotEvent("\$missing", null)),
            absent.events,
        )
        for (ids in
            listOf(
                emptyList(),
                List(17) { "${'$'}event-$it" },
                listOf("\$same", "\$same"),
                listOf("bad event"),
            )) {
            assertIs<AppResult.Failed>(f.repository.snapshot(f.access, f.conversation, f.room, ids))
        }
        assertIs<AppResult.Failed>(
            f.repository.snapshot(f.access, f.conversation, "bad room", listOf("\$event"))
        )
        assertNull(f.keys.read())
        assertNull(f.raw().checkpoint)
    }

    @Test
    fun indexedBatchesPreserveInputOrderAndCanonicalIdentityBeyondAliasWindow() =
        withDatabase { f ->
            val first = f.event()
            f.merge(first)
            for (batch in
                (1..100)
                    .map {
                        first.copy(eventId = "${'$'}alias-$it", timestamp = first.timestamp - it)
                    }
                    .chunked(16)) f.merge(*batch.toTypedArray())
            val expected = assertNotNull(f.lookup())
            assertEquals(first.eventId, expected.primaryEventId)
            assertEquals(first.timestamp, expected.timestamp)
            val expectedRevision = f.read().revision
            var reads = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ): ReplicaPage {
                        reads++
                        assertTrue(ids.size in 1..32)
                        return f.database.replicas.workingDocuments(scope, partition, ids)
                    }

                    override suspend fun document(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): ReplicaDocument? = error("No individual reads")

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
                    ): Flow<ReplicaQueryPage> = error("No history scan")
                }
            f.repository = f.repository(store)
            val ids = (100 downTo 85).map { "${'$'}alias-$it" }
            val snapshot = f.snapshot(*ids.toTypedArray())
            assertEquals(ids, snapshot.events.map { it.eventId })
            assertTrue(snapshot.events.all { it.original == expected })
            assertEquals(2, reads)
            assertEquals(expectedRevision, snapshot.revision)
        }

    @Test
    fun replacementsResolveTheCurrentOriginalIncludingRejectedAndEarlierEdits() =
        withDatabase { f ->
            val first = f.event()
            val alias = first.copy(eventId = "\$alias", timestamp = first.timestamp + 1)
            f.merge(first, alias)
            val earlier = edit(first)
            val newest = edit(alias, 2)
            val rejected = edit(first, 3).copy(sender = "@other:example.test")
            f.edits(earlier, newest, rejected)
            val expected = assertNotNull(f.lookup())
            assertEquals(newest, expected.latestEdit?.edit)
            val snapshot =
                f.snapshot(
                    rejected.eventId,
                    earlier.eventId,
                    newest.eventId,
                    first.eventId,
                    alias.eventId,
                )
            assertTrue(snapshot.events.all { it.original == expected })
            assertEquals(first.eventId, expected.primaryEventId)
            assertEquals(first.contentHash, expected.contentHash)
        }

    @Test
    fun redactedEditsAndTombstonesCannotResurrectOriginalText() = withDatabase { f ->
        val first = f.event()
        val replacement = edit(first)
        f.merge(first)
        f.edits(replacement)
        f.merge(CollaborationHistoryChange.Redacted(replacement.eventId, first.sender))
        assertEquals(f.lookup(), f.snapshot(replacement.eventId).events.single().original)
        f.merge(
            CollaborationHistoryChange.Redacted(first.eventId, first.sender),
            CollaborationHistoryChange.Redacted("\$unseen", first.sender),
        )
        val snapshot = f.snapshot(first.eventId, replacement.eventId, "\$unseen", "\$absent")
        assertTrue(
            snapshot.events.take(2).all {
                it.original?.redacted == true && it.original?.text == null
            }
        )
        assertTrue(snapshot.events.drop(2).all { it.original == null })
        assertNotNull(snapshot.revision)
    }

    @Test
    fun hiddenOriginalsRemainAvailableForPreviewRemovalWithoutChangingStorage() =
        withDatabase { f ->
            f.merge(f.event())
            val original = assertNotNull(f.lookup())
            val raw = assertNotNull(f.raw().checkpoint)
            CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { content, index ->
                val codec =
                    CollaborationHistoryCodec(
                        f.access,
                        f.conversation,
                        f.room,
                        f.cipher,
                        content,
                        index,
                    )
                val previous = codec.checkpoint(raw)
                val document =
                    codec.message(
                        raw.handle.generation,
                        raw.revision + 1,
                        original.copy(locallyHidden = true),
                    )
                assertTrue(
                    f.database.replicas.commit(
                        raw.handle,
                        raw.revision,
                        codec.checkpoint(raw.handle.generation, raw.revision + 1, previous.count),
                        listOf(document),
                        true,
                    )
                )
            }
            val before = f.raw()
            val snapshot = f.snapshot(f.event().eventId)
            assertTrue(assertNotNull(snapshot.events.single().original).locallyHidden)
            assertEquals(before, f.raw())
            assertEquals(0, f.read().messages.size)
        }

    @Test
    fun legacyReplacementAliasesRequireTheirAuthenticatedRevision() = withDatabase { f ->
        val first = f.event()
        val replacement = edit(first)
        f.merge(first)
        f.edits(replacement)
        f.removeDocument { it.id.startsWith("p-") }
        assertEquals(f.lookup(), f.snapshot(replacement.eventId).events.single().original)
        f.removeDocument { it.id.startsWith("r-") }
        assertIs<AppResult.Failed>(
            f.repository.snapshot(f.access, f.conversation, f.room, listOf(replacement.eventId))
        )
    }

    @Test
    fun sourceChangeBetweenIndexedBatchesReturnsChangedWithoutRetry() = withDatabase { f ->
        f.merge(f.event())
        var reads = 0
        val originalRepository = f.repository
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ): ReplicaPage {
                        reads++
                        if (reads == 2)
                            assertEquals(
                                AppResult.Success(true),
                                originalRepository.merge(
                                    f.access,
                                    f.conversation,
                                    f.room,
                                    listOf(f.event(2)),
                                ),
                            )
                        return f.database.replicas.workingDocuments(scope, partition, ids)
                    }
                }
            )
        assertEquals(
            AppResult.Success(null),
            f.repository.snapshot(f.access, f.conversation, f.room, listOf(f.event().eventId)),
        )
        assertEquals(2, reads)
        assertEquals(
            f.event().eventId,
            f.snapshot(f.event().eventId).events.single().original?.primaryEventId,
        )
    }

    @Test
    fun sourceChangeAfterDecodingCannotPublishAnOldSnapshot() = withDatabase { f ->
        f.merge(f.event())
        var checks = 0
        val originalRepository = f.repository
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        checks++
                        if (checks == 2)
                            assertEquals(
                                AppResult.Success(true),
                                originalRepository.merge(
                                    f.access,
                                    f.conversation,
                                    f.room,
                                    listOf(f.event(2)),
                                ),
                            )
                        return f.database.replicas.checkpoint(scope, partition)
                    }
                }
            )
        assertEquals(
            AppResult.Success(null),
            f.repository.snapshot(f.access, f.conversation, f.room, listOf(f.event().eventId)),
        )
        assertEquals(2, checks)
    }

    @Test
    fun operationalFailuresAreNotConvertedIntoContentionOrRetried() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        var reads = 0
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ): ReplicaPage {
                        reads++
                        throw IllegalStateException("Fixture storage failure")
                    }
                }
            )
        assertIs<AppResult.Failed>(
            f.repository.snapshot(f.access, f.conversation, f.room, listOf(f.event().eventId))
        )
        assertEquals(1, reads)
        assertEquals(before, f.raw())
    }

    @Test
    fun cancellationJoinsALateReadWithoutPublishingOrWriting() = withDatabase { f ->
        f.merge(f.event())
        val before = f.raw()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ): ReplicaPage =
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            f.database.replicas.workingDocuments(scope, partition, ids)
                        }
                }
            )
        coroutineScope {
            var published = false
            val work = launch {
                f.snapshot(f.event().eventId)
                published = true
            }
            entered.await()
            work.cancel()
            assertFalse(work.isCompleted)
            release.complete(Unit)
            work.join()
            assertFalse(published)
            assertTrue(work.isCancelled)
        }
        assertEquals(before, f.raw())
    }

    @Test
    fun mutableCallerInputsAreCapturedBeforeTheFirstStorageSuspension() = withDatabase { f ->
        f.merge(f.event(), f.event(2))
        val submitted = mutableListOf(f.event().eventId)
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        submitted.clear()
                        submitted.add(f.event(2).eventId)
                        return f.database.replicas.checkpoint(scope, partition)
                    }
                }
            )
        val result =
            assertIs<AppResult.Success<CollaborationSpaceHistorySnapshot?>>(
                f.repository.snapshot(f.access, f.conversation, f.room, submitted)
            )
        assertEquals(f.event().eventId, assertNotNull(result.value).events.single().eventId)
    }

    @Test
    fun missingKeysAndWrongOwnershipNeverCreateReplacementKeysOrCrossNamespaces() =
        withDatabase { f ->
            f.merge(f.event())
            val before = f.raw()
            assertIs<AppResult.Failed>(
                f.repository.snapshot(
                    f.access,
                    f.conversation,
                    "!other:example.test",
                    listOf(f.event().eventId),
                )
            )
            val otherSpace = f.access.copy(spaceId = historyId(9998))
            assertNull(
                assertNotNull(
                        assertIs<AppResult.Success<CollaborationSpaceHistorySnapshot?>>(
                                f.repository.snapshot(
                                    otherSpace,
                                    f.conversation,
                                    f.room,
                                    listOf(f.event().eventId),
                                )
                            )
                            .value
                    )
                    .events
                    .single()
                    .original
            )
            f.keys.write(null)
            assertIs<AppResult.Failed>(
                f.repository.snapshot(f.access, f.conversation, f.room, listOf(f.event().eventId))
            )
            assertNull(f.keys.read())
            assertEquals(before, f.raw())
        }

    @Test
    fun alteredCiphertextOrMissingOriginalNeverReturnsAPartialBatch() = withDatabase { f ->
        f.merge(f.event(), f.event(2))
        val original = f.raw().documents.first { it.id.startsWith("m-") }
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun workingDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    ids: List<String>,
                ): ReplicaPage {
                    val page = f.database.replicas.workingDocuments(scope, partition, ids)
                    return page.copy(
                        documents =
                            page.documents.map {
                                if (it.id == original.id) it.copy(payload = "invalid") else it
                            }
                    )
                }
            }
        assertIs<AppResult.Failed>(
            f.repository(store)
                .snapshot(
                    f.access,
                    f.conversation,
                    f.room,
                    listOf(f.event().eventId, f.event(2).eventId),
                )
        )
        f.removeDocument { it.id == original.id }
        assertIs<AppResult.Failed>(
            f.repository.snapshot(
                f.access,
                f.conversation,
                f.room,
                listOf(f.event().eventId, f.event(2).eventId),
            )
        )
    }

    @Test
    fun snapshotsAndSemanticReplaysSurviveReopenWithoutAdvancingTheSource() = withDatabase { f ->
        val first = f.event()
        val replacement = edit(first)
        f.merge(first)
        f.edits(replacement)
        val before = f.raw()
        val snapshot = f.snapshot(first.eventId, replacement.eventId)
        val key = f.keys.read()
        f.reopen()
        f.merge(first)
        f.edits(replacement)
        assertEquals(snapshot, f.snapshot(first.eventId, replacement.eventId))
        assertEquals(before, f.raw())
        assertEquals(key, f.keys.read())
        assertFalse(snapshot.toString().contains(first.eventId))
        assertFalse(snapshot.events.first().toString().contains(first.text))
    }

    @Test
    fun companyHistoryWithMatchingIdsDoesNotBecomeGroupHistory() = withDatabase { f ->
        assertEquals(
            AppResult.Success(true),
            f.legacyRepository().merge(f.legacyAccess, f.conversation, f.room, listOf(f.event())),
        )
        val key = f.keys.read()
        val snapshot = f.snapshot(f.event().eventId)
        assertNull(snapshot.revision)
        assertNull(snapshot.events.single().original)
        assertNull(f.raw().checkpoint)
        assertEquals(key, f.keys.read())
    }

    @Test
    fun accountReplacementDuringAcquisitionCannotReturnThePreviousOwnersContent() =
        withDatabase { f ->
            f.merge(f.event())
            val before = f.raw()
            var reads = 0
            f.repository =
                f.repository(
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun workingDocuments(
                            scope: ReplicaScope,
                            partition: String,
                            ids: List<String>,
                        ): ReplicaPage {
                            reads++
                            if (reads == 2)
                                f.activate(
                                    f.access.copy(
                                        sessionId = "other-login",
                                        accountId = historyId(9900),
                                    )
                                )
                            return f.database.replicas.workingDocuments(scope, partition, ids)
                        }
                    }
                )
            assertEquals(
                AppResult.Success(null),
                f.repository.snapshot(f.access, f.conversation, f.room, listOf(f.event().eventId)),
            )
            assertEquals(2, reads)
            f.activate()
            assertEquals(before, f.raw())
            assertEquals(
                f.event().eventId,
                f.snapshot(f.event().eventId).events.single().original?.primaryEventId,
            )
        }

    @Test
    fun sourceCreatedDuringConfirmedAbsenceRequiresFreshObservation() = withDatabase { f ->
        var checks = 0
        val originalRepository = f.repository
        f.repository =
            f.repository(
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        checks++
                        if (checks == 2)
                            assertEquals(
                                AppResult.Success(true),
                                originalRepository.merge(
                                    f.access,
                                    f.conversation,
                                    f.room,
                                    listOf(f.event()),
                                ),
                            )
                        return f.database.replicas.checkpoint(scope, partition)
                    }
                }
            )
        assertEquals(
            AppResult.Success(null),
            f.repository.snapshot(f.access, f.conversation, f.room, listOf(f.event().eventId)),
        )
        assertEquals(2, checks)
        assertEquals(
            f.event().eventId,
            f.snapshot(f.event().eventId).events.single().original?.primaryEventId,
        )
    }

    private suspend fun CollaborationSpaceHistoryFixture.snapshot(vararg ids: String) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceHistorySnapshot?>>(
                    repository.snapshot(access, conversation, room, ids.toList())
                )
                .value
        )

    private suspend fun CollaborationSpaceHistoryFixture.edits(
        vararg edits: CollaborationMessageEdit
    ) {
        assertEquals(
            AppResult.Success(true),
            repository.mergeEdits(access, conversation, room, edits.toList()),
        )
    }

    private suspend fun CollaborationSpaceHistoryFixture.removeDocument(
        predicate: (ReplicaDocument) -> Boolean
    ) {
        val page = raw()
        val checkpoint = assertNotNull(page.checkpoint)
        val document = page.documents.single(predicate)
        CollaborationHistoryKeys(database.replicas, cipher, keys).use { content, index ->
            val codec =
                CollaborationHistoryCodec(access, conversation, room, cipher, content, index)
            val previous = codec.checkpoint(checkpoint)
            assertTrue(
                database.replicas.commit(
                    checkpoint.handle,
                    checkpoint.revision,
                    codec.checkpoint(
                        checkpoint.handle.generation,
                        checkpoint.revision + 1,
                        previous.count,
                    ),
                    listOf(
                        document.copy(
                            version = checkpoint.revision + 1,
                            payload = null,
                            tags = emptySet(),
                        )
                    ),
                    true,
                )
            )
        }
    }

    private fun edit(original: CollaborationHistoryChange.Text, index: Int = 1) =
        CollaborationMessageEdit(
            "${'$'}revision-$index",
            original.sender,
            original.eventId,
            original.timestamp + index * 100L,
            "Revised fictional text $index",
            (index + 100).toString(16).padStart(64, '0'),
            historyId(1000 + index),
            original.logicalId,
            original.replyToEventId,
            original.forwarded,
        )

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
