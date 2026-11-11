package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceOriginalRoomMetadataRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceOriginalRoomMetadataStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun emptyExactReadsDoNotAllocateKeys() = withDatabase { f ->
        val value = metadata(f.local())
        assertEquals(
            AppResult.Success(null),
            f.metadata().getByConversation(f.local(), value.conversation.position),
        )
        assertEquals(
            AppResult.Success(null),
            f.metadata()
                .findByRoom(
                    f.local(),
                    value.conversation.position.companyId,
                    value.room.serverName,
                    value.room.roomId,
                ),
        )
        assertNull(f.keys.read())
        assertNull(f.rawMetadata().checkpoint)
    }

    @Test
    fun encryptedIndexesReopenAcrossSessionAndSelectedCompanyChanges() = withDatabase { f ->
        val value = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        val before = f.rawMetadata()
        assertEquals(2, before.documents.size)
        assertEquals(0, before.checkpoint?.revision)
        assertTrue(
            before.documents.all {
                it.id.matches(Regex("o-[0-9a-f]{64}")) && it.tags == setOf(it.id)
            }
        )
        val payloads = before.documents.joinToString { it.id + it.payload + it.tags }
        for (secret in
            listOf(
                value.conversation.position.companyId,
                value.conversation.position.conversationId,
                value.room.roomId,
                value.conversation.firstAccountId,
            )) assertFalse(payloads.contains(secret))
        val renewed =
            f.access.copy(sessionId = "renewed-original-owner", companyId = historyId(7101))
        f.reopen(renewed)
        val access = f.local(renewed)
        f.assertMetadata(value, access)
        assertEquals(AppResult.Success(true), f.metadata().retain(access, value))
        assertEquals(before.documents, f.rawMetadata(access).documents)
        assertEquals(before.checkpoint?.payload, f.rawMetadata(access).checkpoint?.payload)
    }

    @Test
    fun equalConversationAndPhysicalRoomNamesInDifferentCompaniesKeepTheirOriginals() =
        withDatabase { f ->
            val first = metadata(f.local())
            val second =
                first.copy(
                    conversation =
                        first.conversation.copy(
                            position = first.conversation.position.copy(companyId = historyId(7102))
                        )
                )
            for (value in listOf(first, second)) assertEquals(
                AppResult.Success(true),
                f.metadata().retain(f.local(), value),
            )
            assertEquals(4, f.rawMetadata().documents.size)
            f.reopen()
            for (value in listOf(first, second)) f.assertMetadata(value)
            assertEquals(
                AppResult.Success(null),
                f.metadata()
                    .findByRoom(
                        f.local(),
                        historyId(7103),
                        first.room.serverName,
                        first.room.roomId,
                    ),
            )
        }

    @Test
    fun metadataRetentionDoesNotCreateOrConsumeWorkerWakes() = withDatabase { f ->
        val wake =
            assertNotNull(f.database.workWakes.request("incoming-owner", "original-capture", 32))
        val first = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
        val snapshot = f.rawMetadata()
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
        assertEquals(snapshot, f.rawMetadata())
        assertEquals(wake, f.database.workWakes.get("incoming-owner"))
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        assertEquals(
            AppResult.Success(true),
            f.metadata().retain(f.local(), metadata(f.local(), 2)),
        )
        assertEquals(wake, f.database.workWakes.get("incoming-owner"))
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun fullSchedulerCapacityDoesNotPreventIndependentMetadataRetention() = withDatabase { f ->
        repeat(32) {
            assertNotNull(f.database.workWakes.request("occupied.$it", "capture-$it", 32))
        }
        val value = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        f.assertMetadata(value)
        for (index in 0 until 32) assertEquals(
            "capture-$index",
            f.database.workWakes.get("occupied.$index")?.generation,
        )
    }

    @Test
    fun immutableParticipantAndCreatorFactsCannotBeReplaced() = withDatabase { f ->
        val first = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
        val before = f.rawMetadata()
        for (value in
            listOf(
                first.copy(
                    conversation =
                        first.conversation.copy(createdBy = first.conversation.secondAccountId)
                ),
                first.copy(room = first.room.copy(createdBy = first.conversation.firstAccountId)),
                first.copy(
                    conversation =
                        first.conversation.copy(
                            createdAt = Instant.parse("2026-11-06T00:00:00.123457Z")
                        )
                ),
            )) {
            assertNotEquals(first, value)
            assertEquals(AppResult.Success(false), f.metadata().retain(f.local(), value))
            assertEquals(before, f.rawMetadata())
        }
        f.assertMetadata(first)
    }

    @Test
    fun neitherConversationNorPhysicalRoomCanBeRemappedInsideItsCompany() = withDatabase { f ->
        val first = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
        val before = f.rawMetadata()
        val otherRoom = first.copy(room = first.room.copy(roomId = "!other:matrix.example.test"))
        val otherConversation =
            first.copy(
                conversation =
                    first.conversation.copy(
                        position =
                            first.conversation.position.copy(conversationId = historyId(7209))
                    )
            )
        for (value in listOf(otherRoom, otherConversation)) {
            assertIs<AppResult.Failed>(f.metadata().retain(f.local(), value))
            assertEquals(before, f.rawMetadata())
            f.assertMetadata(first)
        }
    }

    @Test
    fun malformedOriginalFactsFailBeforeCreatingKeys() = withDatabase { f ->
        val valid = metadata(f.local())
        val conversation = valid.conversation
        for (value in
            listOf(
                valid.copy(
                    conversation =
                        conversation.copy(
                            position =
                                conversation.position.copy(
                                    companyId = "00000000-0000-0000-0000-000000000000"
                                )
                        )
                ),
                valid.copy(
                    conversation =
                        conversation.copy(
                            firstAccountId = conversation.secondAccountId,
                            secondAccountId = conversation.firstAccountId,
                        )
                ),
                valid.copy(conversation = conversation.copy(createdBy = historyId(7109))),
                valid.copy(conversation = conversation.copy(version = 1)),
                valid.copy(room = valid.room.copy(createdBy = historyId(7109))),
                valid.copy(room = valid.room.copy(boundAt = Instant.parse("2026-11-05T00:00:00Z"))),
                valid.copy(room = valid.room.copy(roomId = "missing-room-prefix")),
            )) assertIs<AppResult.Failed>(f.metadata().retain(f.local(), value))
        assertNull(f.keys.read())
        assertNull(f.rawMetadata().checkpoint)
    }

    @Test
    fun malformedLookupCoordinatesFailEvenWhenStorageIsEmpty() = withDatabase { f ->
        val value = metadata(f.local())
        for (company in listOf("bad", "00000000-0000-0000-0000-000000000000")) {
            assertIs<AppResult.Failed>(
                f.metadata()
                    .getByConversation(
                        f.local(),
                        value.conversation.position.copy(companyId = company),
                    )
            )
            assertIs<AppResult.Failed>(
                f.metadata()
                    .findByRoom(f.local(), company, value.room.serverName, value.room.roomId)
            )
        }
        assertIs<AppResult.Failed>(
            f.metadata()
                .findByRoom(
                    f.local(),
                    value.conversation.position.companyId,
                    value.room.serverName,
                    "bad",
                )
        )
        assertNull(f.keys.read())
    }

    @Test
    fun retainedOriginalIndexesPreventSilentKeyReplacement() = withDatabase { f ->
        val value = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        val before = f.rawMetadata()
        f.keys.write(null)
        assertIs<AppResult.Failed>(
            f.metadata().getByConversation(f.local(), value.conversation.position)
        )
        assertIs<AppResult.Failed>(
            f.metadata().retain(f.local().copy(spaceId = historyId(7301)), metadata(f.local(), 2))
        )
        assertNull(f.keys.read())
        assertEquals(before, f.rawMetadata())
    }

    @Test
    fun anotherSpaceAudienceOrAccountCannotReadOriginalMetadata() = withDatabase { f ->
        val value = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        for (other in
            listOf(
                f.local().copy(spaceId = historyId(7009)),
                f.local().copy(audience = "https://other.example.test/"),
            )) assertEquals(
            AppResult.Success(null),
            f.metadata().getByConversation(other, value.conversation.position),
        )
        val foreign = f.access.copy(sessionId = "other-account", accountId = historyId(7400))
        f.activate(foreign)
        assertEquals(
            AppResult.Success(null),
            f.metadata().getByConversation(f.local(foreign), value.conversation.position),
        )
        assertEquals(
            AppResult.Success(null),
            f.metadata().getByConversation(f.local(), value.conversation.position),
        )
        f.activate()
        f.assertMetadata(value)
    }

    @Test
    fun anotherCompanyCiphertextCannotBeMovedOntoAnOriginalIndex() = withDatabase { f ->
        val first = metadata(f.local())
        val second =
            first.copy(
                conversation =
                    first.conversation.copy(
                        position = first.conversation.position.copy(companyId = historyId(7102))
                    )
            )
        for (value in listOf(first, second)) assertEquals(
            AppResult.Success(true),
            f.metadata().retain(f.local(), value),
        )
        val raw = f.rawMetadata()
        val foreignPayload = raw.documents.first { it.version == 1L }.payload
        val changed =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> =
                    f.database.replicas.observeDocument(scope, partition, id).map { page ->
                        page.copy(
                            documents = page.documents.map { it.copy(payload = foreignPayload) }
                        )
                    }
            }
        assertIs<AppResult.Failed>(
            f.metadata(changed).getByConversation(f.local(), first.conversation.position)
        )
        f.assertMetadata(first)
        f.assertMetadata(second)
    }

    @Test
    fun missingDuplicatedOrRevisionMismatchedPairsNeverReturnPartialMetadata() = withDatabase { f ->
        val value = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        for (mode in 0..2) {
            val broken =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> =
                        f.database.replicas.observeQueries(scope, partition, queries).map { page ->
                            val first = page.pages[0].single()
                            val second = page.pages[1].single()
                            page.copy(
                                pages =
                                    when (mode) {
                                        0 -> listOf(listOf(first), emptyList())
                                        1 -> listOf(listOf(first), listOf(first))
                                        else ->
                                            listOf(
                                                listOf(first),
                                                listOf(second.copy(version = second.version + 1)),
                                            )
                                    }
                            )
                        }
                }
            assertIs<AppResult.Failed>(
                f.metadata(broken).getByConversation(f.local(), value.conversation.position)
            )
            f.assertMetadata(value)
        }
    }

    @Test
    fun cancelledInitialWriteDoesNotPublishMetadataOrRetry() = withDatabase { f ->
        var writes = 0
        val blocked =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    writes++
                    throw CancellationException("Interrupted before write")
                }
            }
        assertFailsWith<CancellationException> {
            f.metadata(blocked).retain(f.local(), metadata(f.local()))
        }
        assertEquals(1, writes)
        assertNull(f.rawMetadata().checkpoint)
    }

    @Test
    fun lostInitialAndAppendResponsesReopenBothIndexesWithoutReplay() = withDatabase { f ->
        var writes = 0
        fun lossy() =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    writes++
                    check(f.database.replicas.replace(scope, partition, expected, snapshot, wake))
                    throw CancellationException("Lost initial response")
                }

                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    writes++
                    check(
                        f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    )
                    throw CancellationException("Lost append response")
                }
            }
        for (index in 1..2) {
            val value = metadata(f.local(), index)
            assertFailsWith<CancellationException> { f.metadata(lossy()).retain(f.local(), value) }
            val before = f.rawMetadata()
            f.reopen()
            f.assertMetadata(value)
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
            assertEquals(before, f.rawMetadata())
            assertEquals(index, writes)
        }
    }

    @Test
    fun concurrentUnrelatedAppendReturnsContentionWithoutOverwritingOrRetrying() =
        withDatabase { f ->
            val first = metadata(f.local())
            val producer = metadata(f.local(), 2)
            val requested = metadata(f.local(), 3)
            assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), first))
            var commits = 0
            val competing =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        commits++
                        assertEquals(
                            AppResult.Success(true),
                            f.metadata().retain(f.local(), producer),
                        )
                        return f.database.replicas.commit(
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
                f.metadata(competing).retain(f.local(), requested),
            )
            assertEquals(1, commits)
            f.assertMetadata(first)
            f.assertMetadata(producer)
            assertEquals(
                AppResult.Success(null),
                f.metadata().getByConversation(f.local(), requested.conversation.position),
            )
            assertEquals(4, f.rawMetadata().documents.size)
        }

    @Test
    fun accountRetirementDuringPairReadCannotReturnOldMetadata() = withDatabase { f ->
        val value = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
        val retired =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = flow {
                    val page = f.database.replicas.observeQueries(scope, partition, queries).first()
                    f.activate(
                        f.access.copy(sessionId = "foreign-account", accountId = historyId(7401))
                    )
                    emit(page)
                }
            }
        assertIs<AppResult.Failed>(
            f.metadata(retired).getByConversation(f.local(), value.conversation.position)
        )
        f.activate()
        f.assertMetadata(value)
    }

    @Test
    fun cancellationWhileAuthenticatingThePairPropagatesAndPreservesMetadata() = withDatabase { f ->
        val value = metadata(f.local())
        assertEquals(AppResult.Success(true), f.metadata().retain(f.local(), value))
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
                f.metadata(blocked).getByConversation(f.local(), value.conversation.position)
            }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        f.assertMetadata(value)
    }

    private fun CollaborationHistoryFixture.local(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(7000),
        )

    private fun metadata(
        access: CollaborationSpaceLocalAccess,
        index: Int = 1,
    ): CollaborationSpaceOriginalRoomMetadata {
        val pair = listOf(access.accountId, historyId(10000 + index)).sorted()
        return CollaborationSpaceOriginalRoomMetadata(
            CollaborationOriginalConversation(
                CollaborationOriginalConversationPosition(historyId(7100), historyId(7200 + index)),
                pair[0],
                pair[1],
                pair[0],
                Instant.parse("2026-11-06T00:00:00.123456Z"),
                0,
            ),
            CollaborationOriginalRoom(
                "matrix.example.test",
                "!original$index:matrix.example.test",
                Instant.parse("2026-11-06T01:00:00.654321Z"),
                pair[1],
            ),
        )
    }

    private fun CollaborationHistoryFixture.metadata(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceOriginalRoomMetadataRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.rawMetadata(
        access: CollaborationSpaceLocalAccess = local()
    ) =
        database.replicas
            .observe(
                spaceOriginalRoomMetadataScope(access),
                spaceOriginalRoomMetadataPartition(access),
                limit = 200,
            )
            .first()

    private suspend fun CollaborationHistoryFixture.assertMetadata(
        value: CollaborationSpaceOriginalRoomMetadata,
        access: CollaborationSpaceLocalAccess = local(),
    ) {
        assertEquals(
            AppResult.Success(value),
            metadata().getByConversation(access, value.conversation.position),
        )
        assertEquals(
            AppResult.Success(value),
            metadata()
                .findByRoom(
                    access,
                    value.conversation.position.companyId,
                    value.room.serverName,
                    value.room.roomId,
                ),
        )
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
