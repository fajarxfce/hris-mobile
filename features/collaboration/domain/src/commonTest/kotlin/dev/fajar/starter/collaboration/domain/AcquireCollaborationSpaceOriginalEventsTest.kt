package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationSpaceOriginalEventsTest {
    @Test
    fun providerCaptureRetainsOriginalContentUnderItsExactCompanyAndDevice() = runTest {
        val f = SpaceOriginalIncomingContentFixture(CollaborationSpaceIncomingPhase.Provider)
        f.put(f.source())
        val result = success(f, listOf("\$original"))
        assertEquals(f.request, result.room)
        assertEquals(f.physical.companyId, result.room.conversation.conversation.position.companyId)
        assertNotEquals(f.runtime.deviceId, result.room.traversal.deviceId)
        assertEquals(1, f.originalWrites.size)
        assertTrue(f.base.saved.isEmpty())
        assertTrue(f.base.requests.isEmpty())
    }

    @Test
    fun rejectsEmptyOversizedRepeatedAndMalformedInputsBeforeIo() = runTest {
        for (ids in
            listOf(
                emptyList(),
                List(17) { "\$event$it" },
                listOf("\$one", "\$one"),
                listOf("bad"),
            )) {
            val f = SpaceOriginalIncomingContentFixture()
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, ids))
            assertTrue(f.base.calls.isEmpty())
        }
    }

    @Test
    fun oneBatchKeepsOriginalCompanyDeviceAndNeverCreatesTransportOrSendWork() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        val values = List(16) { f.original("\$event$it").copy(logicalId = f.local.id(300 + it)) }
        values.forEach { f.put(f.source(it)) }
        val result = success(f, values.map { it.eventId })
        assertEquals(values, f.originalWrites.single())
        assertEquals(f.request, result.room)
        assertNotEquals(f.runtime.deviceId, result.room.traversal.deviceId)
        assertEquals(f.physical.companyId, result.room.conversation.conversation.position.companyId)
        assertTrue(f.base.requests.isEmpty())
        assertTrue(f.base.saved.isEmpty())
        assertEquals(16, result.items.size)
        assertEquals(0, f.base.calls.count { it == "transport" })
        assertEquals(16, f.base.calls.count { it.startsWith("event:") })
        assertFalse(
            f.base.calls.any { it in setOf("acquire", "snapshot", "resume", "settle", "append") }
        )
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun coherentLocalOriginalAvoidsTargetTransportAndPreservesEditIdentity() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.local()
        val edit = f.replacement()
        f.put(edit)
        val result = success(f, listOf(edit.eventId))
        assertEquals(listOf("event:\$edit"), f.base.calls.filter { it.startsWith("event:") })
        assertTrue(f.originalWrites.isEmpty())
        assertEquals(
            listOf((edit.content as CollaborationAcquiredEventContent.Replacement).edit),
            f.editWrites.single(),
        )
        assertEquals(
            CollaborationIncomingHistoryOutcome.Retained(emptyList(), listOf(edit.eventId)),
            result.items.single().outcome,
        )
    }

    @Test
    fun missingTargetIsReadOncePerInvocationAndOriginalsCommitBeforeReplacements() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.put(f.replacement())
        f.put(f.replacement("\$edit2"))
        success(f, listOf("\$edit", "\$edit2"))
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertEquals(listOf("merge", "mergeEdits"), f.base.calls.filter { it.startsWith("merge") })
        assertTrue(f.originalWrites.single().all { it.eventId == "\$original" })
        assertEquals(listOf("\$edit", "\$edit2"), f.editWrites.single().map { it.eventId })
    }

    @Test
    fun exactRedactedTargetRetainsItsSenderAndDoesNotConfirmAMissingOriginalEdit() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(
            f.source()
                .copy(
                    type = "m.room.encrypted",
                    content = CollaborationAcquiredEventContent.Redacted("\$delete"),
                )
        )
        f.put(f.replacement())
        val result = success(f, listOf("\$edit"))
        assertEquals(
            CollaborationIncomingHistoryOutcome.MissingOriginal("\$original"),
            result.items.single().outcome,
        )
        assertEquals(
            listOf(CollaborationHistoryChange.Redacted("\$original", f.original().sender)),
            f.originalWrites.single(),
        )
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun missingKeysRemainExplicitAndAChangedEventCanBeAcquiredAgain() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
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
        assertTrue(f.originalWrites.isEmpty())
        f.put(f.source())
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(success(f).items.single().outcome)
        assertEquals(0, f.base.calls.count { it == "transport" })
        assertEquals(2, f.base.calls.count { it == "event:\$original" })
    }

    @Test
    fun redactionTriggerWaitsForConfirmationWithoutInventingATombstone() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.put(
            CollaborationAcquiredEvent(
                "\$delete",
                "@redactor:example.test",
                300,
                "m.room.redaction",
                CollaborationAcquiredEventContent.Redaction("\$original"),
            )
        )
        assertEquals(
            CollaborationIncomingHistoryOutcome.AwaitRedaction("\$original"),
            success(f, listOf("\$delete")).items.single().outcome,
        )
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun referencedReplacementDoesNotRecursivelyTraverseAnotherTarget() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.replacement())
        f.put(f.replacement("\$original", "\$older"))
        assertEquals(
            CollaborationIncomingHistoryOutcome.Unsupported,
            success(f, listOf("\$edit")).items.single().outcome,
        )
        assertFalse(f.base.calls.any { it == "event:\$older" })
        assertTrue(f.originalWrites.isEmpty())
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun wrongPhysicalOwnerDeviceRoomOrLogicalRuntimeNeverReachesStorage() = runTest {
        for (phase in
            listOf(
                CollaborationSpaceIncomingPhase.Provider,
                CollaborationSpaceIncomingPhase.Pending,
            )) {
            val seed = SpaceOriginalIncomingContentFixture(phase)
            seed.put(seed.source())
            val snap = seed.snapshots.getValue("\$original")
            val p = snap.physical
            val variants =
                listOf(
                    snap.copy(runtime = snap.runtime.copy(operationId = seed.local.id(999))),
                    snap.copy(physical = p.copy(owner = p.owner.copy(sessionId = "different"))),
                    snap.copy(
                        physical =
                            p.copy(owner = p.owner.copy(audience = "https://different.example"))
                    ),
                    snap.copy(
                        physical = p.copy(owner = p.owner.copy(accountId = seed.local.id(999)))
                    ),
                    snap.copy(
                        physical = p.copy(owner = p.owner.copy(companyId = seed.local.id(999)))
                    ),
                    snap.copy(
                        physical =
                            p.copy(owner = p.owner.copy(nativeSessionId = seed.local.id(998)))
                    ),
                    snap.copy(physical = p.copy(deviceId = seed.local.id(999))),
                    snap.copy(physical = p.copy(roomId = "!other:example.test")),
                    snap.copy(physical = p.copy(roomJoined = false)),
                    snap.copy(physical = p.copy(roomEncrypted = false)),
                    snap.copy(physical = p.copy(event = p.event.copy(eventId = "\$different"))),
                )
            for (value in variants) {
                val f = SpaceOriginalIncomingContentFixture(phase)
                f.snapshots["\$original"] = value
                assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
                assertTrue(f.originalWrites.isEmpty())
            }
        }
    }

    @Test
    fun targetCannotSwitchPhysicalNativeNamespaceWithinAnAdmittedBatch() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.put(f.replacement())
        val old = f.snapshots.getValue("\$original")
        f.snapshots["\$original"] =
            old.copy(
                physical =
                    old.physical.copy(
                        owner = old.physical.owner.copy(nativeSessionId = f.local.id(999))
                    )
            )
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$edit")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun lateAccountSwitchDenialAndRoomReplacementBeatSuccessfulRawReads() = runTest {
        for (change in 0..2) {
            val f = SpaceOriginalIncomingContentFixture()
            f.put(f.source())
            f.base.hooks["event:\$original"] = {
                when (change) {
                    0 -> f.native.setup.session = null
                    1 -> f.local.directory.deny()
                    2 -> f.local.retained = null
                }
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun runtimeReplacementDuringMetadataRecheckCannotReachStorage() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.base.hooks["event:\$original"] = { f.base.hooks["metadata"] = { f.base.open = false } }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun sdkDenialWinsEvenWhenRestrictionPersistenceAndRuntimeCleanupFail() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        val denied = f.local.directory.denied.copy(code = "collaboration_peer_unavailable")
        f.base.failures["event:\$original"] = denied
        f.local.directory.afterRestrict = {
            throw IllegalStateException("Fictional persistence loss")
        }
        f.base.hooks["close"] = { throw IllegalStateException("Fictional cleanup loss") }
        assertEquals(
            denied,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
                .failure,
        )
        assertEquals(1, f.local.directory.restrictions)
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun providerOutageKeepsClassificationWithoutRetryingOrDenyingLocalReads() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.base.failures["event:\$original"] = f.local.directory.unavailable
        assertEquals(
            f.local.directory.unavailable,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
                .failure,
        )
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertEquals(0, f.local.directory.restrictions)
    }

    @Test
    fun secondCommitFailureRetainsOriginalWorkAndReplayUsesTheSameEventIdentities() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.put(f.replacement())
        f.editResponse = AppResult.Failed(f.local.directory.storageFailure)
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$edit")))
        assertEquals(1, f.originalWrites.size)
        assertEquals(1, f.editWrites.size)
        f.editResponse = AppResult.Success(true)
        success(f, listOf("\$edit"))
        assertEquals(f.originalWrites[0], f.originalWrites[1])
        assertEquals(f.editWrites[0], f.editWrites[1])
        assertFalse(f.base.calls.any { it in setOf("acquire", "resume", "append") })
    }

    @Test
    fun falseOriginalCommitAndFalseEditCommitAreFailuresNeverCompletedBatches() = runTest {
        for (first in listOf(true, false)) {
            val f = SpaceOriginalIncomingContentFixture()
            f.put(f.source())
            f.put(f.replacement())
            if (first) f.originalResponse = AppResult.Success(false)
            else f.editResponse = AppResult.Success(false)
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$edit")))
            assertEquals(if (first) 0 else 1, f.editWrites.size)
        }
    }

    @Test
    fun deniedAfterOriginalCommitDoesNotWriteEditsOrReportCompletion() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.put(f.replacement())
        f.base.hooks["merge"] = { f.local.directory.deny() }
        assertEquals(
            f.local.directory.denied,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$edit"))).failure,
        )
        assertEquals(1, f.originalWrites.size)
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun elapsedInvocationDeadlinePreventsLateStorage() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.base.hooks["event:\$original"] = { f.native.time += 15.seconds }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun cancellationPropagatesWithoutCommittingOrStartingAnotherRequest() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        val entered = CompletableDeferred<Unit>()
        f.base.hooks["event:\$original"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.acquire(f.runtime, f.request, listOf("\$original")) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.originalWrites.isEmpty())
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
    }

    @Test
    fun callerMutationAfterSuspensionCannotChangeTheCapturedInput() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        val ids = mutableListOf("\$original")
        f.base.hooks["metadata"] = { ids[0] = "\$different" }
        assertEquals("\$original", success(f, ids).items.single().sourceEventId)
    }

    @Test
    fun incoherentLocalAliasFailsWithoutFetchingOrWritingReplacement() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.replacement())
        f.local()
        f.aliases["\$original"] =
            f.aliases.getValue("\$original").copy(sender = "@other:example.test")
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$edit")))
        assertTrue(f.editWrites.isEmpty())
        assertFalse(f.base.calls.any { it == "event:\$original" })
    }

    @Test
    fun diagnosticResultsHideProviderIdentifiersAndMessageText() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        val result = success(f)
        for (value in listOf(result, result.items.single(), result.items.single().outcome)) {
            assertFalse(value.toString().contains("\$original"))
            assertFalse(value.toString().contains("Fictional"))
        }
    }

    @Test
    fun latestWithoutARetainedPageCannotAcquireContent() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        val wrong =
            f.request.copy(
                captured = f.request.captured.copy(phase = CollaborationSpaceIncomingPhase.Latest)
            )
        assertIs<AppResult.Failed>(f.acquire(f.runtime, wrong, listOf("\$original")))
        assertTrue(f.base.calls.isEmpty())
        assertEquals(0, f.local.sourceReads)
    }

    @Test
    fun retainedOriginalMetadataDoesNotGrantExpiredNativeContentAccess() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.local.native.setup.now += 365.days
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
        assertFalse(f.base.calls.any { it.startsWith("event:") })
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun missingInstalledSourceOrRegistrationDoesNotProbeEvents() = runTest {
        for (source in listOf(true, false)) {
            val f = SpaceOriginalIncomingContentFixture()
            if (source) f.local.installed = null else f.local.registration = null
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
            assertFalse(f.base.calls.any { it.startsWith("event:") })
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun groupReceiptCannotStandInForTheRegisteredOriginalPhysicalDevice() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        val wrong =
            f.request.copy(traversal = f.request.traversal.copy(deviceId = f.runtime.deviceId))
        assertIs<AppResult.Failed>(f.acquire(f.runtime, wrong, listOf("\$original")))
        assertFalse(f.base.calls.any { it.startsWith("event:") })
    }

    @Test
    fun lateSourceRegistrationTaskMetadataAndRuntimeChangesRejectRawSuccess() = runTest {
        for (change in listOf("source", "registration", "task", "metadata", "runtime")) {
            val f = SpaceOriginalIncomingContentFixture()
            f.put(f.source())
            f.base.hooks["event:\$original"] = {
                when (change) {
                    "source" -> f.local.installed = f.local.source.copy(storeId = "b".repeat(64))
                    "registration" ->
                        f.local.registration =
                            assertNotNull(f.local.registration).copy(revision = 3)
                    "task" ->
                        f.local.demand(
                            assertNotNull(f.local.snapshot)
                                .demand
                                .copy(
                                    rooms =
                                        listOf(f.local.captured.copy(generation = f.local.id(111)))
                                )
                        )
                    "metadata" -> f.local.retained = null
                    "runtime" -> f.base.open = false
                }
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
            assertTrue(f.editWrites.isEmpty())
        }
    }

    @Test
    fun independentSourcePositionAdvancePreservesTheSameCapturedTask() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.base.hooks["event:\$original"] = {
            val demand = assertNotNull(f.local.snapshot).demand
            f.local.demand(
                demand.copy(sources = listOf(demand.sources.single().copy(position = "position-2")))
            )
        }
        assertEquals(f.request, success(f).room)
        assertEquals(listOf(f.original()), f.originalWrites.single())
    }

    @Test
    fun uncertainStorageReplyCannotBecomeACompletedBatch() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        val failure = f.local.directory.storageFailure
        f.originalResponse = AppResult.Failed(failure)
        assertEquals(
            failure,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
                .failure,
        )
        assertEquals(1, f.originalWrites.size)
        assertEquals(1, f.base.calls.count { it.startsWith("event:") })
        assertTrue(f.base.saved.isEmpty())
        assertTrue(f.base.requests.isEmpty())
    }

    @Test
    fun backwardWallClockCannotAdmitLateDecryptedContent() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.put(f.source())
        f.base.hooks["event:\$original"] = { f.native.setup.now -= 1.seconds }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun stalledEventHasOneDeadlineAndCannotStartAnotherRead() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.base.hooks["event:\$original"] = { awaitCancellation() }
        val result =
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$original")))
        assertEquals(FailureKind.Timeout, result.failure.kind)
        assertEquals(1, f.base.calls.count { it.startsWith("event:") })
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun sourceReplacementAfterFinalEditCommitCannotPublishCompletion() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        f.local()
        f.put(f.replacement())
        f.base.hooks["mergeEdits"] = { f.local.installed = null }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.request, listOf("\$edit")))
        assertEquals(1, f.editWrites.size)
        assertTrue(f.originalWrites.isEmpty())
        assertTrue(f.base.saved.isEmpty())
    }

    @Test
    fun fullBatchWithSharedEditTargetReadsAndRetainsThatOriginalOnlyOnce() = runTest {
        val f = SpaceOriginalIncomingContentFixture()
        val edits = List(15) { f.replacement("\$edit$it") }
        edits.forEach(f::put)
        f.put(f.source())
        val ids = edits.map { it.eventId } + "\$original"
        val result = success(f, ids)
        assertEquals(ids, result.items.map { it.sourceEventId })
        assertEquals(listOf(f.original()), f.originalWrites.single())
        assertEquals(15, f.editWrites.single().size)
        assertEquals(16, f.base.calls.count { it.startsWith("event:") })
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
    }

    private suspend fun success(
        f: SpaceOriginalIncomingContentFixture,
        ids: List<String> = listOf("\$original"),
    ) =
        assertIs<AppResult.Success<CollaborationSpaceOriginalIncomingBatch>>(
                f.acquire(f.runtime, f.request, ids)
            )
            .value
}
