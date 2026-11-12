package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.planCollaborationIncomingHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationSpaceKeyPageTest {
    private fun fixture() = SpaceKeyIncomingContentFixture().also { it.local.snapshot = null }

    private fun history(f: SpaceKeyIncomingContentFixture) = f.reader.keyHistory

    private fun page(
        f: SpaceKeyIncomingContentFixture,
        events: List<CollaborationAcquiredEvent> = listOf(f.source()),
    ) =
        CollaborationHistoryReferences(
            CollaborationHistoryProgress(
                f.local.id(960),
                0,
                null,
                maxOf(1, events.size).toLong(),
                provider =
                    CollaborationHistoryProviderIdentity(
                        f.local.position.companyId,
                        f.local.id(951),
                    ),
                incoming = CollaborationIncomingHistoryPosition("head", null),
                recovery =
                    CollaborationHistoryRecoveryPosition(
                        f.local.id(961),
                        maxOf(1, events.size).toLong(),
                    ),
            ),
            events.mapIndexed { index, event ->
                CollaborationHistoryReference(
                    index.toLong(),
                    CollaborationProviderHistoryEvent(
                        event.eventId,
                        event.sender,
                        event.timestamp,
                        event.type,
                    ),
                )
            },
            null,
        )

    private suspend fun success(
        f: SpaceKeyIncomingContentFixture,
        references: CollaborationHistoryReferences = page(f),
    ) =
        assertIs<AppResult.Success<CollaborationSpaceKeyPageBatch>>(
                f.acquire(f.runtime, history(f), references)
            )
            .value

    private fun assertNoContentIo(f: SpaceKeyIncomingContentFixture) {
        assertFalse(f.base.calls.any { it.startsWith("event:") })
        assertTrue(f.originalWrites.isEmpty())
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun sixteenRetainedOriginalsNeedNoNativeReadOrRewrite() = runTest {
        val f = fixture()
        val values = List(16) { f.original("\$event$it").copy(logicalId = f.local.id(300 + it)) }
        values.forEach { f.local(it) }
        val result = success(f, page(f, values.map(f::source)))
        assertEquals(values.map { it.eventId }, result.items.map { it.sourceEventId })
        assertTrue(result.items.all { it.outcome is CollaborationIncomingHistoryOutcome.Retained })
        assertEquals(history(f), result.history)
        assertNoContentIo(f)
        assertNull(f.local.snapshot)
        assertFalse(
            f.base.calls.any {
                it in setOf("demand", "transport", "acquire", "resume", "append", "settle")
            }
        )
    }

    @Test
    fun mixedLocalAndAcquiredResultsKeepCapturedSourceOrder() = runTest {
        val f = fixture()
        val original = f.source()
        val edit = f.replacement()
        val fresh = f.source(f.original("\$fresh").copy(logicalId = f.local.id(970)))
        val waiting =
            f.source(f.original("\$waiting"))
                .copy(
                    type = "m.room.encrypted",
                    content =
                        CollaborationAcquiredEventContent.Unresolved(
                            CollaborationEventDecryptionFailure.UnableToDecrypt
                        ),
                )
        val unsupported =
            f.source(f.original("\$unsupported"))
                .copy(
                    type = "m.room.member",
                    content = CollaborationAcquiredEventContent.Unsupported,
                )
        f.local()
        f.replacementFacts[edit.eventId] =
            CollaborationReplacementEventFact(edit.eventId, edit.sender, edit.timestamp, false)
        listOf(fresh, waiting, unsupported).forEach(f::put)
        val sources = listOf(original, edit, fresh, waiting, unsupported)
        val result = success(f, page(f, sources))
        assertEquals(sources.map { it.eventId }, result.items.map { it.sourceEventId })
        assertEquals(
            listOf("event:\$fresh", "event:\$waiting", "event:\$unsupported"),
            f.base.calls.filter { it.startsWith("event:") },
        )
        assertEquals(listOf("\$fresh"), f.originalWrites.single().map { it.eventId })
        assertTrue(f.editWrites.isEmpty())
        assertIs<CollaborationIncomingHistoryOutcome.AwaitDecryption>(result.items[3].outcome)
        assertIs<CollaborationIncomingHistoryOutcome.Unsupported>(result.items[4].outcome)
        assertEquals(
            listOf("\$edit"),
            assertIs<CollaborationIncomingHistoryOutcome.Retained>(result.items[1].outcome)
                .replacementEventIds,
        )
    }

    @Test
    fun emptyFinitePageStillChecksAdmissionWithoutContentIo() = runTest {
        val f = fixture()
        assertTrue(success(f, page(f, emptyList())).items.isEmpty())
        assertTrue(f.reader.calls.any { it == "binding" })
        assertNoContentIo(f)
    }

    @Test
    fun absentCompletedOrMalformedRecoveryPagesFailBeforeIo() = runTest {
        val f = fixture()
        val good = page(f)
        val progress = assertNotNull(good.progress)
        val recovery = assertNotNull(progress.recovery)
        for (bad in
            listOf(
                good.copy(progress = null),
                good.copy(progress = progress.copy(recovery = null)),
                good.copy(progress = progress.copy(recovery = recovery.copy(complete = true))),
                good.copy(progress = progress.copy(generation = "bad")),
                good.copy(progress = progress.copy(provider = null)),
                good.copy(events = listOf(good.events.single().copy(resolved = true))),
                good.copy(events = List(17) { good.events.single() }),
                good.copy(events = listOf(good.events.single(), good.events.single())),
                good.copy(events = listOf(good.events.single().copy(sequence = 2))),
                good.copy(after = "invalid"),
            )) {
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), bad))
            assertTrue(f.base.calls.isEmpty())
        }
    }

    @Test
    fun referencesBeyondCapturedRecoveryBoundaryAreRejectedBeforeIo() = runTest {
        val f = fixture()
        val good = page(f, listOf(f.source(), f.source(f.original("\$later"))))
        val progress = assertNotNull(good.progress)
        val bad =
            good.copy(
                progress =
                    progress.copy(recovery = assertNotNull(progress.recovery).copy(through = 1))
            )
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), bad))
        assertTrue(f.base.calls.isEmpty())
    }

    @Test
    fun aConfirmedOriginalTombstoneIsAlreadyRetained() = runTest {
        val f = fixture()
        val source = f.source()
        f.aliases[source.eventId] =
            CollaborationHistoryEventFact(source.eventId, source.sender, null, true)
        val result = success(f)
        assertEquals(
            listOf(source.eventId),
            assertIs<CollaborationIncomingHistoryOutcome.Retained>(result.items.single().outcome)
                .originalEventIds,
        )
        assertNoContentIo(f)
    }

    @Test
    fun aRedactedReplacementKeepsItsExactSourceIdentity() = runTest {
        val f = fixture()
        val source = f.replacement()
        f.replacementFacts[source.eventId] =
            CollaborationReplacementEventFact(source.eventId, source.sender, source.timestamp, true)
        val result = success(f, page(f, listOf(source)))
        assertEquals(
            listOf(source.eventId),
            assertIs<CollaborationIncomingHistoryOutcome.Retained>(result.items.single().outcome)
                .replacementEventIds,
        )
        assertNoContentIo(f)
    }

    @Test
    fun mismatchedOriginalFactsDoNotFallBackToSdkOrReplacement() = runTest {
        for (mode in 0..3) {
            val f = fixture()
            val e = f.source()
            val fact = CollaborationHistoryEventFact(e.eventId, e.sender, e.timestamp, false)
            f.aliases[e.eventId] =
                when (mode) {
                    0 -> fact.copy(eventId = "\$other")
                    1 -> fact.copy(sender = "@other:example.test")
                    2 -> fact.copy(timestamp = e.timestamp + 1)
                    else -> fact.copy(timestamp = null)
                }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
            assertFalse(f.base.calls.any { it.startsWith("replacement:") })
            assertNoContentIo(f)
        }
    }

    @Test
    fun mismatchedReplacementFactsDoNotFallBackToSdk() = runTest {
        for (mode in 0..2) {
            val f = fixture()
            val e = f.replacement()
            val fact = CollaborationReplacementEventFact(e.eventId, e.sender, e.timestamp, false)
            f.replacementFacts[e.eventId] =
                when (mode) {
                    0 -> fact.copy(eventId = "\$other")
                    1 -> fact.copy(sender = "@other:example.test")
                    else -> fact.copy(timestamp = e.timestamp + 1)
                }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f, listOf(e))))
            assertNoContentIo(f)
        }
    }

    @Test
    fun missingLocalFactsAcquireTheSourceOnce() = runTest {
        val f = fixture()
        f.put(f.source())
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(success(f).items.single().outcome)
        assertEquals(
            listOf("alias:\$original", "replacement:\$original", "event:\$original"),
            f.base.calls.filter {
                it.startsWith("alias:") || it.startsWith("replacement:") || it.startsWith("event:")
            },
        )
        assertEquals(1, f.originalWrites.size)
    }

    @Test
    fun anOriginalCommittedBeforeALostReplyIsNotAcquiredAgain() = runTest {
        val f = fixture()
        f.put(f.source())
        f.base.hooks["merge"] = { f.local() }
        f.originalResponse = AppResult.Failed(f.local.directory.storageFailure)
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
        f.base.calls.clear()
        val result = success(f)
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(result.items.single().outcome)
        assertFalse(f.base.calls.any { it.startsWith("event:") })
        assertEquals(1, f.originalWrites.size)
    }

    @Test
    fun anEditCommittedBeforeALostReplyIsNotAcquiredAgain() = runTest {
        val f = fixture()
        val edit = f.replacement()
        f.local()
        f.put(edit)
        f.base.hooks["mergeEdits"] = {
            f.replacementFacts[edit.eventId] =
                CollaborationReplacementEventFact(edit.eventId, edit.sender, edit.timestamp, false)
        }
        f.editResponse = AppResult.Failed(f.local.directory.storageFailure)
        val captured = page(f, listOf(edit))
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), captured))
        f.base.calls.clear()
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(
            success(f, captured).items.single().outcome
        )
        assertFalse(f.base.calls.any { it.startsWith("event:") })
        assertEquals(1, f.editWrites.size)
    }

    @Test
    fun rawSenderOrTimestampMustMatchTheCapturedReference() = runTest {
        for (sender in listOf(true, false)) {
            val f = fixture()
            val event = f.source()
            val captured = page(f)
            val raw =
                f.source(
                    if (sender) f.original().copy(sender = "@other:example.test")
                    else f.original().copy(timestamp = event.timestamp + 1)
                )
            assertIs<AppResult.Success<CollaborationIncomingHistoryPlan>>(
                planCollaborationIncomingHistory(raw)
            )
            f.put(raw)
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), captured))
            assertEquals(1, f.base.calls.count { it == "event:\$original" })
            assertTrue(f.originalWrites.isEmpty())
            assertTrue(f.editWrites.isEmpty())
        }
    }

    @Test
    fun localOriginalReadFailureDoesNotTriggerSdkFallback() = runTest {
        val f = fixture()
        f.base.failures["alias:\$original"] = f.local.directory.storageFailure
        assertEquals(
            f.local.directory.storageFailure,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f))).failure,
        )
        assertNoContentIo(f)
    }

    @Test
    fun localReplacementReadFailureDoesNotTriggerSdkFallback() = runTest {
        val f = fixture()
        f.base.failures["replacement:\$original"] = f.local.directory.storageFailure
        assertEquals(
            f.local.directory.storageFailure,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f))).failure,
        )
        assertNoContentIo(f)
    }

    @Test
    fun denialAfterEitherLocalFactReadPreventsACompletedBatch() = runTest {
        for (stage in listOf("alias:\$original", "replacement:\$original")) {
            val f = fixture()
            val e = f.source()
            if (stage.startsWith("alias")) f.local()
            else
                f.replacementFacts[e.eventId] =
                    CollaborationReplacementEventFact(e.eventId, e.sender, e.timestamp, false)
            f.base.hooks[stage] = { f.local.directory.deny() }
            assertEquals(
                f.local.directory.denied,
                assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f))).failure,
            )
            assertNoContentIo(f)
            assertTrue(f.base.calls.any { it == "close" })
        }
    }

    @Test
    fun cancellingEitherLocalFactReadDoesNotRetryOrAcquire() = runTest {
        for (stage in listOf("alias:\$original", "replacement:\$original")) {
            val f = fixture()
            val entered = CompletableDeferred<Unit>()
            f.base.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val task = async { f.acquire(f.runtime, history(f), page(f)) }
            entered.await()
            task.cancelAndJoin()
            assertFailsWith<CancellationException> { task.await() }
            assertEquals(1, f.base.calls.count { it == stage })
            assertNoContentIo(f)
        }
    }

    @Test
    fun capturedReferencesAreCopiedBeforeSuspendingAdmission() = runTest {
        val f = fixture()
        f.local()
        val captured = page(f)
        val rows = captured.events.toMutableList()
        f.reader.hooks["binding"] = {
            rows[0] = rows[0].copy(event = rows[0].event.copy(eventId = "\$other"))
        }
        val result = success(f, captured.copy(events = rows))
        assertEquals(listOf("\$original"), result.items.map { it.sourceEventId })
        assertNoContentIo(f)
    }

    @Test
    fun sourceReplacementAfterLocalReadInvalidatesTheResult() = runTest {
        val f = fixture()
        f.local()
        f.base.hooks["alias:\$original"] = { f.local.installed = null }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
        assertNoContentIo(f)
    }

    @Test
    fun accountReplacementAfterLocalReadInvalidatesTheResult() = runTest {
        val f = fixture()
        f.local()
        f.base.hooks["alias:\$original"] = { f.native.setup.session = null }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
        assertNoContentIo(f)
    }

    @Test
    fun metadataAndRegistrationChangesAfterLocalReadsInvalidateTheResult() = runTest {
        for (metadata in listOf(true, false)) {
            val f = fixture()
            f.local()
            f.base.hooks["alias:\$original"] = {
                if (metadata) f.reader.binding = null else f.local.registration = null
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
            assertNoContentIo(f)
        }
    }

    @Test
    fun monotonicDeadlineAndBackwardClockApplyToLocalReconciliation() = runTest {
        for (backward in listOf(true, false)) {
            val f = fixture()
            f.local()
            f.base.hooks["alias:\$original"] = {
                if (backward) f.native.setup.now -= 1.seconds else f.native.time += 15.seconds
            }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
            assertNoContentIo(f)
        }
    }

    @Test
    fun aLaterKeyAttemptRetriesUnknownSourcesWithoutATimerLoop() = runTest {
        val f = fixture()
        val source = f.source()
        val captured = page(f)
        f.put(
            source.copy(
                type = "m.room.encrypted",
                content =
                    CollaborationAcquiredEventContent.Unresolved(
                        CollaborationEventDecryptionFailure.UnableToDecrypt
                    ),
            )
        )
        assertIs<CollaborationIncomingHistoryOutcome.AwaitDecryption>(
            success(f, captured).items.single().outcome
        )
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertTrue(f.originalWrites.isEmpty())
        f.put(source)
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(
            success(f, captured).items.single().outcome
        )
        assertEquals(2, f.base.calls.count { it == "event:\$original" })
        assertEquals(1, f.originalWrites.size)
        assertNull(f.local.snapshot)
    }

    @Test
    fun primaryDemandFailureDoesNotHideRetainedHistoricalContent() = runTest {
        val f = fixture()
        f.local()
        f.base.failures["demand"] = f.local.directory.storageFailure
        assertIs<CollaborationIncomingHistoryOutcome.Retained>(success(f).items.single().outcome)
        assertFalse(f.base.calls.any { it == "demand" })
        assertNoContentIo(f)
    }

    @Test
    fun emptyPageCannotSucceedUnderAnUnavailableSource() = runTest {
        val f = fixture()
        val source = history(f)
        f.local.installed = null
        assertIs<AppResult.Failed>(f.acquire(f.runtime, source, page(f, emptyList())))
        assertNoContentIo(f)
    }

    @Test
    fun laterNativeFailureDoesNotTurnEarlierLocalFactsIntoACompletedBatch() = runTest {
        val f = fixture()
        f.local()
        val sources = listOf(f.source(), f.source(f.original("\$later")))
        f.base.failures["event:\$later"] = f.local.directory.unavailable
        assertEquals(
            f.local.directory.unavailable,
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f, sources))).failure,
        )
        assertNotNull(f.aliases["\$original"])
        assertEquals(listOf("event:\$later"), f.base.calls.filter { it.startsWith("event:") })
        assertTrue(f.originalWrites.isEmpty())
    }

    @Test
    fun aSharedTargetReadMustMatchAnyCapturedReferenceForThatTarget() = runTest {
        val f = fixture()
        val edit = f.replacement()
        val original = f.source()
        val captured = page(f, listOf(edit, original))
        f.put(edit)
        val raw = f.source(f.original().copy(timestamp = original.timestamp + 1))
        assertIs<AppResult.Success<CollaborationIncomingHistoryPlan>>(
            planCollaborationIncomingHistory(raw)
        )
        f.put(raw)
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), captured))
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertTrue(f.originalWrites.isEmpty())
        assertTrue(f.editWrites.isEmpty())
    }

    @Test
    fun stalledLocalFactsRespectTheBoundedInvocationLifetime() = runTest {
        val f = fixture()
        f.base.hooks["alias:\$original"] = { awaitCancellation() }
        val result = assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
        assertEquals(FailureKind.Timeout, result.failure.kind)
        assertEquals(1, f.base.calls.count { it == "alias:\$original" })
        assertNoContentIo(f)
    }

    @Test
    fun spaceAndOriginalAliasKeepLogicalAndHistoricalCoordinates() = runTest {
        for (alias in listOf(false, true)) {
            val f = fixture()
            if (alias) f.reader.originalAlias()
            f.local()
            val captured = page(f)
            val result = success(f, captured)
            assertEquals(history(f), result.history)
            assertEquals(captured, result.references)
            assertNotEquals(
                f.local.position.conversationId,
                result.history.traversal.conversationId,
            )
            assertNotEquals(f.base.traversal.deviceId, result.history.traversal.deviceId)
            assertNotEquals(f.base.traversal.deviceId, captured.progress?.provider?.deviceId)
            assertNull(f.local.retained)
            assertFalse(f.base.calls.any { it == "metadata" || it == "transport" })
            assertNoContentIo(f)
        }
    }

    @Test
    fun acquiredEventsUseCurrentRegisteredDeviceWithoutReplacingRetainedProvider() = runTest {
        val f = fixture()
        f.reader.originalAlias()
        f.put(f.source())
        val captured = page(f)
        val result = success(f, captured)
        assertEquals(captured, result.references)
        assertNotEquals(
            captured.progress?.provider?.deviceId,
            f.snapshots.getValue("\$original").physical.deviceId,
        )
        assertEquals(listOf("\$original"), f.originalWrites.single().map { it.eventId })
        assertNull(f.local.retained)
    }

    @Test
    fun forgedPhysicalOwnershipCannotBorrowLogicalSpaceAdmission() = runTest {
        for (change in 0..7) {
            val f = fixture()
            f.put(f.source())
            val snapshot = f.snapshots.getValue("\$original")
            val physical = snapshot.physical
            f.snapshots["\$original"] =
                when (change) {
                    0 ->
                        snapshot.copy(
                            physical =
                                physical.copy(
                                    owner = physical.owner.copy(companyId = f.local.id(997))
                                )
                        )
                    1 ->
                        snapshot.copy(
                            physical =
                                physical.copy(
                                    owner = physical.owner.copy(sessionId = "replacement")
                                )
                        )
                    2 ->
                        snapshot.copy(
                            physical =
                                physical.copy(
                                    owner = physical.owner.copy(nativeSessionId = f.local.id(998))
                                )
                        )
                    3 ->
                        snapshot.copy(
                            physical = physical.copy(deviceId = history(f).traversal.deviceId)
                        )
                    4 -> snapshot.copy(physical = physical.copy(roomJoined = false))
                    5 -> snapshot.copy(physical = physical.copy(roomEncrypted = false))
                    6 -> snapshot.copy(physical = physical.copy(roomId = "!other:matrix.test"))
                    else -> snapshot.copy(runtime = f.runtime.copy(operationId = f.local.id(999)))
                }
            assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
            assertTrue(f.originalWrites.isEmpty())
            assertTrue(f.editWrites.isEmpty())
        }
    }

    @Test
    fun missingRegistrationCannotAdmitEvenAnEntirelyRetainedPage() = runTest {
        val f = fixture()
        f.local()
        f.local.registration = null
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
        assertFalse(f.base.calls.any { it.startsWith("alias:") || it.startsWith("replacement:") })
        assertNoContentIo(f)
    }

    @Test
    fun aSharedCapturedOriginalIsReadOnceAndRetainedOnce() = runTest {
        val f = fixture()
        val original = f.source()
        val first = f.replacement("\$edit1")
        val second = f.replacement("\$edit2")
        val events = listOf(first, second, original)
        events.forEach(f::put)
        val result = success(f, page(f, events))
        assertEquals(events.map { it.eventId }, result.items.map { it.sourceEventId })
        assertEquals(1, f.base.calls.count { it == "event:\$original" })
        assertEquals(listOf("\$original"), f.originalWrites.single().map { it.eventId })
        assertEquals(listOf("\$edit1", "\$edit2"), f.editWrites.single().map { it.eventId })
    }

    @Test
    fun registrationReplacementAfterNativeReadPreventsRetention() = runTest {
        val f = fixture()
        f.put(f.source())
        f.base.hooks["event:\$original"] = { f.local.registration = null }
        assertIs<AppResult.Failed>(f.acquire(f.runtime, history(f), page(f)))
        assertTrue(f.originalWrites.isEmpty())
        assertTrue(f.editWrites.isEmpty())
    }
}
