package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

abstract class CollaborationSpaceIncomingDemandStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun maximumTargetPredicatesFitTheEncryptedHeaderWithAllExistingParts() = withDatabase { f ->
        val active = List(64) { "!a${it.toString().padStart(2, '0')}" + "漢".repeat(100) }
        val requested = List(64) { "!b${it.toString().padStart(2, '0')}" + "\"\\".repeat(90) }
        val recovery =
            retainedHistoryRecovery().copy(roomIds = active, requestedRoomIds = requested)
        val before = physicalDemand(64).copy(historyRecovery = recovery)
        val wake = CollaborationSpaceIncomingWake(historyId(9390))
        val initial = saved(f.demands().replace(f.local(), null, before, wake))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT, page.checkpoint?.payload)
        assertEquals(129, page.documents.size)
        assertTrue(page.documents.all { assertNotNull(it.payload).length <= 131072 })
        assertTrue(page.documents.sumOf { assertNotNull(it.payload).length.toLong() } <= 4_194_304L)
        f.reopen()
        assertEquals(AppResult.Success(initial), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun requestedTargetsReopenWithoutNarrowingAnActiveAccountPass() = withDatabase { f ->
        val recovery = retainedHistoryRecovery().copy(requestedRoomIds = listOf("!later:test"))
        val initial =
            saved(
                f.demands().replace(f.local(), null, typedDemand().copy(historyRecovery = recovery))
            )
        f.reopen()
        assertEquals(AppResult.Success(initial), f.demands().current(f.local()))
        val retained = assertNotNull(initial.demand.historyRecovery)
        assertNull(retained.roomIds)
        assertEquals(listOf("!later:test"), retained.requestedRoomIds)
    }

    @Test
    fun laterAccountRequestPromotesWithoutRetainingTheOldRoomRestriction() = withDatabase { f ->
        val recovery =
            CollaborationSpaceHistoryRecovery(
                historyId(9200),
                revision = 1,
                requestedGeneration = historyId(9201),
                roomIds = listOf("!active:test"),
            )
        val before = CollaborationSpaceIncomingDemand(historyRecovery = recovery)
        val wake = CollaborationSpaceIncomingWake(historyId(9390))
        val initial = saved(f.demands().replace(f.local(), null, before, wake))
        val next =
            assertIs<AppResult.Success<CollaborationSpaceHistoryRecovery>>(
                    planCollaborationSpaceHistoryRecoveryAdvance(
                        recovery,
                        recovery,
                        null,
                        false,
                        false,
                    )
                )
                .value
        val result =
            saved(f.demands().replace(f.local(), initial, before.copy(historyRecovery = next)))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT, page.checkpoint?.payload)
        f.reopen()
        assertEquals(AppResult.Success(result), f.demands().current(f.local()))
        assertNull(next.roomIds)
        assertNull(next.requestedRoomIds)
        assertEquals(historyId(9201), next.generation)
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun olderAuthenticatedRecoveryHeaderCannotSmuggleRoomPredicates() = withDatabase { f ->
        saved(
            f.demands()
                .replace(
                    f.local(),
                    null,
                    CollaborationSpaceIncomingDemand(historyRecovery = retainedHistoryRecovery()),
                )
        )
        for (name in listOf("roomIds", "requestedRoomIds")) {
            assertHistoryRecoveryHeaderRejected(f, INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT) {
                fields ->
                JsonObject(
                    fields +
                        ("historyRecovery" to
                            JsonObject(
                                fields.getValue("historyRecovery").jsonObject +
                                    (name to JsonArray(listOf(JsonPrimitive("!target:test"))))
                            ))
                )
            }
        }
    }

    @Test
    fun targetedHeaderRequiresBothItsVersionAndAnActualPredicate() = withDatabase { f ->
        saved(
            f.demands()
                .replace(
                    f.local(),
                    null,
                    CollaborationSpaceIncomingDemand(
                        historyRecovery =
                            retainedHistoryRecovery().copy(roomIds = listOf("!a:test"))
                    ),
                )
        )
        for (change in 0..3) {
            assertHistoryRecoveryHeaderRejected(f, INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT) {
                fields ->
                when (change) {
                    0 -> JsonObject(fields + ("schema" to JsonPrimitive(7)))
                    1 -> JsonObject(fields - "historyRecovery")
                    2 -> JsonObject(fields + ("historyRecovery" to JsonNull))
                    else ->
                        JsonObject(
                            fields +
                                ("historyRecovery" to
                                    JsonObject(
                                        fields.getValue("historyRecovery").jsonObject -
                                            "roomIds" -
                                            "requestedRoomIds"
                                    ))
                        )
                }
            }
        }
    }

    @Test
    fun authenticatedTargetListsRejectMalformedUnboundedOrNonCanonicalValues() = withDatabase { f ->
        saved(
            f.demands()
                .replace(
                    f.local(),
                    null,
                    CollaborationSpaceIncomingDemand(
                        historyRecovery =
                            retainedHistoryRecovery().copy(roomIds = listOf("!a:test"))
                    ),
                )
        )
        val invalid =
            listOf(
                emptyList(),
                listOf("bad"),
                listOf("!a:test", "!a:test"),
                listOf("!b:test", "!a:test"),
                List(65) { "!${it.toString().padStart(2, '0')}:test" },
                List(64) { "!${it.toString().padStart(2, '0')}" + "漢".repeat(150) },
            )
        for (name in listOf("roomIds", "requestedRoomIds")) for (rooms in invalid) {
            assertHistoryRecoveryHeaderRejected(f, INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT) {
                fields ->
                JsonObject(
                    fields +
                        ("historyRecovery" to
                            JsonObject(
                                fields.getValue("historyRecovery").jsonObject +
                                    (name to JsonArray(rooms.map(::JsonPrimitive)))
                            ))
                )
            }
        }
    }

    @Test
    fun requestedPredicateWithoutARequestedGenerationCannotReopen() = withDatabase { f ->
        saved(
            f.demands()
                .replace(
                    f.local(),
                    null,
                    CollaborationSpaceIncomingDemand(
                        historyRecovery =
                            retainedHistoryRecovery()
                                .copy(
                                    roomIds = listOf("!a:test"),
                                    requestedRoomIds = listOf("!later:test"),
                                )
                    ),
                )
        )
        assertHistoryRecoveryHeaderRejected(f, INCOMING_DEMAND_TARGETED_HISTORY_CHECKPOINT) { fields
            ->
            JsonObject(
                fields +
                    ("historyRecovery" to
                        JsonObject(
                            fields.getValue("historyRecovery").jsonObject +
                                ("requestedGeneration" to JsonNull)
                        ))
            )
        }
    }

    @Test
    fun historyRecoveryAndWakeCommitBeforeAnySourceSyncPositionExists() = withDatabase { f ->
        val before =
            CollaborationSpaceIncomingDemand(
                historyRecovery = CollaborationSpaceHistoryRecovery(historyId(9200))
            )
        val wake = CollaborationSpaceIncomingWake(historyId(9300))
        val initial = saved(f.demands().replace(f.local(), null, before, wake))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT, page.checkpoint?.payload)
        assertEquals(1, page.documents.size)
        f.reopen()
        assertEquals(AppResult.Success(initial), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        assertTrue(initial.demand.sources.isEmpty())
        assertEquals(0, initial.demand.nextPosition)
    }

    @Test
    fun historyRecoveryDoesNotAddAPartToTheMaximumRoomAndSourceSnapshot() = withDatabase { f ->
        val before = physicalDemand(64).copy(historyRecovery = retainedHistoryRecovery())
        val saved = saved(f.demands().replace(f.local(), null, before))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(129, page.documents.size)
        assertEquals(130, INCOMING_DEMAND_READ_LIMIT)
        assertEquals(INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT, page.checkpoint?.payload)
        assertTrue(page.documents.all { assertNotNull(it.payload).length <= 131072 })
        assertTrue(page.documents.sumOf { assertNotNull(it.payload).length.toLong() } <= 4_194_304L)
        f.reopen()
        assertEquals(AppResult.Success(saved), f.demands().current(f.local()))
    }

    @Test
    fun historyRecoveryPreservesOriginalTargetsInventoryRequestCursorAndDelay() =
        withDatabase { f ->
            val before =
                typedDemand().let { value ->
                    value.copy(
                        historyRecovery = retainedHistoryRecovery(),
                        sources =
                            value.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(9400),
                                    inventoryAfterRoomId = "!before:test",
                                    inventoryRequestedGeneration = historyId(9401),
                                    inventoryRetry = CollaborationSpaceIncomingRetry(0, 30_000, 1),
                                )
                            },
                    )
                }
            val saved = saved(f.demands().replace(f.local(), null, before))
            f.reopen()
            assertEquals(AppResult.Success(saved), f.demands().current(f.local()))
            assertEquals("before", saved.demand.sources.single().position)
            assertEquals(before.rooms, saved.demand.rooms)
        }

    @Test
    fun primaryQueueUpdatesCannotBeOverwrittenByAnOldRecoverySnapshot() = withDatabase { f ->
        val before = demand().copy(historyRecovery = retainedHistoryRecovery())
        val initial = saved(f.demands().replace(f.local(), null, before))
        val newer =
            saved(
                f.demands()
                    .replace(
                        f.local(),
                        initial,
                        demand(3).copy(historyRecovery = before.historyRecovery),
                    )
            )
        val advanced =
            value(
                planCollaborationSpaceHistoryRecoveryAdvance(
                    assertNotNull(before.historyRecovery),
                    assertNotNull(before.historyRecovery),
                    "collaboration-history/space/v1/a",
                    false,
                    false,
                )
            )
        assertEquals(
            AppResult.Success(null),
            f.demands().replace(f.local(), initial, before.copy(historyRecovery = advanced)),
        )
        assertEquals(AppResult.Success(newer), f.demands().current(f.local()))
    }

    @Test
    fun recoveryResponseLossKeepsTheCommittedTurnAndWakeAcrossRestart() = withDatabase { f ->
        val before = CollaborationSpaceIncomingDemand(historyRecovery = retainedHistoryRecovery())
        val wake = CollaborationSpaceIncomingWake(historyId(9300))
        var attempts = 0
        val original = f.database.replicas
        val store =
            object : ReplicaStore by original {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    attempts++
                    check(original.replace(scope, partition, expected, snapshot, wake))
                    error("fixture lost committed response")
                }
            }
        assertIs<AppResult.Failed>(f.demands(store).replace(f.local(), null, before, wake))
        assertEquals(1, attempts)
        f.reopen()
        assertEquals(before, saved(f.demands().current(f.local())).demand)
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun cancelledRecoveryCommitPropagatesAndRetainsItsAtomicWake() = withDatabase { f ->
        val before = CollaborationSpaceIncomingDemand(historyRecovery = retainedHistoryRecovery())
        val wake = CollaborationSpaceIncomingWake(historyId(9300))
        var attempts = 0
        val original = f.database.replicas
        val store =
            object : ReplicaStore by original {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    attempts++
                    check(original.replace(scope, partition, expected, snapshot, wake))
                    throw CancellationException("fixture cancelled committed response")
                }
            }
        assertFailsWith<CancellationException> {
            f.demands(store).replace(f.local(), null, before, wake)
        }
        assertEquals(1, attempts)
        f.reopen()
        assertEquals(before, saved(f.demands().current(f.local())).demand)
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun completedUnresolvedRecoveryReopensWithoutAnActiveRetry() = withDatabase { f ->
        val done =
            value(
                planCollaborationSpaceHistoryRecoveryAdvance(
                    CollaborationSpaceHistoryRecovery(historyId(9200)),
                    CollaborationSpaceHistoryRecovery(historyId(9200)),
                    null,
                    false,
                    true,
                )
            )
        val before = CollaborationSpaceIncomingDemand(historyRecovery = done)
        val saved = saved(f.demands().replace(f.local(), null, before))
        f.reopen()
        assertEquals(AppResult.Success(saved), f.demands().current(f.local()))
        assertTrue(assertNotNull(saved.demand.historyRecovery).complete)
        assertNull(saved.demand.historyRecovery?.retry)
    }

    @Test
    fun removingRecoveryReturnsToTheExistingInventorySchemaWithoutChangingOtherWork() =
        withDatabase { f ->
            val old =
                typedDemand().let { value ->
                    value.copy(
                        sources =
                            value.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(9400),
                                    inventoryRequestedGeneration = historyId(9401),
                                )
                            }
                    )
                }
            val first =
                saved(
                    f.demands()
                        .replace(
                            f.local(),
                            null,
                            old.copy(historyRecovery = retainedHistoryRecovery()),
                        )
                )
            val cleared = saved(f.demands().replace(f.local(), first, old))
            assertEquals(
                INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT,
                f.database.replicas
                    .checkpoint(incomingDemandScope(f.local()), incomingDemandPartition(f.local()))
                    ?.payload,
            )
            f.reopen()
            assertEquals(AppResult.Success(cleared), f.demands().current(f.local()))
        }

    @Test
    fun olderAuthenticatedHeadersCannotSmuggleHistoryRecovery() = withDatabase { f ->
        saved(
            f.demands()
                .replace(
                    f.local(),
                    null,
                    CollaborationSpaceIncomingDemand(historyRecovery = retainedHistoryRecovery()),
                )
        )
        for ((marker, schema) in
            listOf(
                INCOMING_DEMAND_CHECKPOINT to 1,
                INCOMING_DEMAND_CHECKPOINT to 2,
                INCOMING_DEMAND_PARTS_CHECKPOINT to 3,
                INCOMING_DEMAND_TARGETS_CHECKPOINT to 4,
                INCOMING_DEMAND_INVENTORY_CHECKPOINT to 5,
                INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT to 6,
            )) {
            assertHistoryRecoveryHeaderRejected(f, marker) { fields ->
                if (schema <= 2)
                    JsonObject(
                        mapOf(
                            "schema" to JsonPrimitive(schema),
                            "nextPosition" to JsonPrimitive("0"),
                            "rooms" to JsonArray(emptyList()),
                            "rescanGeneration" to JsonNull,
                            "historyRecovery" to fields.getValue("historyRecovery"),
                        )
                    )
                else JsonObject(fields + ("schema" to JsonPrimitive(schema)))
            }
        }
    }

    @Test
    fun theRecoveryHeaderRequiresItsVersionAndNonNullRecoveryState() = withDatabase { f ->
        saved(
            f.demands()
                .replace(
                    f.local(),
                    null,
                    CollaborationSpaceIncomingDemand(historyRecovery = retainedHistoryRecovery()),
                )
        )
        for (change in 0..2) {
            assertHistoryRecoveryHeaderRejected(f, INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT) {
                fields ->
                when (change) {
                    0 -> JsonObject(fields - "historyRecovery")
                    1 -> JsonObject(fields + ("historyRecovery" to JsonNull))
                    else -> JsonObject(fields + ("schema" to JsonPrimitive(6)))
                }
            }
        }
    }

    @Test
    fun authenticatedRecoveryFieldsRejectAmbiguousCursorsCountersAndCompletedState() =
        withDatabase { f ->
            saved(
                f.demands()
                    .replace(
                        f.local(),
                        null,
                        CollaborationSpaceIncomingDemand(
                            historyRecovery = retainedHistoryRecovery()
                        ),
                    )
            )
            for ((name, bad) in
                listOf(
                    "revision" to JsonPrimitive("02"),
                    "revision" to JsonPrimitive("9007199254740992"),
                    "afterPartition" to JsonPrimitive("x".repeat(161)),
                    "afterPartition" to JsonPrimitive("bad cursor"),
                    "requestedGeneration" to JsonPrimitive(historyId(9200)),
                    "complete" to JsonPrimitive(true),
                    "generation" to JsonPrimitive("bad"),
                )) {
                assertHistoryRecoveryHeaderRejected(
                    f,
                    INCOMING_DEMAND_HISTORY_RECOVERY_CHECKPOINT,
                ) { fields ->
                    JsonObject(
                        fields +
                            ("historyRecovery" to
                                JsonObject(
                                    fields.getValue("historyRecovery").jsonObject + (name to bad)
                                ))
                    )
                }
            }
        }

    @Test
    fun recoveryCoordinatesRemainRootScopedAndSurviveAccountReauthentication() = withDatabase { f ->
        val before = CollaborationSpaceIncomingDemand(historyRecovery = retainedHistoryRecovery())
        val saved = saved(f.demands().replace(f.local(), null, before))
        assertEquals(
            AppResult.Success(null),
            f.demands().current(f.local().copy(spaceId = historyId(402))),
        )
        val renewed = f.access.copy(sessionId = "renewed-history-owner")
        f.reopen(renewed)
        assertEquals(
            AppResult.Success(saved),
            f.demands().current(f.local().copy(sessionId = renewed.sessionId)),
        )
    }

    private fun retainedHistoryRecovery() =
        CollaborationSpaceHistoryRecovery(
            historyId(9200),
            revision = 2,
            afterPartition = "collaboration-history/self/" + historyId(401),
            moreHistoryPages = true,
            unresolved = true,
            requestedGeneration = historyId(9201),
            retry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
        )

    private suspend fun assertHistoryRecoveryHeaderRejected(
        f: CollaborationHistoryFixture,
        marker: String,
        transform: (JsonObject) -> JsonObject,
    ) {
        val before = saved(f.demands().current(f.local()))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        val raw = assertNotNull(page.checkpoint)
        val header = page.documents.single { it.id == INCOMING_DEMAND_DOCUMENT }
        val aad =
            Json.encodeToString(
                    listOf(
                        "hris-collaboration-space-incoming-demand-v1",
                        f.access.audience,
                        f.access.accountId,
                        f.local().spaceId,
                        raw.handle.generation,
                        header.id,
                        "0",
                    )
                )
                .encodeToByteArray()
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
            val bytes = f.cipher.decrypt(key, aad, Base64.decode(assertNotNull(header.payload)))
            val fields =
                try {
                    Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                } finally {
                    bytes.fill(0)
                }
            val altered = transform(fields)
            assertNotEquals(fields, altered)
            val payload =
                Base64.encode(f.cipher.encrypt(key, aad, altered.toString().encodeToByteArray()))
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ) =
                        flowOf(
                            page.copy(
                                checkpoint = raw.copy(payload = marker),
                                documents =
                                    page.documents.map {
                                        if (it.id == header.id) it.copy(payload = payload) else it
                                    },
                            )
                        )
                }
            assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
        }
        assertEquals(AppResult.Success(before), f.demands().current(f.local()))
    }

    @Test
    fun requestedInventoryPassReopensWithActiveCursorRetryOriginalTargetsAndWake() =
        withDatabase { f ->
            val requested =
                typedDemand().let { before ->
                    before.copy(
                        sources =
                            before.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(8800),
                                    inventoryRequestedGeneration = historyId(9100),
                                    inventoryAfterRoomId = "!kept:test",
                                    inventoryRetry =
                                        CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                                )
                            }
                    )
                }
            val wake = CollaborationSpaceIncomingWake(historyId(8801))
            val retained = saved(f.demands().replace(f.local(), null, requested, wake))
            val page =
                f.database.replicas
                    .observe(
                        incomingDemandScope(f.local()),
                        incomingDemandPartition(f.local()),
                        limit = INCOMING_DEMAND_READ_LIMIT,
                    )
                    .first()
            assertEquals(INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT, page.checkpoint?.payload)
            f.reopen()
            assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
            assertEquals(AppResult.Success(wake), f.wakes().pending())
            assertEquals("before", retained.demand.sources.single().position)
        }

    @Test
    fun promotingAndCompletingARequestedPassPreservesOriginalTargetsAndWake() = withDatabase { f ->
        val before =
            typedDemand().let { value ->
                value.copy(
                    sources =
                        value.sources.map {
                            it.copy(
                                inventoryGeneration = historyId(8820),
                                inventoryRequestedGeneration = historyId(9101),
                                inventoryAfterRoomId = "!before:test",
                                inventoryRetry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                            )
                        }
                )
            }
        val wake = CollaborationSpaceIncomingWake(historyId(9150))
        val initial = saved(f.demands().replace(f.local(), null, before, wake))
        val cleared =
            before.copy(
                sources =
                    before.sources.map {
                        it.copy(
                            inventoryGeneration = it.inventoryRequestedGeneration,
                            inventoryRequestedGeneration = null,
                            inventoryAfterRoomId = null,
                            inventoryRetry = null,
                        )
                    }
            )
        val retained = saved(f.demands().replace(f.local(), initial, cleared))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_TARGETS_CHECKPOINT, page.checkpoint?.payload)
        f.reopen()
        assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
        assertEquals(historyId(9101), retained.demand.sources.single().inventoryGeneration)
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        assertEquals(before.rooms, retained.demand.rooms)
        val completed =
            cleared.copy(sources = cleared.sources.map { it.copy(inventoryGeneration = null) })
        val final = saved(f.demands().replace(f.local(), retained, completed))
        f.reopen()
        assertEquals(AppResult.Success(final), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        assertEquals(before.sources.single().position, retained.demand.sources.single().position)
    }

    @Test
    fun invalidRequestedGenerationsCannotReplaceAValidStoredQueueOrItsWake() = withDatabase { f ->
        val before = physicalDemand()
        val wake = CollaborationSpaceIncomingWake(historyId(8830))
        val initial = saved(f.demands().replace(f.local(), null, before, wake))
        val active = before.sources.single().copy(inventoryGeneration = historyId(9120))
        for (source in
            listOf(
                active.copy(
                    inventoryGeneration = null,
                    inventoryRequestedGeneration = historyId(9121),
                ),
                active.copy(inventoryRequestedGeneration = historyId(9120)),
                active.copy(
                    inventoryRequestedGeneration = before.physicalRooms.single().generation
                ),
                active.copy(inventoryRequestedGeneration = "invalid"),
            )) assertIs<AppResult.Failed>(
            f.demands().replace(f.local(), initial, before.copy(sources = listOf(source)))
        )
        f.reopen()
        assertEquals(AppResult.Success(initial), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun olderAuthenticatedHeadersCannotHideTheRequestedInventoryField() = withDatabase { f ->
        val before =
            physicalDemand().let { value ->
                value.copy(
                    sources =
                        value.sources.map {
                            it.copy(
                                inventoryGeneration = historyId(8840),
                                inventoryRequestedGeneration = historyId(9102),
                            )
                        }
                )
            }
        val initial = saved(f.demands().replace(f.local(), null, before))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        val raw = assertNotNull(page.checkpoint)
        val header = page.documents.single { it.id == INCOMING_DEMAND_DOCUMENT }
        val aad =
            Json.encodeToString(
                    listOf(
                        "hris-collaboration-space-incoming-demand-v1",
                        f.access.audience,
                        f.access.accountId,
                        f.local().spaceId,
                        raw.handle.generation,
                        header.id,
                        "0",
                    )
                )
                .encodeToByteArray()
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
            val bytes = f.cipher.decrypt(key, aad, Base64.decode(assertNotNull(header.payload)))
            val original =
                try {
                    bytes.decodeToString()
                } finally {
                    bytes.fill(0)
                }
            for ((marker, schema) in
                listOf(
                    INCOMING_DEMAND_PARTS_CHECKPOINT to 3,
                    INCOMING_DEMAND_TARGETS_CHECKPOINT to 4,
                    INCOMING_DEMAND_INVENTORY_CHECKPOINT to 5,
                )) {
                val old = original.replace("\"schema\":6", "\"schema\":$schema")
                assertNotEquals(original, old)
                val payload = Base64.encode(f.cipher.encrypt(key, aad, old.encodeToByteArray()))
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observe(
                            scope: ReplicaScope,
                            partition: String,
                            after: String?,
                            limit: Int,
                        ) =
                            flowOf(
                                page.copy(
                                    checkpoint = raw.copy(payload = marker),
                                    documents =
                                        page.documents.map {
                                            if (it.id == header.id) it.copy(payload = payload)
                                            else it
                                        },
                                )
                            )
                    }
                assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
                assertIs<AppResult.Failed>(f.demands(store).replace(f.local(), initial, before))
            }
        }
        assertEquals(AppResult.Success(initial), f.demands().current(f.local()))
    }

    @Test
    fun interruptedCommitRetainsActiveAndRequestedPassesWithPhysicalWorkAndWake() =
        withDatabase { f ->
            val before =
                physicalDemand().let { value ->
                    value.copy(
                        sources =
                            value.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(8850),
                                    inventoryRequestedGeneration = historyId(9103),
                                    inventoryAfterRoomId = "!before:test",
                                )
                            }
                    )
                }
            val first =
                saved(
                    f.demands()
                        .replace(
                            f.local(),
                            null,
                            before,
                            CollaborationSpaceIncomingWake(historyId(8851)),
                        )
                )
            val after =
                before.copy(
                    sources =
                        before.sources.map {
                            it.copy(
                                inventoryAfterRoomId = "!next:test",
                                inventoryRequestedGeneration = historyId(9104),
                            )
                        },
                    physicalRooms =
                        before.physicalRooms.map {
                            it.copy(
                                generation = historyId(8852),
                                headEventId = null,
                                limited = true,
                            )
                        },
                )
            val wake = CollaborationSpaceIncomingWake(historyId(8853))
            var attempts = 0
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
                        throw CancellationException("Fixture inventory commit reply loss")
                    }
                }
            assertFailsWith<CancellationException> {
                f.demands(store).replace(f.local(), first, after, wake)
            }
            assertEquals(1, attempts)
            f.reopen()
            assertEquals(after, saved(f.demands().current(f.local())).demand)
            assertEquals(AppResult.Success(wake), f.wakes().pending())
        }

    @Test
    fun maximumRequestedPassesRemainInsideExistingEncryptedSnapshotBounds() = withDatabase { f ->
        val before = physicalDemand(64)
        val requested =
            before.copy(
                sources =
                    before.sources.mapIndexed { index, source ->
                        source.copy(
                            position = "\u0000".repeat(4096),
                            inventoryGeneration = historyId(8900 + index),
                            inventoryRequestedGeneration = historyId(9200 + index),
                            inventoryAfterRoomId = "!" + "漢".repeat(254),
                            inventoryRetry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                        )
                    }
            )
        val retained = saved(f.demands().replace(f.local(), null, requested))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_REQUESTED_INVENTORY_CHECKPOINT, page.checkpoint?.payload)
        assertEquals(129, page.documents.size)
        assertTrue(page.documents.all { requireNotNull(it.payload).length <= 131072 })
        assertTrue(
            page.documents.sumOf { requireNotNull(it.payload).length.toLong() } <= 4_194_304L
        )
        f.reopen()
        assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
    }

    @Test
    fun inventoryCursorAndRetryReopenWithOriginalTargetsAndTheirIndependentWake() =
        withDatabase { f ->
            val requested =
                typedDemand().let { before ->
                    before.copy(
                        sources =
                            before.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(8800),
                                    inventoryAfterRoomId = "!kept:test",
                                    inventoryRetry =
                                        CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                                )
                            }
                    )
                }
            val wake = CollaborationSpaceIncomingWake(historyId(8801))
            val retained = saved(f.demands().replace(f.local(), null, requested, wake))
            val page =
                f.database.replicas
                    .observe(
                        incomingDemandScope(f.local()),
                        incomingDemandPartition(f.local()),
                        limit = INCOMING_DEMAND_READ_LIMIT,
                    )
                    .first()
            assertEquals(INCOMING_DEMAND_INVENTORY_CHECKPOINT, page.checkpoint?.payload)
            f.reopen()
            assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
            assertEquals(AppResult.Success(wake), f.wakes().pending())
            assertEquals("before", retained.demand.sources.single().position)
        }

    @Test
    fun inventoryRetryBeforeFirstPagePersistsWithoutInventingACursor() = withDatabase { f ->
        val before = physicalDemand()
        val requested =
            before.copy(
                sources =
                    before.sources.map {
                        it.copy(
                            inventoryGeneration = historyId(8810),
                            inventoryRetry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                        )
                    }
            )
        val retained = saved(f.demands().replace(f.local(), null, requested))
        f.reopen()
        val actual = saved(f.demands().current(f.local()))
        assertEquals(retained, actual)
        assertNull(actual.demand.sources.single().inventoryAfterRoomId)
        assertEquals(before.physicalRooms, actual.demand.physicalRooms)
    }

    @Test
    fun clearingInventoryProgressPreservesOriginalTargetsAndOpaqueSourcePosition() =
        withDatabase { f ->
            val before =
                typedDemand().let { value ->
                    value.copy(
                        sources =
                            value.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(8820),
                                    inventoryAfterRoomId = "!before:test",
                                    inventoryRetry =
                                        CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                                )
                            }
                    )
                }
            val initial = saved(f.demands().replace(f.local(), null, before))
            val cleared =
                before.copy(
                    sources =
                        before.sources.map {
                            it.copy(
                                inventoryGeneration = null,
                                inventoryAfterRoomId = null,
                                inventoryRetry = null,
                            )
                        }
                )
            val retained = saved(f.demands().replace(f.local(), initial, cleared))
            val page =
                f.database.replicas
                    .observe(
                        incomingDemandScope(f.local()),
                        incomingDemandPartition(f.local()),
                        limit = INCOMING_DEMAND_READ_LIMIT,
                    )
                    .first()
            assertEquals(INCOMING_DEMAND_TARGETS_CHECKPOINT, page.checkpoint?.payload)
            f.reopen()
            assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
            assertEquals(before.rooms, retained.demand.rooms)
            assertEquals(
                before.sources.single().position,
                retained.demand.sources.single().position,
            )
        }

    @Test
    fun orphanInventoryFieldsCannotReplaceAValidStoredQueue() = withDatabase { f ->
        val before = physicalDemand()
        val wake = CollaborationSpaceIncomingWake(historyId(8830))
        val initial = saved(f.demands().replace(f.local(), null, before, wake))
        for (source in
            listOf(
                before.sources.single().copy(inventoryAfterRoomId = "!orphan:test"),
                before.sources
                    .single()
                    .copy(inventoryRetry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1)),
            )) assertIs<AppResult.Failed>(
            f.demands().replace(f.local(), initial, before.copy(sources = listOf(source)))
        )
        f.reopen()
        assertEquals(AppResult.Success(initial), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun anOlderMarkerAndMatchingOlderHeaderCannotHideAuthenticatedInventoryFields() =
        withDatabase { f ->
            val before =
                typedDemand().let { value ->
                    value.copy(
                        sources =
                            value.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(8840),
                                    inventoryAfterRoomId = "!kept:test",
                                )
                            }
                    )
                }
            val initial = saved(f.demands().replace(f.local(), null, before))
            val page =
                f.database.replicas
                    .observe(
                        incomingDemandScope(f.local()),
                        incomingDemandPartition(f.local()),
                        limit = INCOMING_DEMAND_READ_LIMIT,
                    )
                    .first()
            val raw = assertNotNull(page.checkpoint)
            val header = page.documents.single { it.id == INCOMING_DEMAND_DOCUMENT }
            val aad =
                Json.encodeToString(
                        listOf(
                            "hris-collaboration-space-incoming-demand-v1",
                            f.access.audience,
                            f.access.accountId,
                            f.local().spaceId,
                            raw.handle.generation,
                            header.id,
                            "0",
                        )
                    )
                    .encodeToByteArray()
            CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
                val bytes = f.cipher.decrypt(key, aad, Base64.decode(assertNotNull(header.payload)))
                val original =
                    try {
                        bytes.decodeToString()
                    } finally {
                        bytes.fill(0)
                    }
                val old = original.replace("\"schema\":5", "\"schema\":4")
                assertNotEquals(original, old)
                val payload = Base64.encode(f.cipher.encrypt(key, aad, old.encodeToByteArray()))
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observe(
                            scope: ReplicaScope,
                            partition: String,
                            after: String?,
                            limit: Int,
                        ) =
                            flowOf(
                                page.copy(
                                    checkpoint =
                                        raw.copy(payload = INCOMING_DEMAND_TARGETS_CHECKPOINT),
                                    documents =
                                        page.documents.map {
                                            if (it.id == header.id) it.copy(payload = payload)
                                            else it
                                        },
                                )
                            )
                    }
                assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
                assertIs<AppResult.Failed>(f.demands(store).replace(f.local(), initial, before))
            }
            assertEquals(AppResult.Success(initial), f.demands().current(f.local()))
        }

    @Test
    fun interruptedInventoryProgressCommitReopensItsCursorPhysicalWorkAndWakeTogether() =
        withDatabase { f ->
            val before =
                physicalDemand().let { value ->
                    value.copy(
                        sources =
                            value.sources.map {
                                it.copy(
                                    inventoryGeneration = historyId(8850),
                                    inventoryAfterRoomId = "!before:test",
                                )
                            }
                    )
                }
            val first =
                saved(
                    f.demands()
                        .replace(
                            f.local(),
                            null,
                            before,
                            CollaborationSpaceIncomingWake(historyId(8851)),
                        )
                )
            val after =
                before.copy(
                    sources = before.sources.map { it.copy(inventoryAfterRoomId = "!next:test") },
                    physicalRooms =
                        before.physicalRooms.map {
                            it.copy(
                                generation = historyId(8852),
                                headEventId = null,
                                limited = true,
                            )
                        },
                )
            val wake = CollaborationSpaceIncomingWake(historyId(8853))
            var attempts = 0
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
                        throw CancellationException("Fixture inventory commit reply loss")
                    }
                }
            assertFailsWith<CancellationException> {
                f.demands(store).replace(f.local(), first, after, wake)
            }
            assertEquals(1, attempts)
            f.reopen()
            assertEquals(after, saved(f.demands().current(f.local())).demand)
            assertEquals(AppResult.Success(wake), f.wakes().pending())
        }

    @Test
    fun maximumInventoryCursorsAndRetriesStayInsideTheExistingSnapshotBounds() = withDatabase { f ->
        val before = physicalDemand(64)
        val requested =
            before.copy(
                sources =
                    before.sources.mapIndexed { index, source ->
                        source.copy(
                            position = "\u0000".repeat(4096),
                            inventoryGeneration = historyId(8900 + index),
                            inventoryAfterRoomId = "!" + "漢".repeat(254),
                            inventoryRetry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                        )
                    }
            )
        val retained = saved(f.demands().replace(f.local(), null, requested))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_INVENTORY_CHECKPOINT, page.checkpoint?.payload)
        assertEquals(129, page.documents.size)
        assertTrue(page.documents.all { requireNotNull(it.payload).length <= 131072 })
        assertTrue(
            page.documents.sumOf { requireNotNull(it.payload).length.toLong() } <= 4_194_304L
        )
        f.reopen()
        assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
    }

    @Test
    fun interruptedAliasHandoffReopensTheFreshOriginalAndPreservesItsWakeAndSource() =
        withDatabase { f ->
            val source =
                physicalDemand().sources.single().copy(inventoryGeneration = historyId(8600))
            val before = demand(1).copy(sources = listOf(source))
            val wake = CollaborationSpaceIncomingWake(historyId(8601))
            val first = saved(f.demands().replace(f.local(), null, before, wake))
            val captured = before.rooms.single()
            val proposed =
                value(
                        routeCollaborationSpaceIncomingAlias(
                            before,
                            captured,
                            source.source,
                            CollaborationSpaceIncomingTarget.Original(
                                CollaborationOriginalConversationPosition(
                                    historyId(7100),
                                    historyId(1000),
                                )
                            ),
                            historyId(8602),
                        )
                    )
                    .demand
            var attempts = 0
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
                        throw CancellationException("Fictional alias handoff reply loss")
                    }
                }
            assertFailsWith<CancellationException> {
                f.demands(store).replace(f.local(), first, proposed)
            }
            assertEquals(1, attempts)
            f.reopen()
            val retained = saved(f.demands().current(f.local())).demand
            assertEquals(proposed, retained)
            assertEquals(
                AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
                confirmCollaborationSpaceIncomingAliasRouting(
                    source.source,
                    captured,
                    proposed.rooms.single(),
                    retained,
                    false,
                ),
            )
            assertEquals(before.sources, retained.sources)
            assertEquals(before.rescanGeneration, retained.rescanGeneration)
            assertEquals(AppResult.Success(wake), f.wakes().pending())
        }

    @Test
    fun staleAliasHandoffCannotOverwriteACommittedNewProducerHead() = withDatabase { f ->
        val source = physicalDemand().sources.single()
        val before = demand(1).copy(sources = listOf(source))
        val wake = CollaborationSpaceIncomingWake(historyId(8610))
        val first = saved(f.demands().replace(f.local(), null, before, wake))
        val captured = before.rooms.single()
        val proposed =
            value(
                    routeCollaborationSpaceIncomingAlias(
                        before,
                        captured,
                        source.source,
                        CollaborationSpaceIncomingTarget.Original(
                            CollaborationOriginalConversationPosition(
                                historyId(7100),
                                historyId(1000),
                            )
                        ),
                        historyId(8611),
                    )
                )
                .demand
        val newer =
            before.copy(
                rooms =
                    listOf(captured.copy(generation = historyId(8612), headEventId = "\$new-head"))
            )
        val producer = saved(f.demands().replace(f.local(), first, newer))
        assertNull(value(f.demands().replace(f.local(), first, proposed)))
        f.reopen()
        assertEquals(AppResult.Success(producer), f.demands().current(f.local()))
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
            confirmCollaborationSpaceIncomingAliasRouting(
                source.source,
                captured,
                proposed.rooms.single(),
                producer.demand,
                true,
            ),
        )
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun aReopenedUnsourcedAliasStillWaitsForActualSourceProgress() = withDatabase { f ->
        val before = demand(1)
        val wake = CollaborationSpaceIncomingWake(historyId(8620))
        val first = saved(f.demands().replace(f.local(), null, before, wake))
        f.reopen()
        val retained = saved(f.demands().current(f.local()))
        val planned =
            value(
                routeCollaborationSpaceIncomingAlias(
                    retained.demand,
                    retained.demand.rooms.single(),
                    physicalDemand().sources.single().source,
                    CollaborationSpaceIncomingTarget.Original(
                        CollaborationOriginalConversationPosition(historyId(7100), historyId(1000))
                    ),
                    historyId(8621),
                )
            )
        assertEquals(
            CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource,
            planned.outcome,
        )
        assertEquals(first.demand, planned.demand)
        assertTrue(planned.demand.sources.isEmpty())
        assertEquals(AppResult.Success(first), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun authenticatedUnknownOrAmbiguousTargetsAndMixedVersionMarkersAreRejected() =
        withDatabase { f ->
            val first = saved(f.demands().replace(f.local(), null, typedDemand()))
            val page =
                f.database.replicas
                    .observe(
                        incomingDemandScope(f.local()),
                        incomingDemandPartition(f.local()),
                        limit = INCOMING_DEMAND_READ_LIMIT,
                    )
                    .first()
            val raw = assertNotNull(page.checkpoint)
            val document = page.documents.single { it.id == "r-01" }
            val aad =
                Json.encodeToString(
                        listOf(
                            "hris-collaboration-space-incoming-demand-v1",
                            f.access.audience,
                            f.access.accountId,
                            f.local().spaceId,
                            raw.handle.generation,
                            document.id,
                            "0",
                        )
                    )
                    .encodeToByteArray()
            CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
                val bytes =
                    f.cipher.decrypt(key, aad, Base64.decode(assertNotNull(document.payload)))
                val original =
                    try {
                        bytes.decodeToString()
                    } finally {
                        bytes.fill(0)
                    }
                val sourceId = typedDemand().sources.single().source.storeId
                val invalid =
                    listOf(
                        original.replace("\"type\":\"original\"", "\"type\":\"unknown\""),
                        original.replace("\"type\":\"original\"", "\"type\":\"published\""),
                        original.replace(historyId(7100), "00000000-0000-0000-0000-000000000000"),
                        original.replace(",\"sourceId\":\"$sourceId\"", ""),
                    )
                for (bad in invalid) {
                    assertNotEquals(original, bad)
                    val payload = Base64.encode(f.cipher.encrypt(key, aad, bad.encodeToByteArray()))
                    val store =
                        object : ReplicaStore by f.database.replicas {
                            override fun observe(
                                scope: ReplicaScope,
                                partition: String,
                                after: String?,
                                limit: Int,
                            ) =
                                flowOf(
                                    page.copy(
                                        documents =
                                            page.documents.map {
                                                if (it.id == document.id) it.copy(payload = payload)
                                                else it
                                            }
                                    )
                                )
                        }
                    assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
                    assertIs<AppResult.Failed>(
                        f.demands(store).replace(f.local(), first, typedDemand())
                    )
                }
            }
            for (marker in listOf(INCOMING_DEMAND_PARTS_CHECKPOINT, "incoming-demand/v5")) {
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observe(
                            scope: ReplicaScope,
                            partition: String,
                            after: String?,
                            limit: Int,
                        ) = flowOf(page.copy(checkpoint = raw.copy(payload = marker)))
                    }
                assertIs<AppResult.Failed>(f.demands(store).current(f.local()))
            }
            assertEquals(AppResult.Success(first), f.demands().current(f.local()))
        }

    @Test
    fun originalTargetsUpgradeExistingPartsWithoutDiscardingPhysicalHintsOrSourcePositions() =
        withDatabase { f ->
            val wake = CollaborationSpaceIncomingWake(historyId(8400))
            val before = physicalDemand(2)
            val first = saved(f.demands().replace(f.local(), null, before, wake))
            val next =
                before.copy(
                    nextPosition = 5,
                    rooms =
                        typedDemand().rooms.mapIndexed { index, room ->
                            room.copy(position = 2L + index)
                        },
                )
            val retained = saved(f.demands().replace(f.local(), first, next))
            val raw =
                f.database.replicas.checkpoint(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                )
            assertEquals(INCOMING_DEMAND_TARGETS_CHECKPOINT, raw?.payload)
            f.reopen()
            assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
            assertEquals(before.physicalRooms, retained.demand.physicalRooms)
            assertEquals(before.sources, retained.demand.sources)
            assertEquals(AppResult.Success(wake), f.wakes().pending())
        }

    @Test
    fun interruptedTypedCommitReopensItsOriginalTargetsAndSingleWakeWithoutReplay() =
        withDatabase { f ->
            var attempts = 0
            val wake = CollaborationSpaceIncomingWake(historyId(8401))
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
                        throw CancellationException("Fictional typed commit response loss")
                    }
                }
            assertFailsWith<CancellationException> {
                f.demands(store).replace(f.local(), null, typedDemand(), wake)
            }
            assertEquals(1, attempts)
            f.reopen()
            assertEquals(typedDemand(), saved(f.demands().current(f.local())).demand)
            assertEquals(AppResult.Success(wake), f.wakes().pending())
        }

    @Test
    fun completedOriginalTargetsReturnToLegacyPartsWithoutLosingPublishedWorkOrWake() =
        withDatabase { f ->
            val wake = CollaborationSpaceIncomingWake(historyId(8200))
            val first = saved(f.demands().replace(f.local(), null, typedDemand(), wake))
            val next =
                first.demand.copy(
                    rooms =
                        first.demand.rooms.filter {
                            it.target is CollaborationSpaceIncomingTarget.Published
                        }
                )
            val retained = saved(f.demands().replace(f.local(), first, next))
            val raw =
                f.database.replicas.checkpoint(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                )
            assertEquals(INCOMING_DEMAND_PARTS_CHECKPOINT, raw?.payload)
            f.reopen()
            assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
            assertEquals(first.demand.sources, retained.demand.sources)
            assertEquals(AppResult.Success(wake), f.wakes().pending())
        }

    @Test
    fun maximumTypedTargetsAndSourcesKeepTheExistingAtomicPartBudget() = withDatabase { f ->
        val source = physicalDemand(64)
        val requested =
            demand(64)
                .copy(
                    rooms =
                        demand(64).rooms.mapIndexed { index, room ->
                            room.copy(
                                target =
                                    CollaborationSpaceIncomingTarget.Original(
                                        CollaborationOriginalConversationPosition(
                                            historyId(7200 + index),
                                            historyId(7300 + index),
                                        )
                                    ),
                                sourceId = source.sources[index].source.storeId,
                            )
                        },
                    sources = source.sources,
                )
        val wake = CollaborationSpaceIncomingWake(historyId(8201))
        val retained = saved(f.demands().replace(f.local(), null, requested, wake))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_TARGETS_CHECKPOINT, page.checkpoint?.payload)
        assertEquals(129, page.documents.size)
        assertTrue(page.documents.sumOf { assertNotNull(it.payload).length.toLong() } <= 4_194_304L)
        assertTrue(page.documents.all { it.tags.isEmpty() && it.id == it.sortKey })
        f.reopen()
        assertEquals(AppResult.Success(retained), f.demands().current(f.local()))
        assertEquals(AppResult.Success(wake), f.wakes().pending())
    }

    @Test
    fun originalTargetsWithoutAValidCompanyOrSourceCannotCreateStorageOrKeys() = withDatabase { f ->
        val valid = typedDemand()
        val original = valid.rooms[1]
        val target = assertIs<CollaborationSpaceIncomingTarget.Original>(original.target)
        val invalid =
            listOf(
                valid.copy(rooms = listOf(original.copy(sourceId = null))),
                valid.copy(
                    rooms =
                        listOf(
                            original.copy(
                                target =
                                    target.copy(
                                        position =
                                            target.position.copy(
                                                companyId = "00000000-0000-0000-0000-000000000000"
                                            )
                                    )
                            )
                        )
                ),
                valid.copy(
                    rooms =
                        listOf(original, original.copy(generation = historyId(8300), position = 2))
                ),
            )
        for (value in invalid) assertIs<AppResult.Failed>(
            f.demands()
                .replace(f.local(), null, value, CollaborationSpaceIncomingWake(historyId(8202)))
        )
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(null), f.demands().current(f.local()))
        assertEquals(AppResult.Success(null), f.wakes().pending())
    }

    @Test
    fun typedTargetsWithSameConversationIdReopenInTheirExactNamespaces() = withDatabase { f ->
        val requested = typedDemand()
        val wake = CollaborationSpaceIncomingWake(historyId(8100))
        val retained = saved(f.demands().replace(f.local(), null, requested, wake))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        assertEquals(INCOMING_DEMAND_TARGETS_CHECKPOINT, page.checkpoint?.payload)
        assertEquals(5, page.documents.size)
        val encrypted = page.documents.joinToString { it.payload.orEmpty() }
        assertFalse(encrypted.contains(historyId(7100)))
        assertFalse(encrypted.contains(historyId(7101)))
        val replacement =
            f.access.copy(sessionId = "replacement-native-parent", companyId = historyId(8101))
        f.reopen(replacement)
        assertEquals(
            AppResult.Success(retained),
            f.demands().current(f.local().copy(sessionId = replacement.sessionId)),
        )
        assertEquals(AppResult.Success(wake), f.wakes().pending())
        assertEquals(requested.rooms.map { it.target }, retained.demand.rooms.map { it.target })
    }

    @Test
    fun publishedDemandStillEncodesTheExactLegacyPayload() = withDatabase { f ->
        saved(f.demands().replace(f.local(), null, demand()))
        val page =
            f.database.replicas
                .observe(
                    incomingDemandScope(f.local()),
                    incomingDemandPartition(f.local()),
                    limit = INCOMING_DEMAND_READ_LIMIT,
                )
                .first()
        val raw = assertNotNull(page.checkpoint)
        assertEquals(INCOMING_DEMAND_CHECKPOINT, raw.payload)
        val document = page.documents.single()
        val aad =
            Json.encodeToString(
                    listOf(
                        "hris-collaboration-space-incoming-demand-v1",
                        f.access.audience,
                        f.access.accountId,
                        f.local().spaceId,
                        raw.handle.generation,
                        document.id,
                        "0",
                    )
                )
                .encodeToByteArray()
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
            val bytes = f.cipher.decrypt(key, aad, Base64.decode(assertNotNull(document.payload)))
            try {
                val expected =
                    """{"schema":1,"nextPosition":"2","rooms":[{"conversationId":"${historyId(1000)}","generation":"${historyId(2000)}","position":"0","headEventId":"${'$'}head-0","phase":"Latest"},{"conversationId":"${historyId(1001)}","generation":"${historyId(2001)}","position":"1","headEventId":"${'$'}head-1","phase":"Provider"}],"rescanGeneration":"${historyId(9000)}"}"""
                assertEquals(expected, bytes.decodeToString())
            } finally {
                bytes.fill(0)
            }
        }
    }

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
        assertFalse(
            encrypted.contains(
                assertIs<CollaborationSpaceIncomingTarget.Published>(
                        first.demand.rooms.first().target
                    )
                    .conversationId
            )
        )
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

    private fun typedDemand(): CollaborationSpaceIncomingDemand {
        val source = physicalDemand().sources.single()
        val id = historyId(1000)
        val targets =
            listOf(
                CollaborationSpaceIncomingTarget.Published(id),
                CollaborationSpaceIncomingTarget.Original(
                    CollaborationOriginalConversationPosition(historyId(7100), id)
                ),
                CollaborationSpaceIncomingTarget.Original(
                    CollaborationOriginalConversationPosition(historyId(7101), id)
                ),
            )
        val base = demand(3)
        return base.copy(
            rooms =
                base.rooms.mapIndexed { index, room ->
                    room.copy(target = targets[index], sourceId = source.source.storeId)
                },
            sources = listOf(source),
        )
    }

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
