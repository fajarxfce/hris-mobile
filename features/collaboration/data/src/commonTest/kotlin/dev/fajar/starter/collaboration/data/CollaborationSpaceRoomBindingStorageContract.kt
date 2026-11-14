package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceRoomBindingRepository
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationHistorySignalRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

    private suspend fun CollaborationHistoryFixture.historySignal(
        access: CollaborationSpaceLocalAccess = local()
    ): String? =
        assertIs<AppResult.Success<String?>>(
                StoredCollaborationHistorySignalRepository(database.replicas)
                    .observe(access)
                    .first()
            )
            .value

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
    fun publishedBindingObservationStartsKeylessAndFollowsCreationAndRemoval() = withDatabase { f ->
        coroutineScope {
            val access = f.local()
            val value = binding(access, legacy = true)
            val values = Channel<AppResult<CollaborationSpaceRoomBinding?>>(8)
            val job = launch {
                f.bindings().observeByConversation(access, value.conversation.id).collect {
                    values.send(it)
                }
            }
            try {
                assertEquals(AppResult.Success(null), values.receive())
                assertNull(f.keys.read())
                assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
                assertEquals(AppResult.Success(value), values.receive())
                assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
                assertEquals(
                    AppResult.Success(true),
                    f.bindings().retain(access, binding(access, 2)),
                )
                assertTrue(
                    f.database.replicas.clear(
                        spaceRoomBindingScope(access),
                        spaceRoomBindingPartition(access),
                    )
                )
                // Identical retention and another conversation cannot add a duplicate value.
                assertEquals(AppResult.Success(null), values.receive())
            } finally {
                job.cancelAndJoin()
                values.close()
            }
        }
    }

    @Test
    fun publishedBindingObservationRetainsBothOriginsAcrossReopenAndRenewedLogin() =
        withDatabase { f ->
            val access = f.local()
            val values = listOf(binding(access), binding(access, 2, legacy = true))
            for (value in values) assertEquals(
                AppResult.Success(true),
                f.bindings().retain(access, value),
            )
            val before = f.rawBindings()
            val renewed =
                f.access.copy(sessionId = "binding-observer-renewed", companyId = historyId(9955))
            f.reopen(renewed)
            for (value in values) {
                assertEquals(
                    AppResult.Success(value),
                    f.bindings()
                        .observeByConversation(f.local(renewed), value.conversation.id)
                        .first(),
                )
                assertEquals(
                    AppResult.Success(null),
                    f.bindings().observeByConversation(access, value.conversation.id).first(),
                )
            }
            assertEquals(before.documents, f.rawBindings(f.local(renewed)).documents)
            assertEquals(
                before.checkpoint?.payload,
                f.rawBindings(f.local(renewed)).checkpoint?.payload,
            )
        }

    @Test
    fun publishedBindingObservationRecoversFromMalformedPairOnlyAfterAnotherLocalPublication() =
        withDatabase { f ->
            coroutineScope {
                val access = f.local()
                val value = binding(access, legacy = true)
                assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
                val malformed = MutableStateFlow(true)
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observeQueries(
                            scope: ReplicaScope,
                            partition: String,
                            queries: List<ReplicaQuery>,
                        ) =
                            f.database.replicas.observeQueries(scope, partition, queries).map { raw
                                ->
                                if (malformed.value)
                                    raw.copy(pages = listOf(raw.pages[0], emptyList()))
                                else raw
                            }
                    }
                val values = Channel<AppResult<CollaborationSpaceRoomBinding?>>(8)
                val job = launch {
                    f.bindings(store).observeByConversation(access, value.conversation.id).collect {
                        values.send(it)
                    }
                }
                try {
                    assertIs<AppResult.Failed>(values.receive())
                    val before = f.rawBindings()
                    malformed.value = false
                    assertEquals(
                        AppResult.Success(true),
                        f.bindings().retain(access, binding(access, 2)),
                    )
                    assertEquals(AppResult.Success(value), values.receive())
                    val after = f.rawBindings()
                    assertTrue(after.documents.containsAll(before.documents))
                    f.assertBinding(value)
                } finally {
                    job.cancelAndJoin()
                    values.close()
                }
            }
        }

    @Test
    fun publishedBindingObservationRejectsReplacementDuringComplementaryIndexRead() =
        withDatabase { f ->
            val value = binding(f.local(), legacy = true)
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) = flow {
                        val page =
                            f.database.replicas.observeQueries(scope, partition, queries).first()
                        f.activate(
                            f.access.copy(
                                sessionId = "observed-foreign",
                                accountId = historyId(9956),
                            )
                        )
                        emit(page)
                    }
                }
            // Inactive scopes publish absence. An older in-flight lookup may fail before the
            // new checkpoint cancels it, but neither path may publish the previous account's data.
            val absent =
                f.bindings(store)
                    .observeByConversation(f.local(), value.conversation.id)
                    .onEach { result ->
                        when (result) {
                            is AppResult.Success -> assertNull(result.value)
                            is AppResult.Failed ->
                                assertEquals(FailureKind.Storage, result.failure.kind)
                        }
                    }
                    .first { it is AppResult.Success }
            assertEquals(AppResult.Success(null), absent)
            f.activate()
            f.assertBinding(value)
        }

    @Test
    fun aNewPublishedCheckpointCancelsTheOlderBindingReadBeforePublishing() = withDatabase { f ->
        val access = f.local()
        val value = binding(access, legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
        val entered = CompletableDeferred<Unit>()
        val retired = CompletableDeferred<Unit>()
        var reads = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) = flow {
                    reads++
                    if (reads == 1) {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            retired.complete(Unit)
                        }
                    }
                    emitAll(f.database.replicas.observeQueries(scope, partition, queries))
                }
            }
        val result =
            CoroutineScope(currentCoroutineContext()).async {
                f.bindings(store).observeByConversation(access, value.conversation.id).first()
            }
        entered.await()
        assertEquals(AppResult.Success(true), f.bindings().retain(access, binding(access, 2)))
        assertEquals(AppResult.Success(value), result.await())
        assertTrue(retired.isCompleted)
        assertEquals(2, reads)
        f.assertBinding(value)
    }

    @Test
    fun cancellingPublishedBindingObservationJoinsItsPendingPairRead() = withDatabase { f ->
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) =
                    flow<ReplicaQueryPage> {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
            }
        val result =
            CoroutineScope(currentCoroutineContext()).async {
                f.bindings(store).observeByConversation(f.local(), value.conversation.id).first()
            }
        entered.await()
        result.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertFailsWith<CancellationException> { result.await() }
        f.assertBinding(value)
    }

    @Test
    fun invalidPublishedBindingObservationDoesNotCreateKeysOrRepairLostOnes() = withDatabase { f ->
        val access = f.local()
        val value = binding(access)
        for ((owner, id) in
            listOf(
                access to "invalid",
                access.copy(spaceId = "invalid") to value.conversation.id,
            )) {
            assertIs<AppResult.Failed>(f.bindings().observeByConversation(owner, id).first())
            assertNull(f.keys.read())
        }
        assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
        val before = f.rawBindings()
        f.keys.write(null)
        assertIs<AppResult.Failed>(
            f.bindings().observeByConversation(access, value.conversation.id).first()
        )
        assertNull(f.keys.read())
        assertEquals(before, f.rawBindings())
    }

    @Test
    fun newBindingRearmsRecoveryAfterTheConsumerFinalInventoryRead() = withDatabase { f ->
        val key = CollaborationSpaceRecoveryWake.WORK_KEY
        val first = binding(f.local())
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), first))
        val consumed = assertNotNull(f.database.workWakes.get(key))
        assertTrue(consumed.pending)
        val inventory =
            assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                    f.bindings().page(f.local())
                )
                .value
        assertEquals(listOf(first), inventory.bindings)
        assertNull(inventory.next)

        val second = binding(f.local(), 2, legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), second))
        val pending = assertNotNull(f.database.workWakes.get(key))
        assertTrue(pending.pending)
        assertNotEquals(consumed.generation, pending.generation)
        assertFalse(f.database.workWakes.acknowledge(key, consumed.generation))
        assertEquals(pending, f.database.workWakes.get(key))
        f.assertBinding(first)
        f.assertBinding(second)

        assertTrue(f.database.workWakes.acknowledge(key, pending.generation))
        val third = binding(f.local(), 3)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), third))
        val latest = assertNotNull(f.database.workWakes.get(key))
        assertTrue(latest.pending)
        assertNotEquals(pending.generation, latest.generation)
        val raw = f.rawBindings()
        val signal = f.historySignal()
        for (value in listOf(first, second, third)) {
            f.assertBinding(value)
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        }
        assertEquals(latest, f.database.workWakes.get(key))
        assertEquals(raw, f.rawBindings())
        assertEquals(signal, f.historySignal())
    }

    @Test
    fun fullRecoveryCapacityCannotPublishTheFirstBinding() = withDatabase { f ->
        repeat(32) { index ->
            assertNotNull(f.database.workWakes.request("occupied.$index", "generation-$index", 32))
        }
        val value = binding(f.local())
        assertEquals(AppResult.Success(false), f.bindings().retain(f.local(), value))
        assertNull(f.historySignal())
        assertNull(f.rawBindings().checkpoint)
        assertTrue(f.rawBindings().documents.isEmpty())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        repeat(32) { index ->
            assertEquals(
                "generation-$index",
                f.database.workWakes.get("occupied.$index")?.generation,
            )
        }
    }

    @Test
    fun fullRecoveryCapacityPreservesBindingsCreatedBeforeProducerWakes() = withDatabase { f ->
        val historicalStore =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertEquals(2, mutations.size)
                    val original = assertIs<ReplicaMutation.Replace>(mutations.first())
                    // Recreate the previous binding protocol, before either signal or wake.
                    return f.database.replicas.replace(
                        scope,
                        original.partition,
                        original.expected,
                        original.snapshot,
                    )
                }
            }
        val historical = binding(f.local(), legacy = true)
        assertEquals(
            AppResult.Success(true),
            f.bindings(historicalStore).retain(f.local(), historical),
        )
        val before = f.rawBindings()
        assertNull(f.historySignal())
        repeat(32) { index ->
            assertNotNull(f.database.workWakes.request("occupied.$index", "generation-$index", 32))
        }
        assertEquals(
            AppResult.Success(false),
            f.bindings().retain(f.local(), binding(f.local(), 2)),
        )
        assertEquals(before, f.rawBindings())
        assertNull(f.historySignal())
        f.assertBinding(historical)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), historical))
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
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
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        writes++
                        assertEquals(2, mutations.size)
                        assertIs<ReplicaMutation.Replace>(mutations[0])
                        assertEquals(
                            AppResult.Success(true),
                            f.bindings().retain(f.local(), second),
                        )
                        return f.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            assertEquals(AppResult.Success(false), f.bindings(firstRace).retain(f.local(), first))
            assertEquals(1, writes)
            f.assertBinding(second)
            val appendRace =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        writes++
                        assertEquals(2, mutations.size)
                        val bindingMutation = assertIs<ReplicaMutation.Commit>(mutations[0])
                        assertEquals(2, bindingMutation.documents.size)
                        assertTrue(bindingMutation.publish)
                        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), third))
                        return f.database.replicas.mutate(scope, mutations, wake)
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
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertEquals(2, mutations.size)
                        check(f.database.replicas.mutate(scope, mutations, wake))
                        throw CancellationException("Lost binding and signal publication response")
                    }
                }
            for (index in 1..2) {
                val value = binding(f.local(), index, legacy = index == 2)
                assertFailsWith<CancellationException> {
                    f.bindings(lossyStore()).retain(f.local(), value)
                }
                val before = f.rawBindings()
                val signal = f.historySignal()
                f.reopen()
                f.assertBinding(value)
                assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
                assertEquals(signal, f.historySignal())
                assertEquals(before.documents, f.rawBindings().documents)
                assertEquals(before.checkpoint?.payload, f.rawBindings().checkpoint?.payload)
            }
        }

    @Test
    fun replacementAccountAtEitherWriteBoundaryReceivesNoOldBinding() = withDatabase { f ->
        val foreign = f.access.copy(sessionId = "other-login", accountId = historyId(9920))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertEquals(2, mutations.size)
                    f.activate(foreign)
                    return f.database.replicas.mutate(scope, mutations, wake)
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

    @Test
    fun inventoryStartsKeylessAndRejectsInvalidBoundsBeforeReadingStorage() = withDatabase { f ->
        assertEquals(
            AppResult.Success(CollaborationSpaceRoomBindingPage(null, emptyList(), null, false)),
            f.bindings().page(f.local()),
        )
        val unread =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("Invalid input reached storage")
            }
        for (limit in listOf(0, -1, 51, Int.MAX_VALUE)) {
            assertIs<AppResult.Failed>(f.bindings(unread).page(f.local(), limit = limit))
        }
        val boundary =
            CollaborationSpaceRoomBindingBoundary(
                f.local().audience,
                f.local().accountId,
                f.local().spaceId,
                historyId(14000),
                0,
            )
        val cursor = CollaborationSpaceRoomBindingCursor(boundary, "b-" + "0".repeat(64))
        val invalid =
            listOf(
                cursor.copy(afterId = "invalid"),
                cursor.copy(boundary = boundary.copy(audience = "https://foreign.example/")),
                cursor.copy(boundary = boundary.copy(accountId = historyId(14001))),
                cursor.copy(boundary = boundary.copy(spaceId = historyId(14002))),
                cursor.copy(boundary = boundary.copy(generation = "invalid")),
                cursor.copy(boundary = boundary.copy(revision = -1)),
                cursor.copy(boundary = boundary.copy(revision = 9_007_199_254_740_992L)),
            )
        for (after in invalid) assertIs<AppResult.Failed>(f.bindings(unread).page(f.local(), after))
        assertNull(f.keys.read())
    }

    @Test
    fun inventoryAdvancesOverRoomOnlyPagesAndReturnsEachImmutableBindingOnce() = withDatabase { f ->
        val values = (1..6).map { binding(f.local(), it, legacy = it % 2 == 0) }
        for (value in values) assertEquals(
            AppResult.Success(true),
            f.bindings().retain(f.local(), value),
        )
        val before = f.rawBindings()
        val found = mutableListOf<CollaborationSpaceRoomBinding>()
        var after: CollaborationSpaceRoomBindingCursor? = null
        var pages = 0
        var empty = 0
        do {
            val page =
                assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                        f.bindings().page(f.local(), after, limit = 1)
                    )
                    .value
            assertEquals(5, page.boundary?.revision)
            assertFalse(page.hasNewerBindings)
            found += page.bindings
            if (page.bindings.isEmpty()) empty++
            page.next?.let { next -> after?.let { assertTrue(next.afterId > it.afterId) } }
            after = page.next
            pages++
            assertTrue(pages <= 12)
        } while (after != null)
        assertEquals(12, pages)
        assertEquals(6, empty)
        assertEquals(values.toSet(), found.toSet())
        assertEquals(values.size, found.size)
        assertEquals(before, f.rawBindings())
    }

    @Test
    fun inventoryCursorSurvivesReopenAndSameAccountLoginReplacement() = withDatabase { f ->
        val values = (1..4).map { binding(f.local(), it, legacy = true) }
        for (value in values) assertEquals(
            AppResult.Success(true),
            f.bindings().retain(f.local(), value),
        )
        val first =
            assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                    f.bindings().page(f.local(), limit = 3)
                )
                .value
        val cursor = assertNotNull(first.next)
        val before = f.rawBindings()
        val renewed = f.access.copy(sessionId = "inventory-renewed", companyId = historyId(14003))
        f.reopen(renewed)
        val rest =
            assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                    f.bindings().page(f.local(renewed), cursor, limit = 50)
                )
                .value
        assertEquals(first.boundary, rest.boundary)
        assertNull(rest.next)
        assertFalse(rest.hasNewerBindings)
        assertEquals(values.toSet(), (first.bindings + rest.bindings).toSet())
        assertEquals(values.size, first.bindings.size + rest.bindings.size)
        assertEquals(before.documents, f.rawBindings(f.local(renewed)).documents)
    }

    @Test
    fun inventoryLookaheadRecognizesAnExactlyFullTailWithoutAnotherPage() = withDatabase { f ->
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        var scans = 0
        var pairs = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> {
                    scans++
                    assertEquals(3, limit)
                    return f.database.replicas.observe(scope, partition, after, limit)
                }

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    pairs++
                    assertEquals(2, queries.size)
                    assertTrue(queries.all { it.limit == 2 && it.tag != null })
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }
            }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                    f.bindings(store).page(f.local(), limit = 2)
                )
                .value
        assertEquals(listOf(value), result.bindings)
        assertNull(result.next)
        assertEquals(1, scans)
        assertEquals(2, pairs)
    }

    @Test
    fun inventorySeparatesConcurrentAppendsOnBothSidesOfTheOpaqueCursor() = withDatabase { f ->
        // Fictional fixed key makes index ordering reproducible across all database platforms.
        f.keys.write(Base64.encode(ByteArray(64) { it.toByte() }))
        val original = (1..4).map { binding(f.local(), it, legacy = true) }
        for (value in original) assertEquals(
            AppResult.Success(true),
            f.bindings().retain(f.local(), value),
        )
        val first =
            assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                    f.bindings().page(f.local(), limit = 3)
                )
                .value
        val firstCursor = assertNotNull(first.next)
        val added = (5..12).map { binding(f.local(), it, legacy = it % 2 == 0) }
        for (value in added) assertEquals(
            AppResult.Success(true),
            f.bindings().retain(f.local(), value),
        )
        val newerRows = f.rawBindings().documents.filter { it.version > 3 }
        assertTrue(newerRows.any { it.id < firstCursor.afterId })
        assertTrue(newerRows.any { it.id > firstCursor.afterId })
        var after: CollaborationSpaceRoomBindingCursor? = firstCursor
        val found = first.bindings.toMutableList()
        var pages = 0
        do {
            val page =
                assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                        f.bindings().page(f.local(), after, limit = 3)
                    )
                    .value
            assertEquals(first.boundary, page.boundary)
            assertTrue(page.hasNewerBindings)
            found += page.bindings
            after = page.next
            assertTrue(++pages <= 8)
        } while (after != null)
        assertEquals(original.toSet(), found.toSet())
        assertEquals(original.size, found.size)
        val fresh =
            assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                    f.bindings().page(f.local(), limit = 50)
                )
                .value
        assertEquals((original + added).toSet(), fresh.bindings.toSet())
        assertEquals(11, fresh.boundary?.revision)
        assertFalse(fresh.hasNewerBindings)
        assertNull(fresh.next)
    }

    @Test
    fun inventoryRejectsADeletedOrReplacedContinuationNamespaceWithoutRestartingIt() =
        withDatabase { f ->
            val original = binding(f.local(), legacy = true)
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), original))
            val first =
                assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                        f.bindings().page(f.local(), limit = 1)
                    )
                    .value
            val cursor = assertNotNull(first.next)
            assertTrue(
                f.database.replicas.clear(
                    spaceRoomBindingScope(f.local()),
                    spaceRoomBindingPartition(f.local()),
                )
            )
            assertIs<AppResult.Failed>(f.bindings().page(f.local(), cursor))
            val replacement = binding(f.local(), 2)
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), replacement))
            assertIs<AppResult.Failed>(f.bindings().page(f.local(), cursor))
            val fresh =
                assertIs<AppResult.Success<CollaborationSpaceRoomBindingPage>>(
                        f.bindings().page(f.local())
                    )
                    .value
            assertNotEquals(first.boundary?.generation, fresh.boundary?.generation)
            assertEquals(listOf(replacement), fresh.bindings)
        }

    @Test
    fun inventoryRejectsMalformedRawPagesPairsAndLostKeysWithoutRepairingThem() =
        withDatabase { f ->
            val value = binding(f.local(), legacy = true)
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
            val before = f.rawBindings()
            for (change in 0..5) {
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observe(
                            scope: ReplicaScope,
                            partition: String,
                            after: String?,
                            limit: Int,
                        ) =
                            f.database.replicas.observe(scope, partition, after, limit).map { raw ->
                                when (change) {
                                    0 -> raw.copy(documents = raw.documents.reversed())
                                    1 ->
                                        raw.copy(
                                            documents =
                                                listOf(raw.documents.first(), raw.documents.first())
                                        )
                                    2 ->
                                        raw.copy(
                                            documents =
                                                raw.documents.map { it.copy(payload = "corrupt") }
                                        )
                                    else -> raw
                                }
                            }

                        override fun observeQueries(
                            scope: ReplicaScope,
                            partition: String,
                            queries: List<ReplicaQuery>,
                        ) =
                            f.database.replicas.observeQueries(scope, partition, queries).map { raw
                                ->
                                when (change) {
                                    3 -> raw.copy(pages = listOf(raw.pages[0], emptyList()))
                                    4 ->
                                        raw.copy(
                                            pages =
                                                listOf(
                                                    raw.pages[0],
                                                    raw.pages[1].map { it.copy(version = 1) },
                                                )
                                        )
                                    5 -> raw.copy(pages = listOf(raw.pages[0], raw.pages[0]))
                                    else -> raw
                                }
                            }
                    }
                assertIs<AppResult.Failed>(f.bindings(store).page(f.local()))
            }
            f.keys.write(null)
            assertIs<AppResult.Failed>(f.bindings().page(f.local()))
            assertNull(f.keys.read())
            assertEquals(before, f.rawBindings())
        }

    @Test
    fun inventoryPropagatesCancellationAndRejectsAccountReplacementDuringPairReads() =
        withDatabase { f ->
            val value = binding(f.local(), legacy = true)
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
            val entered = CompletableDeferred<Unit>()
            val blocked =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> = flow {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            val job =
                CoroutineScope(currentCoroutineContext()).async {
                    f.bindings(blocked).page(f.local())
                }
            entered.await()
            job.cancelAndJoin()
            assertFailsWith<CancellationException> { job.await() }
            val replaced =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) = flow {
                        val raw =
                            f.database.replicas.observeQueries(scope, partition, queries).first()
                        f.activate(
                            f.access.copy(sessionId = "foreign", accountId = historyId(14004))
                        )
                        emit(raw)
                    }
                }
            assertIs<AppResult.Failed>(f.bindings(replaced).page(f.local()))
            f.activate()
            f.assertBinding(value)
        }

    @Test
    fun inventoryRechecksOwnershipAfterDecryptingItsFinalCheckpoint() = withDatabase { f ->
        val value = binding(f.local(), legacy = true)
        assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        var finalCheckpoint = false
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observePublishedCheckpoint(scope: ReplicaScope, partition: String) =
                    flow {
                        val raw =
                            f.database.replicas.observePublishedCheckpoint(scope, partition).first()
                        finalCheckpoint = true
                        emit(raw)
                    }
            }
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun decrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    ciphertext: ByteArray,
                ): ByteArray {
                    val bytes = f.cipher.decrypt(key, associatedData, ciphertext)
                    if (finalCheckpoint)
                        f.activate(
                            f.access.copy(sessionId = "late-foreign", accountId = historyId(14005))
                        )
                    return bytes
                }
            }
        val reader = EncryptedCollaborationSpaceRoomBindingRepository(store, cipher, f.keys)
        assertIs<AppResult.Failed>(reader.page(f.local()))
        f.activate()
        f.assertBinding(value)
    }

    @Test
    fun newBindingSignalsItsPublishedPairAndWakeWhileExactReplayStaysSilent() = withDatabase { f ->
        coroutineScope {
            val access = f.local()
            val values = Channel<AppResult<String?>>(8)
            val job = launch {
                StoredCollaborationHistorySignalRepository(f.database.replicas)
                    .observe(access)
                    .collect { values.send(it) }
            }
            try {
                assertEquals(AppResult.Success(null), values.receive())
                assertNull(f.keys.read())
                var previous: String? = null
                for (index in 1..2) {
                    val value = binding(access, index, legacy = index == 2)
                    assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
                    val signal =
                        assertNotNull(assertIs<AppResult.Success<String?>>(values.receive()).value)
                    assertNotEquals(previous, signal)
                    f.assertBinding(value)
                    assertTrue(
                        assertNotNull(
                                f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
                            )
                            .pending
                    )
                    val raw = f.rawBindings()
                    assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
                    assertEquals(signal, f.historySignal())
                    assertEquals(raw, f.rawBindings())
                    previous = signal
                }
                yield()
                assertTrue(values.tryReceive().isFailure)
            } finally {
                job.cancelAndJoin()
                values.close()
            }
        }
    }

    @Test
    fun competingHistorySignalCannotPublishFirstOrAppendedBindingOrWake() = withDatabase { f ->
        for (index in 1..2) {
            val before = f.rawBindings()
            val beforeWake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
            var writes = 0
            val replacement = historyId(15000 + index)
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        writes++
                        assertEquals(2, mutations.size)
                        val signal = assertIs<ReplicaMutation.Replace>(mutations.last())
                        assertEquals(CollaborationHistorySignal.PARTITION, signal.partition)
                        assertTrue(
                            f.database.replicas.replace(
                                scope,
                                signal.partition,
                                signal.expected,
                                CollaborationHistorySignal.snapshot(replacement),
                            )
                        )
                        return f.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            val value = binding(f.local(), index)
            assertEquals(AppResult.Success(false), f.bindings(store).retain(f.local(), value))
            assertEquals(1, writes)
            assertEquals(before, f.rawBindings())
            assertEquals(
                beforeWake,
                f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY),
            )
            assertEquals(replacement, f.historySignal())
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
            f.assertBinding(value)
            assertNotEquals(replacement, f.historySignal())
        }
    }

    @Test
    fun malformedHistorySignalCannotBeOverwrittenByBindingPublication() = withDatabase { f ->
        val scope = spaceRoomBindingScope(f.local())
        assertTrue(
            f.database.replicas.replace(
                scope,
                CollaborationHistorySignal.PARTITION,
                null,
                ReplicaSnapshot(historyId(15010), "invalid-signal-payload", emptyList()),
            )
        )
        val raw = f.database.replicas.checkpoint(scope, CollaborationHistorySignal.PARTITION)
        assertIs<AppResult.Failed>(f.bindings().retain(f.local(), binding(f.local())))
        assertEquals(
            raw,
            f.database.replicas.checkpoint(scope, CollaborationHistorySignal.PARTITION),
        )
        assertNull(f.rawBindings().checkpoint)
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun cancellationBeforeAtomicWritePreservesBindingSignalAndRecoveryWake() = withDatabase { f ->
        for (index in 1..2) {
            val entered = CompletableDeferred<Unit>()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertEquals(2, mutations.size)
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            val before = f.rawBindings()
            val signal = f.historySignal()
            val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
            val value = binding(f.local(), index)
            coroutineScope {
                val job = launch { f.bindings(store).retain(f.local(), value) }
                entered.await()
                job.cancelAndJoin()
            }
            assertEquals(before, f.rawBindings())
            assertEquals(signal, f.historySignal())
            assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
            assertEquals(AppResult.Success(true), f.bindings().retain(f.local(), value))
        }
    }

    @Test
    fun bindingDemandSurvivesRenewalAndIsSharedOnlyWithinItsOwnedAccount() = withDatabase { f ->
        val access = f.local()
        val value = binding(access)
        assertEquals(AppResult.Success(true), f.bindings().retain(access, value))
        val original = assertNotNull(f.historySignal())
        val renewed =
            f.access.copy(sessionId = "renewed-binding-signal", companyId = historyId(15020))
        f.reopen(renewed)
        val current = f.local(renewed)
        assertEquals(original, f.historySignal(current))
        assertNull(f.historySignal(access))
        assertEquals(original, f.historySignal(current.copy(spaceId = historyId(15021))))
        assertNull(f.historySignal(current.copy(accountId = historyId(15022))))
        assertNull(f.historySignal(current.copy(audience = "https://other.example.test/")))
        val raw = f.rawBindings(current)
        assertEquals(AppResult.Success(true), f.bindings().retain(current, value))
        assertEquals(original, f.historySignal(current))
        assertEquals(raw, f.rawBindings(current))
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
