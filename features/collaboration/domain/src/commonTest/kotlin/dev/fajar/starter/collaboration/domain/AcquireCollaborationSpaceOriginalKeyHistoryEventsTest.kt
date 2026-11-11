package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationSpaceOriginalKeyHistoryEventsTest {
    private fun fixture() = SpaceOriginalIncomingContentFixture().also { it.local.snapshot = null }

    private fun history(f: SpaceOriginalIncomingContentFixture) =
        CollaborationSpaceOriginalKeyHistory(
            f.local.source,
            f.request.conversation,
            f.request.traversal.copy(deviceId = f.local.id(950)),
        )

    private suspend fun success(
        f: SpaceOriginalIncomingContentFixture,
        ids: List<String> = listOf("\$original"),
    ) =
        assertIs<AppResult.Success<CollaborationSpaceOriginalKeyHistoryBatch>>(
                f.acquire(f.runtime, history(f), ids)
            )
            .value

    @Test
    fun historicalDeviceUsesTheCurrentNativeSourceWithoutAnyPrimaryQueue() = runTest {
        val f = fixture()
        val values = List(16) { f.original("\$event$it").copy(logicalId = f.local.id(300 + it)) }
        values.forEach { f.put(f.source(it)) }
        val result = success(f, values.map { it.eventId })
        assertEquals(history(f), result.history)
        assertNotEquals(f.base.traversal.deviceId, result.history.traversal.deviceId)
        assertEquals(values, f.originalWrites.single())
        assertEquals(16, result.items.size)
        assertFalse(
            f.base.calls.any {
                it in setOf("demand", "transport", "acquire", "resume", "append", "settle")
            }
        )
        assertTrue(f.base.requests.isEmpty())
        assertTrue(f.base.saved.isEmpty())
        assertNull(f.local.snapshot)
    }

    @Test
    fun invalidEventInputsFailBeforeAnyIo() = runTest {
        for (ids in
            listOf(
                emptyList(),
                List(17) { "\$event$it" },
                listOf("\$one", "\$one"),
                listOf("bad"),
            )) {
            val f = fixture()
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), ids))
            assertTrue(f.base.calls.isEmpty())
        }
    }

    @Test
    fun malformedHistoricalCoordinatesCannotReachNativeReads() = runTest {
        val f = fixture()
        val good = history(f)
        for (bad in
            listOf(
                good.copy(traversal = good.traversal.copy(deviceId = "bad")),
                good.copy(traversal = good.traversal.copy(conversationId = f.local.id(951))),
                good.copy(traversal = good.traversal.copy(roomId = "!other:example.test")),
                good.copy(
                    traversal = good.traversal.copy(purpose = CollaborationHistoryPurpose.Browse)
                ),
                good.copy(source = good.source.copy(storeId = "bad")),
                good.copy(conversation = good.conversation.copy(room = null)),
            )) {
            assertIs<AppResult.Failed>(f.acquire(f.runtime, bad, listOf("\$original")))
            assertTrue(f.base.calls.isEmpty())
        }
    }

    @Test
    fun malformedRuntimeIdentifiersFailBeforeAcquisition() = runTest {
        val f = fixture()
        for (runtime in
            listOf(f.runtime.copy(deviceId = "bad"), f.runtime.copy(operationId = "bad"))) {
            assertIs<AppResult.Failed>(f.acquire(runtime, history(f), listOf("\$original")))
            assertTrue(f.base.calls.isEmpty())
        }
    }

    @Test
    fun staleCapturedNativeSourceIsRejectedWithoutSubstitutingItsDevice() = runTest {
        for (mode in 0..2) {
            val f = fixture()
            val good = history(f)
            val wrong =
                when (mode) {
                    0 -> good.source.copy(storeId = "b".repeat(64))
                    1 -> good.source.copy(matrixUserId = "@other:example.test")
                    else -> good.source.copy(matrixDeviceId = "OTHER")
                }
            assertIs<AppResult.Failed>(
                f.acquire(f.runtime, good.copy(source = wrong), listOf("\$original"))
            )
            assertFalse(f.base.calls.any { it.startsWith("event:") })
        }
    }

    @Test
    fun absentSourceRegistrationAndMetadataRemainUnresolvedFailures() = runTest {
        for (mode in 0..2) {
            val f = fixture()
            val capture = history(f)
            when (mode) {
                0 -> f.local.installed = null
                1 -> f.local.registration = null
                else -> f.local.retained = null
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, capture, listOf("\$original")))
            assertFalse(f.base.calls.any { it.startsWith("event:") })
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun anotherRegisteredCompanyCannotAuthorizeHistoricalContent() = runTest {
        val f = fixture()
        val registered = assertNotNull(f.local.registration)
        val legacy = assertNotNull(registered.command.legacy)
        f.local.registration =
            registered.copy(
                command =
                    registered.command.copy(
                        legacy =
                            legacy.copy(binding = legacy.binding.copy(companyId = f.local.id(952)))
                    )
            )
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
        assertFalse(f.base.calls.any { it.startsWith("event:") })
    }

    @Test
    fun aNativeSnapshotCannotPretendItsDeviceIsTheHistoricalDevice() = runTest {
        val f = fixture()
        f.put(f.source())
        val snapshot = f.snapshots.getValue("\$original")
        f.snapshots["\$original"] =
            snapshot.copy(
                physical = snapshot.physical.copy(deviceId = history(f).traversal.deviceId)
            )
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun foreignRuntimeAccountCompanyRoomOrEventSnapshotsAreRejected() = runTest {
        val seed = fixture()
        seed.put(seed.source())
        val snapshot = seed.snapshots.getValue("\$original")
        val physical = snapshot.physical
        val variants =
            listOf(
                snapshot.copy(runtime = seed.runtime.copy(operationId = seed.local.id(952))),
                snapshot.copy(
                    physical =
                        physical.copy(owner = physical.owner.copy(accountId = seed.local.id(952)))
                ),
                snapshot.copy(
                    physical =
                        physical.copy(owner = physical.owner.copy(companyId = seed.local.id(952)))
                ),
                snapshot.copy(physical = physical.copy(roomId = "!other:example.test")),
                snapshot.copy(physical = physical.copy(roomJoined = false)),
                snapshot.copy(physical = physical.copy(roomEncrypted = false)),
                snapshot.copy(
                    physical = physical.copy(event = physical.event.copy(eventId = "\$other"))
                ),
            )
        for (bad in variants) {
            val f = fixture()
            f.snapshots["\$original"] = bad
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun lateNativeSourceAndRuntimeReplacementBeatRawSuccess() = runTest {
        for (source in listOf(false, true)) {
            val f = fixture()
            f.put(f.source())
            f.base.hooks["event:\$original"] = {
                if (source) f.local.installed = null else f.base.open = false
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun lateRegistrationOrMetadataChangeCannotRetainAcquiredContent() = runTest {
        for (registration in listOf(false, true)) {
            val f = fixture()
            f.put(f.source())
            f.base.hooks["event:\$original"] = {
                if (registration) f.local.registration = null else f.local.retained = null
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun accountReplacementAndConfirmedDenialWinAfterARead() = runTest {
        for (denial in listOf(false, true)) {
            val f = fixture()
            f.put(f.source())
            f.base.hooks["event:\$original"] = {
                if (denial) f.local.directory.deny() else f.native.setup.session = null
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun expiredNativePreparationDoesNotStartContentIo() = runTest {
        val f = fixture()
        f.native.setup.now += 1.days
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
        assertFalse(f.base.calls.any { it.startsWith("event:") })
    }

    @Test
    fun monotonicDeadlineAndBackwardWallClockPreventLateRetention() = runTest {
        for (backward in listOf(false, true)) {
            val f = fixture()
            f.put(f.source())
            f.base.hooks["event:\$original"] = {
                if (backward) f.native.setup.now -= 1.seconds else f.native.time += 15.seconds
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun cancellingAPendingHistoricalReadDoesNotRetryOrCommit() = runTest {
        val f = fixture()
        f.put(f.source())
        val entered = CompletableDeferred<Unit>()
        f.base.hooks["event:\$original"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.acquire(f.runtime, history(f), listOf("\$original")) }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun aProviderOutageStaysAFailureWithoutLocalDenialOrHiddenRetry() = runTest {
        val f = fixture()
        f.put(f.source())
        f.base.failures["event:\$original"] = f.local.directory.unavailable
        assertEquals(
            f.local.directory.unavailable,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
                .failure,
        )
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertEquals(0, f.local.directory.restrictions)
    }

    @Test
    fun sdkDenialSurvivesFailedRestrictionPersistenceAndCleanup() = runTest {
        val f = fixture()
        f.put(f.source())
        val denied = f.local.directory.denied.copy(code = "collaboration_peer_unavailable")
        f.base.failures["event:\$original"] = denied
        f.local.directory.afterRestrict = { error("fixture persistence failure") }
        f.base.hooks["close"] = { error("fixture cleanup failure") }
        assertEquals(
            denied,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
                .failure,
        )
        assertEquals(1, f.local.directory.restrictions)
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun unavailableKeysReturnWaitingOutcomesWithoutAnotherAttempt() = runTest {
        val f = fixture()
        f.put(
            f.source()
                .copy(
                    type = "m.room.encrypted",
                    content =
                        CollaborationAcquiredEventContent.Unresolved(
                            CollaborationEventDecryptionFailure.UnableToDecrypt
                        ),
                )
        )
        val batch = success(f)
        assertIs<CollaborationIncomingHistoryOutcome.AwaitDecryption>(batch.items.single().outcome)
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertTrue(f.originalWrites.isEmpty())
        assertTrue(f.editWrites.isEmpty())
        assertNull(f.local.snapshot)
    }

    @Test
    fun acquiredKeysCanRetainTheSameHistoricalReferencesOnALaterExplicitAttempt() = runTest {
        val f = fixture()
        f.put(
            f.source()
                .copy(
                    type = "m.room.encrypted",
                    content =
                        CollaborationAcquiredEventContent.Unresolved(
                            CollaborationEventDecryptionFailure.UnableToDecrypt
                        ),
                )
        )
        assertIs<CollaborationIncomingHistoryOutcome.AwaitDecryption>(
            success(f).items.single().outcome
        )
        f.put(f.source())
        val retained = success(f)
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(retained.items.single().outcome)
        assertEquals(2, f.base.calls.count { it == "event:\$original" })
        assertEquals(history(f), retained.history)
        assertFalse(f.base.calls.any { it == "transport" })
    }

    @Test
    fun aCoherentLocalOriginalAvoidsATargetRequestForAnEdit() = runTest {
        val f = fixture()
        f.local()
        f.put(f.replacement())
        val result = success(f, listOf("\$edit"))
        assertEquals(listOf("event:\$edit"), f.base.calls.filter { it.startsWith("event:") })
        assertTrue(f.originalWrites.isEmpty())
        assertEquals(1, f.editWrites.single().size)
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(result.items.single().outcome)
    }

    @Test
    fun editsShareOneTargetAcquisitionInsideTheBoundedBatch() = runTest {
        val f = fixture()
        f.put(f.source())
        f.put(f.replacement())
        f.put(f.replacement("\$edit2"))
        success(f, listOf("\$edit", "\$edit2"))
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertEquals(listOf("\$edit", "\$edit2"), f.editWrites.single().map { it.eventId })
        assertEquals(1, f.originalWrites.single().size)
    }

    @Test
    fun aRedactedTargetRetainsATombstoneWithoutConfirmingItsMissingEdit() = runTest {
        val f = fixture()
        f.put(
            f.source()
                .copy(
                    type = "m.room.encrypted",
                    content = CollaborationAcquiredEventContent.Redacted("\$delete"),
                )
        )
        f.put(f.replacement())
        val result = success(f, listOf("\$edit"))
        assertIs<CollaborationIncomingHistoryOutcome.MissingOriginal>(result.items.single().outcome)
        assertIs<CollaborationHistoryChange.Redacted>(f.originalWrites.single().single())
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun falseAndUncertainStorageRepliesAreNotCompletedBatches() = runTest {
        for (result in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "fixture storage failure")),
            )) {
            val f = fixture()
            f.put(f.source())
            f.originalResponse = result
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$original")))
            assertEquals(1, f.originalWrites.size)
            assertTrue(f.editWrites.isEmpty())
        }
    }

    @Test
    fun denialAfterOriginalCommitStopsEditsAndCompletion() = runTest {
        val f = fixture()
        f.put(f.source())
        f.put(f.replacement())
        f.base.hooks["merge"] = { f.local.directory.deny() }
        assertEquals(
            f.local.directory.denied,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), listOf("\$edit"))).failure,
        )
        assertEquals(1, f.originalWrites.size)
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun inputEventIdsAreCapturedBeforeSuspendedAdmission() = runTest {
        val f = fixture()
        f.put(f.source())
        val ids = mutableListOf("\$original")
        f.base.hooks["metadata"] = { ids[0] = "\$different" }
        val result = success(f, ids)
        assertEquals(listOf("\$original"), result.items.map { it.sourceEventId })
    }

    @Test
    fun primaryQueueAndRecoveryStateChangesDoNotBecomeContentAuthority() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        assertTrue(assertNotNull(f.local.snapshot).demand.rooms.isNotEmpty())
        f.put(f.source())
        f.base.failures["demand"] = f.local.directory.storageFailure
        f.base.hooks["event:\$original"] = {
            f.local.demand(
                CollaborationSpaceIncomingDemand(
                    historyRecovery = CollaborationSpaceHistoryRecovery(f.local.id(960))
                )
            )
        }
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(success(f).items.single().outcome)
        assertFalse(f.base.calls.any { it == "demand" })
        assertEquals(
            f.local.id(960),
            assertNotNull(f.local.snapshot).demand.historyRecovery?.generation,
        )
    }
}
