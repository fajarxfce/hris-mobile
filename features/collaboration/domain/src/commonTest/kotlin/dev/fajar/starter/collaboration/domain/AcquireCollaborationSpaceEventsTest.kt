package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationSpaceEventsTest {
    @Test
    fun rejectsEmptyOversizedRepeatedAndMalformedInputsBeforeIo() = runTest {
        for (ids in
            listOf(
                emptyList(),
                List(17) { "\$event$it" },
                listOf("\$one", "\$one"),
                listOf("bad"),
            )) {
            val f = SpaceIncomingFixture()
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, ids))
            assertTrue(f.base.calls.isEmpty())
        }
    }

    @Test
    fun oneBatchUsesOneTransportAndNeverAcquiresOrResumesASendQueue() = runTest {
        val f = SpaceIncomingFixture()
        val values = List(16) { f.original("\$event$it").copy(logicalId = f.queue.id(300 + it)) }
        values.forEach { f.put(f.source(it)) }
        val result = success(f, values.map { it.eventId })
        assertEquals(values, f.originalWrites.single())
        assertEquals(16, result.items.size)
        assertEquals(1, f.base.calls.count { it == "transport" })
        assertEquals(16, f.base.calls.count { it.startsWith("event:") })
        assertFalse(
            f.base.calls.any { it in setOf("acquire", "snapshot", "resume", "settle", "append") }
        )
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun recipientReadinessDoesNotBlockReceivingAndOriginalNativeAliasesStayDistinct() = runTest {
        val f = SpaceIncomingFixture()
        f.base.observed = f.base.observed.copy(recipientReady = false)
        assertNotEquals(f.physical.nativeSessionId, f.runtime.owner.parent.nativeSessionId)
        f.put(f.source())
        success(f)
        assertEquals(listOf(f.original()), f.originalWrites.single())
    }

    @Test
    fun coherentLocalOriginalAvoidsTargetTransportAndPreservesEditIdentity() = runTest {
        val f = SpaceIncomingFixture()
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
        val f = SpaceIncomingFixture()
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
        val f = SpaceIncomingFixture()
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
        val f = SpaceIncomingFixture()
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
        assertEquals(2, f.base.calls.count { it == "transport" })
        assertEquals(2, f.base.calls.count { it == "event:\$original" })
    }

    @Test
    fun redactionTriggerWaitsForConfirmationWithoutInventingATombstone() = runTest {
        val f = SpaceIncomingFixture()
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
        val f = SpaceIncomingFixture()
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
        val seed = SpaceIncomingFixture()
        seed.put(seed.source())
        val snap = seed.snapshots.getValue("\$original")
        val p = snap.physical
        val variants =
            listOf(
                snap.copy(runtime = snap.runtime.copy(operationId = seed.queue.id(999))),
                snap.copy(physical = p.copy(owner = p.owner.copy(sessionId = "different"))),
                snap.copy(
                    physical = p.copy(owner = p.owner.copy(audience = "https://different.example"))
                ),
                snap.copy(physical = p.copy(owner = p.owner.copy(accountId = seed.queue.id(999)))),
                snap.copy(physical = p.copy(owner = p.owner.copy(companyId = seed.queue.id(999)))),
                snap.copy(physical = p.copy(owner = p.owner.copy(nativeSessionId = "invalid"))),
                snap.copy(physical = p.copy(deviceId = seed.queue.id(999))),
                snap.copy(physical = p.copy(roomId = "!other:example.test")),
                snap.copy(physical = p.copy(roomJoined = false)),
                snap.copy(physical = p.copy(roomEncrypted = false)),
                snap.copy(physical = p.copy(event = p.event.copy(eventId = "\$different"))),
            )
        for (value in variants) {
            val f = SpaceIncomingFixture()
            f.snapshots["\$original"] = value
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun targetCannotSwitchPhysicalNativeNamespaceWithinAnAdmittedBatch() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        f.put(f.replacement())
        val old = f.snapshots.getValue("\$original")
        f.snapshots["\$original"] =
            old.copy(
                physical =
                    old.physical.copy(
                        owner = old.physical.owner.copy(nativeSessionId = f.queue.id(999))
                    )
            )
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$edit")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun lateAccountSwitchDenialAndRoomReplacementBeatSuccessfulRawReads() = runTest {
        for (change in 0..2) {
            val f = SpaceIncomingFixture()
            f.put(f.source())
            f.base.hooks["event:\$original"] = {
                when (change) {
                    0 -> f.native.setup.session = null
                    1 -> f.base.directory.deny()
                    2 -> f.base.retained = null
                }
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
            assertTrue(f.originalWrites.isEmpty())
        }
    }

    @Test
    fun runtimeReplacementDuringBindingRecheckCannotReachStorage() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        f.base.hooks["event:\$original"] = { f.base.hooks["binding"] = { f.base.open = false } }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun sdkDenialWinsEvenWhenRestrictionPersistenceAndRuntimeCleanupFail() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        val denied = f.base.directory.denied.copy(code = "collaboration_peer_unavailable")
        f.base.failures["event:\$original"] = denied
        f.base.directory.afterRestrict = {
            throw IllegalStateException("Fictional persistence loss")
        }
        f.base.hooks["closeSpace"] = { throw IllegalStateException("Fictional cleanup loss") }
        assertEquals(
            denied,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
                .failure,
        )
        assertEquals(1, f.base.directory.restrictions)
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun conversationPeerUnavailableDoesNotRevokeTheWholeDirectory() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        f.base.failures["transport"] =
            f.base.directory.denied.copy(code = "collaboration_peer_unavailable")
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
        assertEquals(0, f.base.directory.restrictions)
        assertFalse(f.base.calls.any { it.startsWith("event:") })
    }

    @Test
    fun providerOutageKeepsClassificationWithoutRetryingOrDenyingLocalReads() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        f.base.failures["event:\$original"] = f.base.directory.unavailable
        assertEquals(
            f.base.directory.unavailable,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
                .failure,
        )
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertEquals(0, f.base.directory.restrictions)
    }

    @Test
    fun secondCommitFailureRetainsOriginalWorkAndReplayUsesTheSameEventIdentities() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        f.put(f.replacement())
        f.editResponse = AppResult.Failed(f.base.directory.storageFailure)
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$edit")))
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
            val f = SpaceIncomingFixture()
            f.put(f.source())
            f.put(f.replacement())
            if (first) f.originalResponse = AppResult.Success(false)
            else f.editResponse = AppResult.Success(false)
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$edit")))
            assertEquals(if (first) 0 else 1, f.editWrites.size)
        }
    }

    @Test
    fun deniedAfterOriginalCommitDoesNotWriteEditsOrReportCompletion() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        f.put(f.replacement())
        f.base.hooks["merge"] = { f.base.directory.deny() }
        assertEquals(
            f.base.directory.denied,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$edit")))
                .failure,
        )
        assertEquals(1, f.originalWrites.size)
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun expiredGatewayObservationCountsElapsedTransportTime() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        val device = f.base.observed.device
        f.base.observed =
            f.base.observed.copy(
                device =
                    device.copy(
                        gateway =
                            requireNotNull(device.gateway)
                                .copy(expiresAt = device.observedAt + 1.seconds)
                    )
            )
        f.base.hooks["transport"] = { f.native.time += 2.seconds }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
        assertFalse(f.base.calls.any { it.startsWith("event:") })
    }

    @Test
    fun elapsedInvocationDeadlinePreventsLateStorage() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        f.base.hooks["event:\$original"] = { f.native.time += 15.seconds }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$original")))
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun cancellationPropagatesWithoutCommittingOrStartingAnotherRequest() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        val entered = CompletableDeferred<Unit>()
        f.base.hooks["event:\$original"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.acquire(f.runtime, f.conversation, listOf("\$original")) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.originalWrites.isEmpty())
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
    }

    @Test
    fun callerMutationAfterSuspensionCannotChangeTheCapturedInput() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        val ids = mutableListOf("\$original")
        f.base.hooks["transport"] = { ids[0] = "\$different" }
        assertEquals("\$original", success(f, ids).items.single().sourceEventId)
    }

    @Test
    fun incoherentLocalAliasFailsWithoutFetchingOrWritingReplacement() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.replacement())
        f.local()
        f.aliases["\$original"] =
            f.aliases.getValue("\$original").copy(sender = "@other:example.test")
        assertIs<AppResult.Failed>(f.acquire(f.runtime, f.conversation, listOf("\$edit")))
        assertTrue(f.editWrites.isEmpty())
        assertFalse(f.base.calls.any { it == "event:\$original" })
    }

    @Test
    fun diagnosticResultsHideProviderIdentifiersAndMessageText() = runTest {
        val f = SpaceIncomingFixture()
        f.put(f.source())
        val result = success(f)
        for (value in listOf(result, result.items.single(), result.items.single().outcome)) {
            assertFalse(value.toString().contains("\$original"))
            assertFalse(value.toString().contains("Fictional"))
        }
    }

    private suspend fun success(f: SpaceIncomingFixture, ids: List<String> = listOf("\$original")) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingBatch>>(
                f.acquire(f.runtime, f.conversation, ids)
            )
            .value
}
