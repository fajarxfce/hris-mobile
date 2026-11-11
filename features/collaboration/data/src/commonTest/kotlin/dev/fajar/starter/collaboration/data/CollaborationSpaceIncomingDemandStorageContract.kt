package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

abstract class CollaborationSpaceIncomingDemandStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun sourcePositionAndPhysicalWorkReopenWithTheirLogicalWorkAndSingleWake() = withDatabase { f ->
        val source =
            CollaborationSpaceIncomingSource("a".repeat(64), "@original:proof.invalid", "ORIGINAL")
        val logical = demand(1)
        val next =
            logical.copy(
                nextPosition = 2,
                rooms = logical.rooms.map { it.copy(sourceId = source.storeId) },
                sources =
                    listOf(
                        CollaborationSpaceIncomingSourceProgress(
                            source,
                            "next-position",
                            historyId(6001),
                        )
                    ),
                physicalRooms =
                    listOf(
                        CollaborationSpaceIncomingPhysicalRoomDemand(
                            source.storeId,
                            "!unknown:proof.invalid",
                            historyId(6002),
                            1,
                            "\$next-head",
                            CollaborationSpaceIncomingMembership.Joined,
                            true,
                        )
                    ),
            )
        val wake = CollaborationSpaceIncomingWake(historyId(6003))
        val retained = saved(f.demands().replace(f.local(), null, next, wake))
        assertEquals(next, retained.demand)
        assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        val replacement = f.access.copy(sessionId = "renewed-source-owner")
        f.reopen(replacement)
        assertEquals(
            AppResult.Success(retained),
            f.demands().current(f.local().copy(sessionId = replacement.sessionId)),
        )
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun maximumSourcePositionsAndPhysicalRoomsFitOneBoundedAtomicSnapshot() = withDatabase { f ->
        val requested =
            physicalDemand(64)
                .copy(
                    sources =
                        physicalDemand(64).sources.map {
                            it.copy(
                                source =
                                    it.source.copy(
                                        matrixUserId = "@u:" + "s".repeat(252),
                                        matrixDeviceId = "D".repeat(255),
                                    ),
                                position = "\u0000".repeat(4096),
                            )
                        },
                    physicalRooms =
                        physicalDemand(64).physicalRooms.map {
                            it.copy(
                                roomId = "!" + "漢".repeat(254),
                                headEventId = "$" + "漢".repeat(254),
                            )
                        },
                )
        val wake = CollaborationSpaceIncomingWake(historyId(6500))
        val retained = saved(f.demands().replace(f.local(), null, requested, wake))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_PARTS_CHECKPOINT, page.checkpoint?.payload)
        assertEquals(129, page.documents.size)
        assertTrue(page.documents.all { requireNotNull(it.payload).length <= 131072 })
        assertTrue(
            page.documents.sumOf { requireNotNull(it.payload).length.toLong() } <= 4_194_304L
        )
        assertTrue(page.documents.all { it.tags.isEmpty() && it.sortKey == it.id })
        f.reopen()
        assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun missingExtraDuplicatedAndSwappedPartsCannotBecomeACompleteDemand() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, physicalDemand()))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        val source = page.documents.single { it.id == "s-00" }
        val room = page.documents.single { it.id == "p-00" }
        val extra = room.copy(id = "p-99", sortKey = "p-99")
        val variants =
            listOf(
                page.copy(documents = page.documents.filterNot { it.id == room.id }),
                page.copy(documents = page.documents + extra),
                page.copy(documents = page.documents + source),
                page.copy(
                    documents =
                        page.documents.map {
                            when (it.id) {
                                room.id -> it.copy(payload = source.payload)
                                source.id -> it.copy(payload = room.payload)
                                else -> it
                            }
                        }
                ),
                page.copy(
                    checkpoint =
                        page.checkpoint!!.let {
                            it.copy(handle = it.handle.copy(generation = historyId(6501)))
                        }
                ),
                page.copy(
                    documents =
                        page.documents +
                            (0 until 130).map {
                                room.copy(id = "extra-" + it, sortKey = "extra-" + it)
                            }
                ),
            )
        for (invalid in variants) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ) = flowOf(invalid)
                }
            assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
            assertIs<AppResult.Failed>(
                f.demands(store).replace(f.local(), first, physicalDemand(2))
            )
        }
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
    }

    @Test
    fun callerMutationCannotChangeCapturedSourcesOrPhysicalWork() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, physicalDemand()))
        val expectedSources = first.demand.sources.toMutableList()
        val expectedRooms = first.demand.physicalRooms.toMutableList()
        val next = physicalDemand(2)
        val nextSources = next.sources.toMutableList()
        val nextRooms = next.physicalRooms.toMutableList()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ) = flow {
                    expectedSources.clear()
                    expectedRooms.clear()
                    nextSources.clear()
                    nextRooms.clear()
                    emit(f.database.replicas.observe(scope, partition, after, limit).first())
                }
            }
        val retained =
            saved(
                f.demands(store)
                    .replace(
                        f.local(),
                        first.copy(
                            demand =
                                first.demand.copy(
                                    sources = expectedSources,
                                    physicalRooms = expectedRooms,
                                )
                        ),
                        next.copy(sources = nextSources, physicalRooms = nextRooms),
                    )
            )
        assertTrue(
            listOf(expectedSources, expectedRooms, nextSources, nextRooms).all { it.isEmpty() }
        )
        assertEquals(next, retained.demand)
        assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
    }

    @Test
    fun lostMultipartWriteReplyStillRetainsEveryPartAndTheSingleWake() = withDatabase { f ->
        var writes = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    writes++
                    assertTrue(
                        f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                    )
                    error("Fictional reply loss after commit")
                }
            }
        val requested = physicalDemand(3)
        val wake = CollaborationSpaceIncomingWake(historyId(6502))
        assertIs<AppResult.Failed>(f.demands(store).replace(f.local(), null, requested, wake))
        f.reopen()
        assertEquals(requested, assertNotNull(value(f.demands().current(f.local()))).demand)
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        assertEquals(1, writes)
    }

    @Test
    fun cursorOnlyReplacementNeitherCreatesNorChangesAnIncomingWake() = withDatabase { f ->
        val initial = physicalDemand().copy(nextPosition = 0, physicalRooms = emptyList())
        val first = saved(f.demands().replace(f.local(), null, initial))
        assertNull(value(f.wakes().pending()))
        val second =
            saved(
                f.demands()
                    .replace(
                        f.local(),
                        first,
                        initial.copy(sources = initial.sources.map { it.copy(position = "next") }),
                    )
            )
        assertEquals(AppResult.Success(second), f.demands().current(f.local()))
        assertNull(value(f.wakes().pending()))
        val wake = value(f.wakes().request())
        val third =
            saved(
                f.demands()
                    .replace(
                        f.local(),
                        second,
                        initial.copy(sources = initial.sources.map { it.copy(position = "later") }),
                    )
            )
        f.reopen()
        assertEquals(AppResult.Success(third), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun multipartWakeCapacityFailureCannotPublishAnyInitialOrReplacementPart() = withDatabase { f ->
        for (i in 1..32) assertNotNull(
            f.database.workWakes.request("fixture." + i, historyId(6600 + i), 32)
        )
        val wake = CollaborationSpaceIncomingWake(historyId(6650))
        assertEquals(
            AppResult.Success(null),
            f.demands().replace(f.local(), null, physicalDemand(4), wake),
        )
        assertNull(value(f.demands().current(f.local())))
        val first = saved(f.demands().replace(f.local(), null, physicalDemand(2)))
        assertEquals(
            AppResult.Success(null),
            f.demands().replace(f.local(), first, physicalDemand(4), wake),
        )
        f.reopen()
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
        assertNull(value(f.wakes().pending()))
    }

    @Test
    fun absentReadsAndStandaloneWakesNeverCreateAHistoryKey() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.demands().current(f.local()))
        assertEquals(AppResult.Success(null), f.wakes().pending())
        val wake = value(f.wakes().request())
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        assertNull(f.keys.read())
    }

    @Test
    fun encryptedDemandAndItsWakeReopenAfterSameAccountLoginReplacement() = withDatabase { f ->
        val wake = CollaborationSpaceIncomingWake(historyId(5000))
        val first = saved(f.demands().replace(f.local(), null, demand(), wake))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                )
            )
        assertEquals(INCOMING_DEMAND_CHECKPOINT, raw.payload)
        val encrypted =
            requireNotNull(
                f.database.replicas
                    .document(
                        incomingDemandScope(f.local()),
                        incomingDemandPartition(f.local()),
                        INCOMING_DEMAND_DOCUMENT,
                    )
                    ?.payload
            )
        assertFalse(encrypted.contains(first.demand.rooms.first().conversationId))
        assertFalse(encrypted.contains(first.demand.rooms.first().headEventId!!))
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        assertEquals(
            AppResult.Success(first),
            f.demands().current(f.local().copy(sessionId = renewed.sessionId)),
        )
        assertEquals(AppResult.Success(null), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun incomingCompanyAndOutgoingSpaceWakesAreIndependentEvenWithTheSameGeneration() =
        withDatabase { f ->
            val generation = historyId(5000)
            for (key in
                listOf(
                    CollaborationRecoveryWake.WORK_KEY,
                    CollaborationSpaceRecoveryWake.WORK_KEY,
                )) assertNotNull(f.database.workWakes.request(key, generation, 32))
            saved(
                f.demands()
                    .replace(f.local(), null, demand(), CollaborationSpaceIncomingWake(generation))
            )
            assertEquals(
                AppResult.Success(true),
                f.wakes().acknowledge(CollaborationSpaceIncomingWake(generation)),
            )
            for (key in
                listOf(
                    CollaborationRecoveryWake.WORK_KEY,
                    CollaborationSpaceRecoveryWake.WORK_KEY,
                )) assertTrue(assertNotNull(f.database.workWakes.get(key)).pending)
            val next = value(f.wakes().request())
            f.database.workWakes.acknowledge(CollaborationSpaceRecoveryWake.WORK_KEY, generation)
            f.reopen()
            assertEquals(AppResult.Success(next), f.wakes().pending())
        }

    @Test
    fun capacityFailureRollsBackBothInitialAndReplacementDemand() = withDatabase { f ->
        for (i in 1..32) assertNotNull(
            f.database.workWakes.request("fixture.$i", historyId(6000 + i), 32)
        )
        val wake = CollaborationSpaceIncomingWake(historyId(5000))
        assertEquals(AppResult.Success(null), f.demands().replace(f.local(), null, demand(), wake))
        assertEquals(AppResult.Success(null), f.demands().current(f.local()))
        val first = saved(f.demands().replace(f.local(), null, demand()))
        assertEquals(
            AppResult.Success(null),
            f.demands().replace(f.local(), first, demand(3), wake),
        )
        f.reopen()
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
        assertEquals(AppResult.Success(null), f.wakes().pending())
        for (i in 1..32) assertEquals(
            historyId(6000 + i),
            assertNotNull(f.database.workWakes.get("fixture.$i")).generation,
        )
    }

    @Test
    fun concurrentProducersCommitOnlyOneExactSnapshotAndItsOwnWake() = withDatabase { f ->
        val results = coroutineScope {
            (1..12)
                .map { n ->
                    async {
                        val requested = demand().copy(rescanGeneration = historyId(7000 + n))
                        n to
                            value(
                                f.demands()
                                    .replace(
                                        f.local(),
                                        null,
                                        requested,
                                        CollaborationSpaceIncomingWake(historyId(8000 + n)),
                                    )
                            )
                    }
                }
                .awaitAll()
        }
        val committed = results.filter { it.second != null }
        assertEquals(1, committed.size)
        val (winner, snapshot) = committed.single()
        assertEquals(AppResult.Success(snapshot), f.demands().current(f.local()))
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingWake(historyId(8000 + winner))),
            f.wakes().pending(),
        )
    }

    @Test
    fun cancellationAfterCommitRetainsBothDemandAndWakeWithoutReissuingTheWrite() =
        withDatabase { f ->
            var attempts = 0
            val wake = CollaborationSpaceIncomingWake(historyId(5000))
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        attempts++
                        check(
                            f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                        )
                        throw CancellationException("Fictional commit response loss")
                    }
                }
            assertFailsWith<CancellationException> {
                f.demands(store).replace(f.local(), null, demand(), wake)
            }
            assertEquals(1, attempts)
            f.reopen()
            assertEquals(demand(), saved(f.demands().current(f.local())).demand)
            assertEquals(AppResult.Success(wake), f.wakes().pending())
        }

    @Test
    fun staleSnapshotAndAcknowledgedWakeCannotEraseOrReviveNewWork() = withDatabase { f ->
        val wake = CollaborationSpaceIncomingWake(historyId(5000))
        val first = saved(f.demands().replace(f.local(), null, demand(), wake))
        assertEquals(AppResult.Success(true), f.wakes().acknowledge(wake))
        assertEquals(
            AppResult.Success(null),
            f.demands().replace(f.local(), first, demand(3), wake),
        )
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
        val nextWake = CollaborationSpaceIncomingWake(historyId(5001))
        val next = saved(f.demands().replace(f.local(), first, demand(3), nextWake))
        assertEquals(AppResult.Success(null), f.demands().replace(f.local(), first, demand(4)))
        assertEquals(AppResult.Success(false), f.wakes().acknowledge(wake))
        assertEquals(AppResult.Success(next), f.demands().current(f.local()))
        assertEquals(AppResult.Success(nextWake), f.wakes().pending())
    }

    @Test
    fun phaseUpdatesKeepTheExistingWakeAndEmptyDemandDoesNotAcknowledgeIt() = withDatabase { f ->
        val wake = CollaborationSpaceIncomingWake(historyId(5000))
        val first = saved(f.demands().replace(f.local(), null, demand(), wake))
        val pending =
            demand()
                .copy(
                    rooms =
                        demand().rooms.map {
                            it.copy(phase = CollaborationSpaceIncomingPhase.Pending)
                        }
                )
        val advanced = saved(f.demands().replace(f.local(), first, pending))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        val done =
            saved(f.demands().replace(f.local(), advanced, CollaborationSpaceIncomingDemand()))
        f.reopen()
        assertEquals(AppResult.Success(done), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun maximumRoomCountAndUtf8EventIdentifiersRoundTripWithinTheEncryptedBound() =
        withDatabase { f ->
            val maximum =
                demand(64)
                    .copy(
                        rooms =
                            demand(64).rooms.map { it.copy(headEventId = "$" + "猫".repeat(254)) }
                    )
            val retained =
                saved(
                    f.demands()
                        .replace(
                            f.local(),
                            null,
                            maximum,
                            CollaborationSpaceIncomingWake(historyId(5000)),
                        )
                )
            f.reopen()
            assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
        }

    @Test
    fun otherSpacesCannotReadOrReplaceTheRetainedQueue() = withDatabase { f ->
        val original = saved(f.demands().replace(f.local(), null, demand()))
        val other = f.local().copy(spaceId = historyId(402))
        assertEquals(AppResult.Success(null), f.demands().current(other))
        assertEquals(AppResult.Success(null), f.demands().replace(other, original, demand(3)))
        val independent = saved(f.demands().replace(other, null, demand(3)))
        assertEquals(AppResult.Success(original), f.demands().current(f.local()))
        assertEquals(AppResult.Success(independent), f.demands().current(other))
    }

    @Test
    fun ciphertextCannotBeMovedToAnotherSpaceOrCheckpointGeneration() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, demand()))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                )
            )
        for (other in listOf(f.local(), f.local().copy(spaceId = historyId(402)))) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ) = flow {
                        val page =
                            f.database.replicas
                                .observe(
                                    incomingDemandScope(f.local()),
                                    incomingDemandPartition(f.local()),
                                    after,
                                    limit,
                                )
                                .first()
                        emit(
                            page.copy(
                                checkpoint =
                                    raw.copy(
                                        handle =
                                            raw.handle.copy(
                                                scope = scope,
                                                partition = partition,
                                                generation =
                                                    if (other == f.local()) historyId(9990)
                                                    else raw.handle.generation,
                                            )
                                    )
                            )
                        )
                    }
                }
            assertIs<AppResult.Failed>(f.demands(store).current(other))
            assertIs<AppResult.Failed>(f.demands(store).replace(other, first, demand(3)))
        }
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
    }

    @Test
    fun accountReplacementDuringCommitCannotPublishDemandOrItsWake() = withDatabase { f ->
        val oldAccount = f.access
        val access = f.local()
        val originalWake = CollaborationSpaceIncomingWake(historyId(5000))
        val first = saved(f.demands().replace(access, null, demand(), originalWake))
        val other = f.access.copy(sessionId = "other", accountId = historyId(900))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.demands(store)
                .replace(access, first, demand(3), CollaborationSpaceIncomingWake(historyId(5001))),
        )
        assertEquals(AppResult.Success(null), f.demands().current(f.local()))
        assertEquals(AppResult.Success(originalWake), f.wakes().pending())
        f.activate(oldAccount)
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
    }

    @Test
    fun invalidDemandCannotCreateAKeyCheckpointOrWake() = withDatabase { f ->
        val invalid =
            listOf(
                demand(65),
                demand().copy(nextPosition = 0),
                demand().copy(rooms = listOf(demand().rooms.first(), demand().rooms.first())),
                demand().copy(rescanGeneration = "invalid"),
            )
        for (bad in invalid) assertIs<AppResult.Failed>(
            f.demands()
                .replace(f.local(), null, bad, CollaborationSpaceIncomingWake(historyId(5000)))
        )
        assertIs<AppResult.Failed>(
            f.demands()
                .replace(f.local(), null, demand(), CollaborationSpaceIncomingWake("invalid"))
        )
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(null), f.demands().current(f.local()))
        assertEquals(AppResult.Success(null), f.wakes().pending())
    }

    @Test
    fun authenticatedButUnknownSchemaOrNoncanonicalProgressIsRejected() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, demand()))
        val scope = incomingDemandScope(f.local())
        val partition = incomingDemandPartition(f.local())
        val raw = assertNotNull(f.database.replicas.checkpoint(scope, partition))
        val aad =
            Json.encodeToString(
                    listOf(
                        "hris-collaboration-space-incoming-demand-v1",
                        f.access.audience,
                        f.access.accountId,
                        f.local().spaceId,
                        raw.handle.generation,
                        INCOMING_DEMAND_DOCUMENT,
                        "0",
                    )
                )
                .encodeToByteArray()
        val page =
            f.database.replicas.observeDocument(scope, partition, INCOMING_DEMAND_DOCUMENT).first()
        val document = page.documents.single()
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
            val bytes = f.cipher.decrypt(key, aad, Base64.decode(requireNotNull(document.payload)))
            val original =
                try {
                    bytes.decodeToString()
                } finally {
                    bytes.fill(0)
                }
            for (bad in
                listOf(
                    original.replace("\"schema\":1", "\"schema\":99"),
                    original.replace("\"nextPosition\":\"2\"", "\"nextPosition\":\"02\""),
                    original.replace("\"phase\":\"Latest\"", "\"phase\":\"Unknown\""),
                )) {
                assertNotEquals(original, bad)
                val payload = Base64.encode(f.cipher.encrypt(key, aad, bad.encodeToByteArray()))
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observe(
                            scope: ReplicaScope,
                            partition: String,
                            after: String?,
                            limit: Int,
                        ) = flowOf(page.copy(documents = listOf(document.copy(payload = payload))))
                    }
                assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
                assertIs<AppResult.Failed>(f.demands(store).replace(f.local(), first, demand(3)))
            }
        }
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
    }

    @Test
    fun callerMutationDuringTheFirstReadCannotChangeEitherCapturedSnapshot() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, demand()))
        val expectedRooms = first.demand.rooms.toMutableList()
        val next = demand(3)
        val requestedRooms = next.rooms.toMutableList()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ) = flow {
                    expectedRooms.clear()
                    requestedRooms.clear()
                    emit(f.database.replicas.observe(scope, partition, after, limit).first())
                }
            }
        val saved =
            saved(
                f.demands(store)
                    .replace(
                        f.local(),
                        first.copy(demand = first.demand.copy(rooms = expectedRooms)),
                        next.copy(rooms = requestedRooms),
                    )
            )
        assertTrue(expectedRooms.isEmpty() && requestedRooms.isEmpty())
        assertEquals(next, saved.demand)
        assertEquals(AppResult.Success(saved), f.demands().current(f.local()))
    }

    @Test
    fun inconsistentCheckpointAndDocumentMetadataCannotBeReadOrReplaced() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, demand()))
        val page =
            f.database.replicas
                .observeDocument(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    INCOMING_DEMAND_DOCUMENT,
                )
                .first()
        val raw = requireNotNull(page.checkpoint)
        val document = page.documents.single()
        val malformed =
            listOf(
                page.copy(checkpoint = null),
                page.copy(checkpoint = raw.copy(payload = "unknown/v1")),
                page.copy(checkpoint = raw.copy(published = false)),
                page.copy(checkpoint = raw.copy(revision = 1)),
                page.copy(documents = emptyList()),
                page.copy(documents = listOf(document, document)),
                page.copy(documents = listOf(document.copy(id = "other"))),
                page.copy(documents = listOf(document.copy(version = 1))),
                page.copy(documents = listOf(document.copy(sortKey = "other"))),
                page.copy(documents = listOf(document.copy(tags = setOf("unexpected")))),
                page.copy(documents = listOf(document.copy(payload = null))),
            )
        for (bad in malformed) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ) = flowOf(bad)
                }
            assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
            assertIs<AppResult.Failed>(f.demands(store).replace(f.local(), first, demand(3)))
        }
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
    }

    @Test
    fun legacyDemandUpgradesToScanAndRetryThenReopensWithoutChangingItsWake() = withDatabase { f ->
        val wake = CollaborationSpaceIncomingWake(historyId(5000))
        val first = saved(f.demands().replace(f.local(), null, demand(), wake))
        val upgraded = saved(f.demands().replace(f.local(), first, scanDemand(f)))
        f.reopen()
        assertEquals(AppResult.Success(upgraded), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        val clean =
            saved(f.demands().replace(f.local(), upgraded, CollaborationSpaceIncomingDemand()))
        f.reopen()
        assertEquals(AppResult.Success(clean), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun maximumUtf8QueueScanAndRetryFitTheExistingDocumentLimit() = withDatabase { f ->
        val base = scanDemand(f, 64)
        val maximum =
            base.copy(
                nextPosition = 9_007_199_254_740_991,
                rooms =
                    base.rooms.mapIndexed { i, room ->
                        room.copy(
                            position = 9_007_199_254_740_000L + i,
                            headEventId = "$" + "猫".repeat(254),
                            retry =
                                CollaborationSpaceIncomingRetry(
                                    9_007_199_254_440_991,
                                    9_007_199_254_740_991,
                                    5,
                                ),
                        )
                    },
            )
        val saved =
            saved(
                f.demands()
                    .replace(
                        f.local(),
                        null,
                        maximum,
                        CollaborationSpaceIncomingWake(historyId(5000)),
                    )
            )
        f.reopen()
        assertEquals(AppResult.Success(saved), f.demands().current(f.local()))
    }

    @Test
    fun scanCursorCannotBeRetainedUnderAnotherNamespace() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, demand()))
        val good = scanDemand(f)
        val cursor = good.scan!!.cursor!!
        for (boundary in
            listOf(
                cursor.boundary.copy(audience = "https://other.example"),
                cursor.boundary.copy(accountId = historyId(9998)),
                cursor.boundary.copy(spaceId = historyId(9998)),
            )) {
            val invalid =
                good.copy(scan = good.scan!!.copy(cursor = cursor.copy(boundary = boundary)))
            assertIs<AppResult.Failed>(f.demands().replace(f.local(), first, invalid))
        }
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
    }

    @Test
    fun cancellationAfterScanCommitCannotSeparateTheCursorFromItsRoomTasks() = withDatabase { f ->
        val wake = CollaborationSpaceIncomingWake(historyId(5000))
        val next = scanDemand(f)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    val saved =
                        f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                    if (saved) throw CancellationException("Fictional lost scan commit response")
                    return saved
                }
            }
        coroutineScope {
            val job = launch { f.demands(store).replace(f.local(), null, next, wake) }
            job.join()
            assertTrue(job.isCancelled)
        }
        f.reopen()
        assertEquals(next, value(f.demands().current(f.local()))!!.demand)
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun competingScanTransactionsRetainOneWholeCursorAndRoomQueue() = withDatabase { f ->
        val before = saved(f.demands().replace(f.local(), null, demand()))
        val candidates =
            (1..4).map { count ->
                scanDemand(f, count).let { value ->
                    value.copy(
                        scan =
                            value.scan!!.copy(
                                cursor =
                                    value.scan!!
                                        .cursor!!
                                        .copy(afterId = "b-" + count.toString().padStart(64, '0'))
                            )
                    )
                }
            }
        val results = coroutineScope {
            candidates
                .map { candidate ->
                    async { value(f.demands().replace(f.local(), before, candidate)) }
                }
                .awaitAll()
        }
        val winner = results.filterNotNull().single()
        f.reopen()
        assertEquals(AppResult.Success(winner), f.demands().current(f.local()))
        assertTrue(winner.demand in candidates)
    }

    private fun scanDemand(
        f: CollaborationHistoryFixture,
        count: Int = 2,
    ): CollaborationSpaceIncomingDemand {
        val local = f.local()
        val base = demand(count)
        return base.copy(
            rooms =
                base.rooms.map {
                    it.copy(retry = CollaborationSpaceIncomingRetry(1_000_000, 1_030_000, 1))
                },
            scan =
                CollaborationSpaceIncomingScan(
                    historyId(8999),
                    CollaborationSpaceRoomBindingCursor(
                        CollaborationSpaceRoomBindingBoundary(
                            local.audience,
                            local.accountId,
                            local.spaceId,
                            historyId(9001),
                            4,
                        ),
                        "b-" + "0".repeat(63) + "1",
                    ),
                    true,
                ),
        )
    }

    @Test
    fun authenticatedScanAndRetryPayloadRejectsAmbiguousOrUnboundedValues() = withDatabase { f ->
        val first = saved(f.demands().replace(f.local(), null, scanDemand(f)))
        val scope = incomingDemandScope(f.local())
        val partition = incomingDemandPartition(f.local())
        val raw = assertNotNull(f.database.replicas.checkpoint(scope, partition))
        val aad =
            Json.encodeToString(
                    listOf(
                        "hris-collaboration-space-incoming-demand-v1",
                        f.access.audience,
                        f.access.accountId,
                        f.local().spaceId,
                        raw.handle.generation,
                        INCOMING_DEMAND_DOCUMENT,
                        "0",
                    )
                )
                .encodeToByteArray()
        val page =
            f.database.replicas.observeDocument(scope, partition, INCOMING_DEMAND_DOCUMENT).first()
        val document = page.documents.single()
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
            val bytes = f.cipher.decrypt(key, aad, Base64.decode(requireNotNull(document.payload)))
            val original =
                try {
                    bytes.decodeToString()
                } finally {
                    bytes.fill(0)
                }
            for (bad in
                listOf(
                    original.replace("\"schema\":2", "\"schema\":1"),
                    original.replace("\"revision\":\"4\"", "\"revision\":\"04\""),
                    original.replace("\"n\":1", "\"n\":0"),
                    original.replace("\"after\":\"1030000\"", "\"after\":\"9999999\""),
                    original.replace("\"afterId\":\"b-", "\"afterId\":\"invalid-"),
                )) {
                assertNotEquals(original, bad)
                val payload = Base64.encode(f.cipher.encrypt(key, aad, bad.encodeToByteArray()))
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observe(
                            scope: ReplicaScope,
                            partition: String,
                            after: String?,
                            limit: Int,
                        ) = flowOf(page.copy(documents = listOf(document.copy(payload = payload))))
                    }
                assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
                assertIs<AppResult.Failed>(f.demands(store).replace(f.local(), first, demand(3)))
            }
        }
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
    }

    private fun CollaborationHistoryFixture.local() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            historyId(401),
        )

    private fun CollaborationHistoryFixture.demands(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceIncomingDemandRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.wakes(store: WorkWakeStore = database.workWakes) =
        StoredCollaborationSpaceIncomingWakeRepository(store)

    private fun physicalDemand(count: Int = 1) =
        CollaborationSpaceIncomingDemand(
            nextPosition = count.toLong(),
            sources =
                (0 until count).map {
                    CollaborationSpaceIncomingSourceProgress(
                        CollaborationSpaceIncomingSource(
                            (6100 + it).toString(16).padStart(64, '0'),
                            "@source:proof.invalid",
                            "ORIGINAL",
                        ),
                        "before",
                    )
                },
            physicalRooms =
                (0 until count).map {
                    CollaborationSpaceIncomingPhysicalRoomDemand(
                        (6100 + it).toString(16).padStart(64, '0'),
                        "!room-" + it + ":proof.invalid",
                        historyId(6200 + it),
                        it.toLong(),
                        "$" + "head-" + it,
                        CollaborationSpaceIncomingMembership.Joined,
                        false,
                    )
                },
        )

    private fun demand(count: Int = 2) =
        CollaborationSpaceIncomingDemand(
            count.toLong(),
            (0 until count).map { n ->
                CollaborationSpaceIncomingRoomDemand(
                    historyId(1000 + n),
                    historyId(2000 + n),
                    n.toLong(),
                    "\$head-$n",
                    CollaborationSpaceIncomingPhase.entries[n % 3],
                )
            },
            historyId(9000),
        )

    private fun <T> value(result: AppResult<T>): T = assertIs<AppResult.Success<T>>(result).value

    private fun saved(result: AppResult<CollaborationSpaceIncomingDemandSnapshot?>) =
        assertNotNull(value(result))

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
