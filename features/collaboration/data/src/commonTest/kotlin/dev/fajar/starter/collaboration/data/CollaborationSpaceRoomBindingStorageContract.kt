package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceRoomBindingRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRoomBindingStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.local(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9900),
        )

    private fun binding(
        access: CollaborationSpaceLocalAccess,
        index: Int = 1,
        legacy: Boolean = false,
    ): CollaborationSpaceRoomBinding {
        val pair = listOf(access.accountId, historyId(10000 + index)).sorted()
        val createdAt = Instant.parse("2026-11-07T00:00:00.123456Z")
        val publishedAt = Instant.parse("2026-11-07T01:00:00.123456Z")
        val roomId = "!binding$index:matrix.example.test"
        val position =
            CollaborationOriginalConversationPosition(
                historyId(12000 + index),
                historyId(13000 + index),
            )
        val boundAt = Instant.parse("2026-11-06T01:00:00.654321Z")
        return CollaborationSpaceRoomBinding(
            CollaborationSpaceConversationMetadata(
                access.spaceId,
                historyId(11000 + index),
                pair[0],
                pair[1],
                pair[1],
                createdAt,
                0,
            ),
            CollaborationSpacePublishedRoom(
                roomId,
                "matrix.example.test",
                if (legacy) CollaborationSpaceRoomOrigin.LEGACY
                else CollaborationSpaceRoomOrigin.SPACE,
                publishedAt,
                if (legacy) CollaborationSpaceOriginalRoom(position, roomId, boundAt) else null,
            ),
            if (legacy)
                CollaborationSpaceOriginalRoomMetadata(
                    CollaborationOriginalConversation(
                        position,
                        pair[0],
                        pair[1],
                        pair[0],
                        Instant.parse("2026-11-06T00:00:00.000001Z"),
                        0,
                    ),
                    CollaborationOriginalRoom("matrix.example.test", roomId, boundAt, pair[1]),
                )
            else null,
        )
    }

    private fun CollaborationHistoryFixture.bindings(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceRoomBindingRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.rawBindings(
        access: CollaborationSpaceLocalAccess = local()
    ) =
        database.replicas
            .observe(spaceRoomBindingScope(access), spaceRoomBindingPartition(access), limit = 200)
            .first()

    private suspend fun CollaborationHistoryFixture.assertBinding(
        value: CollaborationSpaceRoomBinding,
        access: CollaborationSpaceLocalAccess = local(),
    ) {
        assertEquals(
            AppResult.Success(value),
            bindings().getByConversation(access, value.conversation.id),
        )
        assertEquals(
            AppResult.Success(value),
            bindings().findByRoom(access, value.room.serverName, value.room.roomId),
        )
    }

    @Test
    fun emptyReadsAreKeylessAndBothOriginsSurviveReopenWithoutLoginOrCompanyCoordinates() =
        withDatabase { f ->
            val first = binding(f.local())
            assertEquals(
                AppResult.Success(null),
                f.bindings().getByConversation(f.local(), first.conversation.id),
            )
            assertEquals(
                AppResult.Success(null),
                f.bindings().findByRoom(f.local(), first.room.serverName, first.room.roomId),
            )
            assertNull(f.keys.read())
            val original = binding(f.local(), 2, legacy = true)
            for (value in listOf(first, original)) assertEquals(
                AppResult.Success(true),
                f.bindings().retain(f.local(), value),
            )
            val before = f.rawBindings()
            assertEquals(4, before.documents.size)
            assertEquals(1, before.checkpoint?.revision)
            assertTrue(
                before.documents.all {
                    it.id.matches(Regex("b-[0-9a-f]{64}")) && it.tags == setOf(it.id)
                }
            )
            for (secret in
                listOf(
                    first.conversation.id,
                    first.room.roomId,
                    original.original!!.conversation.position.companyId,
                )) assertFalse(before.documents.toString().contains(secret))
            val renewed = f.access.copy(sessionId = "new-login", companyId = historyId(9902))
            f.reopen(renewed)
            for (value in listOf(first, original)) {
                f.assertBinding(value, f.local(renewed))
                assertEquals(AppResult.Success(true), f.bindings().retain(f.local(renewed), value))
            }
            assertEquals(before.documents, f.rawBindings(f.local(renewed)).documents)
            assertEquals(
                before.checkpoint?.payload,
                f.rawBindings(f.local(renewed)).checkpoint?.payload,
            )
            assertEquals(
                before.checkpoint?.revision,
                f.rawBindings(f.local(renewed)).checkpoint?.revision,
            )
        }

    @Test
    fun legacyRequiresTheExactOriginalPairCompanyRoomServerAndMicrosecondTimeBeforeAnyKeyCreation() =
        withDatabase { f ->
            val value = binding(f.local(), legacy = true)
            val original = assertNotNull(value.original)
            val reference = assertNotNull(value.room.original)
            val old = original.conversation
            val room = original.room
            val invalid =
                listOf(
                    value.copy(original = null),
                    value.copy(room = value.room.copy(original = null)),
                    value.copy(room = value.room.copy(origin = CollaborationSpaceRoomOrigin.SPACE)),
                    value.copy(
                        room =
                            value.room.copy(
                                original =
                                    reference.copy(
                                        position =
                                            reference.position.copy(companyId = f.local().spaceId)
                                    )
                            )
                    ),
                    value.copy(
                        original =
                            original.copy(
                                conversation =
                                    old.copy(
                                        position =
                                            old.position.copy(
                                                conversationId = value.conversation.id
                                            )
                                    )
                            )
                    ),
                    value.copy(
                        original =
                            original.copy(conversation = old.copy(firstAccountId = historyId(9910)))
                    ),
                    value.copy(
                        original =
                            original.copy(
                                conversation = old.copy(secondAccountId = historyId(9911))
                            )
                    ),
                    value.copy(
                        original =
                            original.copy(conversation = old.copy(createdBy = historyId(9912)))
                    ),
                    value.copy(original = original.copy(conversation = old.copy(version = 1))),
                    value.copy(
                        original =
                            original.copy(
                                room = room.copy(roomId = "!different:matrix.example.test")
                            )
                    ),
                    value.copy(
                        original =
                            original.copy(room = room.copy(serverName = "other.example.test"))
                    ),
                    value.copy(
                        original =
                            original.copy(
                                room =
                                    room.copy(
                                        boundAt = Instant.parse("2026-11-06T01:00:00.654322Z")
                                    )
                            )
                    ),
                    value.copy(
                        original = original.copy(room = room.copy(createdBy = historyId(9912)))
                    ),
                    value.copy(
                        room = value.room.copy(publishedAt = Instant.parse("2026-11-05T00:00:00Z"))
                    ),
                    value.copy(
                        room =
                            value.room.copy(
                                publishedAt = Instant.parse("2026-11-07T01:00:00.123456789Z")
                            )
                    ),
                    value.copy(conversation = value.conversation.copy(spaceId = historyId(9913))),
                )
            for (bad in invalid) assertIs<AppResult.Failed>(f.bindings().retain(f.local(), bad))
            val space = binding(f.local())
            assertIs<AppResult.Failed>(
                f.bindings().retain(f.local(), space.copy(original = original))
            )
            assertNull(f.keys.read())
            assertNull(f.rawBindings().checkpoint)
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
            f.assertBinding(value)
        }

    @Test
    fun immutableFactsAndRoomAliasesCannotOverwriteEitherIndex() = withDatabase { f ->
        val value = binding(f.local())
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        val before = f.rawBindings()
        assertEquals(
            AppResult.Success(false),
            f.bindings()
                .retain(
                    f.local(),
                    value.copy(
                        conversation =
                            value.conversation.copy(createdBy = value.conversation.firstAccountId)
                    ),
                ),
        )
        for (changed in
            listOf(
                value.copy(conversation = value.conversation.copy(id = historyId(9914))),
                value.copy(room = value.room.copy(roomId = "!different:matrix.example.test")),
                value.copy(room = value.room.copy(serverName = "other.example.test")),
            )) assertIs<AppResult.Failed>(f.bindings().retain(f.local(), changed))
        assertEquals(before, f.rawBindings())
        f.assertBinding(value)
        val otherServer =
            binding(f.local(), 2).copy(room = value.room.copy(serverName = "other.example.test"))
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), otherServer))
        f.assertBinding(otherServer)
        f.assertBinding(value)
        assertTrue(f.rawBindings().documents.containsAll(before.documents))
    }

    @Test
    fun exactLookupsUseOnlyBoundedPublishedIndexes() = withDatabase { f ->
        val first = binding(f.local(), legacy = true)
        val second = binding(f.local(), 2)
        listOf(first, second).forEach {
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), it))
        }
        var calls = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    calls++
                    assertEquals(2, queries.size)
                    assertTrue(
                        queries.all {
                            it.tag != null && it.limit == 2 && it.after == null && it.before == null
                        }
                    )
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }

                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("No partition scan")

                override suspend fun workingDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    ids: List<String>,
                ): ReplicaPage = error("No staging read")
            }
        assertEquals(
            AppResult.Success(first),
            f.bindings(store).getByConversation(f.local(), first.conversation.id),
        )
        assertEquals(
            AppResult.Success(second),
            f.bindings(store).findByRoom(f.local(), second.room.serverName, second.room.roomId),
        )
        assertEquals(2, calls)
    }

    @Test
    fun competingFirstAndAppendPublicationsRetainTheWinnerWithoutInternalRetry() =
        withDatabase { f ->
            val first = binding(f.local())
            val second = binding(f.local(), 2, legacy = true)
            val third = binding(f.local(), 3)
            var writes = 0
            val firstRace =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                    ): Boolean {
                        writes++
                        assertEquals(
                            AppResult.Success(true),
                            f.bindings().retain(f.local(), second),
                        )
                        return f.database.replicas.replace(scope, partition, expected, snapshot)
                    }
                }
            assertEquals(AppResult.Success(false), f.bindings(firstRace).retain(f.local(), first))
            assertEquals(1, writes)
            f.assertBinding(second)
            val appendRace =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                    ): Boolean {
                        writes++
                        assertEquals(2, documents.size)
                        assertTrue(publish)
                        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), third))
                        return f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                        )
                    }
                }
            assertEquals(AppResult.Success(false), f.bindings(appendRace).retain(f.local(), first))
            assertEquals(2, writes)
            assertEquals(
                AppResult.Success(null),
                f.bindings().getByConversation(f.local(), first.conversation.id),
            )
            listOf(second, third).forEach { f.assertBinding(it) }
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), first))
            listOf(first, second, third).forEach { f.assertBinding(it) }
        }

    @Test
    fun lostInsertAndAppendResponsesRecoverIdenticalCiphertextAfterProcessReopen() =
        withDatabase { f ->
            fun lossyStore(): ReplicaStore =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                    ): Boolean {
                        check(f.database.replicas.replace(scope, partition, expected, snapshot))
                        throw CancellationException("Lost publication response")
                    }

                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                    ): Boolean {
                        check(
                            f.database.replicas.commit(
                                handle,
                                expectedRevision,
                                checkpoint,
                                documents,
                                publish,
                            )
                        )
                        throw CancellationException("Lost append response")
                    }
                }
            for (index in 1..2) {
                val value = binding(f.local(), index, legacy = index == 2)
                assertFailsWith<CancellationException> {
                    f.bindings(lossyStore()).retain(f.local(), value)
                }
                val before = f.rawBindings()
                f.reopen()
                f.assertBinding(value)
                assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
                assertEquals(before.documents, f.rawBindings().documents)
                assertEquals(before.checkpoint?.payload, f.rawBindings().checkpoint?.payload)
            }
        }

    @Test
    fun replacementAccountAtEitherWriteBoundaryReceivesNoOldBinding() = withDatabase { f ->
        val foreign = f.access.copy(sessionId = "other-login", accountId = historyId(9920))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    f.activate(foreign)
                    return f.database.replicas.replace(scope, partition, expected, snapshot)
                }

                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    f.activate(foreign)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                    )
                }
            }
        val first = binding(f.local())
        assertEquals(AppResult.Success(false), f.bindings(store).retain(f.local(), first))
        assertNull(f.rawBindings(f.local(foreign)).checkpoint)
        f.activate()
        assertNull(f.rawBindings().checkpoint)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), first))
        val before = f.rawBindings()
        assertEquals(
            AppResult.Success(false),
            f.bindings(store).retain(f.local(), binding(f.local(), 2)),
        )
        assertNull(f.rawBindings(f.local(foreign)).checkpoint)
        f.activate()
        assertEquals(before, f.rawBindings())
    }

    @Test
    fun accountAudienceAndSpaceIsolateRoomAndConversationIndexes() = withDatabase { f ->
        val first = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), first))
        val before = f.rawBindings()
        val owners =
            listOf(
                f.access.copy(sessionId = "account", accountId = historyId(9921)),
                f.access.copy(sessionId = "audience", audience = "https://other.example.test/"),
                f.access,
            )
        for ((index, owner) in owners.withIndex()) {
            f.activate(owner)
            val access =
                if (index == 2) f.local(owner).copy(spaceId = historyId(9922)) else f.local(owner)
            assertEquals(
                AppResult.Success(null),
                f.bindings().getByConversation(access, first.conversation.id),
            )
            assertEquals(
                AppResult.Success(null),
                f.bindings().findByRoom(access, first.room.serverName, first.room.roomId),
            )
            val value = binding(access, legacy = true)
            assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
            f.assertBinding(value, access)
            assertTrue(
                f.rawBindings(access)
                    .documents
                    .map { it.id }
                    .toSet()
                    .intersect(before.documents.map { it.id }.toSet())
                    .isEmpty()
            )
            f.activate()
            assertEquals(before, f.rawBindings())
        }
    }

    @Test
    fun retainedBindingsFenceLostKeysAcrossAccountsAndOrdinaryHistoryWriters() = withDatabase { f ->
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        val before = f.rawBindings()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.bindings().getByConversation(f.local(), value.conversation.id))
        assertIs<AppResult.Failed>(f.bindings().retain(f.local(), value))
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, listOf(f.event()))
        )
        val foreign = f.access.copy(sessionId = "foreign", accountId = historyId(9923))
        f.activate(foreign)
        assertIs<AppResult.Failed>(f.bindings().retain(f.local(foreign), binding(f.local(foreign))))
        assertNull(f.keys.read())
        f.activate()
        assertEquals(before, f.rawBindings())
    }

    @Test
    fun existingHistoryKeepsItsKeysAndCiphertextWhenBindingsAreAdded() = withDatabase { f ->
        f.merge(f.event())
        val key = f.keys.read()
        val history = f.raw()
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        f.assertBinding(value)
        assertEquals(key, f.keys.read())
        assertEquals(history, f.raw())
        assertNotNull(f.lookup())
    }

    @Test
    fun incompleteOrTamperedComplementaryIndexesFailWithoutRepairingRecords() = withDatabase { f ->
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        val before = f.rawBindings()
        for (change in 0..5) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) =
                        f.database.replicas.observeQueries(scope, partition, queries).map { raw ->
                            val room = raw.pages[1].single()
                            raw.copy(
                                pages =
                                    listOf(
                                        raw.pages[0],
                                        when (change) {
                                            0 -> emptyList()
                                            1 -> listOf(room, room)
                                            2 -> listOf(room.copy(payload = "corrupt"))
                                            3 -> listOf(room.copy(version = 1))
                                            4 -> listOf(room.copy(tags = emptySet()))
                                            else -> listOf(room.copy(sortKey = "foreign"))
                                        },
                                    )
                            )
                        }
                }
            assertIs<AppResult.Failed>(
                f.bindings(store).getByConversation(f.local(), value.conversation.id)
            )
        }
        assertEquals(before, f.rawBindings())
        f.assertBinding(value)
    }

    @Test
    fun changedCheckpointAndOneSidedWriteInventoryCannotReplaceBindings() = withDatabase { f ->
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        val before = f.rawBindings()
        val checkpoint = assertNotNull(before.checkpoint)
        for (changed in
            listOf(
                checkpoint.copy(payload = "corrupt"),
                checkpoint.copy(payload = "x".repeat(16385)),
                checkpoint.copy(published = false),
                checkpoint.copy(revision = 1),
                checkpoint.copy(handle = checkpoint.handle.copy(generation = historyId(9924))),
                checkpoint.copy(handle = checkpoint.handle.copy(scope = ReplicaScope("foreign"))),
                checkpoint.copy(handle = checkpoint.handle.copy(partition = "foreign")),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ) = before.copy(checkpoint = changed)

                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) = flowOf(ReplicaPage(changed, before.documents.filter { it.id == id }))
                }
            assertIs<AppResult.Failed>(f.bindings(store).retain(f.local(), value))
            assertIs<AppResult.Failed>(
                f.bindings(store).getByConversation(f.local(), value.conversation.id)
            )
        }
        val missing =
            object : ReplicaStore by f.database.replicas {
                override suspend fun workingDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    ids: List<String>,
                ) = before.copy(documents = before.documents.take(1))
            }
        assertIs<AppResult.Failed>(f.bindings(missing).retain(f.local(), value))
        assertEquals(before, f.rawBindings())
    }

    @Test
    fun replacementAndCancellationDuringReadCannotReturnOldAccountFacts() = withDatabase { f ->
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    val page = f.database.replicas.observeQueries(scope, partition, queries).first()
                    f.activate(f.access.copy(sessionId = "foreign", accountId = historyId(9925)))
                    emit(page)
                }
            }
        assertIs<AppResult.Failed>(
            f.bindings(store).getByConversation(f.local(), value.conversation.id)
        )
        f.activate()
        val entered = CompletableDeferred<Unit>()
        val blocked =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) =
                    flow<ReplicaQueryPage> {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
            }
        val job =
            CoroutineScope(currentCoroutineContext()).async {
                f.bindings(blocked).findByRoom(f.local(), value.room.serverName, value.room.roomId)
            }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        f.assertBinding(value)
    }

    @Test
    fun invalidReadCoordinatesAndLocalScopesNeverCreateKeys() = withDatabase { f ->
        val value = binding(f.local())
        assertIs<AppResult.Failed>(f.bindings().getByConversation(f.local(), "invalid"))
        assertIs<AppResult.Failed>(
            f.bindings().findByRoom(f.local(), "https://matrix.example.test", value.room.roomId)
        )
        assertIs<AppResult.Failed>(
            f.bindings().findByRoom(f.local(), value.room.serverName, "not-a-room")
        )
        for (access in
            listOf(
                f.local().copy(sessionId = ""),
                f.local().copy(audience = "bad\n"),
                f.local().copy(accountId = "invalid"),
                f.local().copy(spaceId = "00000000-0000-0000-0000-000000000000"),
            )) {
            assertIs<AppResult.Failed>(
                f.bindings().getByConversation(access, value.conversation.id)
            )
            assertIs<AppResult.Failed>(f.bindings().retain(access, value))
        }
        assertNull(f.keys.read())
    }

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
