package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationSpaceOriginalIncomingReferencesTest {
    @Test
    fun supersededProviderCaptureCannotStartReadingReferences() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.capture(f.room.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider))
        val old = f.room
        f.capture(f.room.captured.copy(generation = f.base.id(999)))
        assertEquals(AppResult.Success(null), f.read(f.base.runtime, old))
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.transition.writes)
        assertTrue(f.provider.saved.isEmpty())
    }

    @Test
    fun providerPageExposesBoundedOwnedReferencesBeforeEofWithoutMovingAnyPosition() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.capture(f.room.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider))
        val previous = requireNotNull(f.provider.progress).copy(next = "next-provider-page")
        f.provider.progress = previous
        val page =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
            )
        assertEquals(f.events, page.events)
        assertEquals(previous, page.progress)
        assertFalse(requireNotNull(page.progress).exhausted)
        assertEquals(listOf<Pair<String?, Int>>(null to 16), f.requests)
        assertEquals(0, f.transition.writes)
        assertTrue(f.provider.saved.isEmpty())
    }

    @Test
    fun readsOriginalCompanyAndPhysicalDeviceWithoutNetworkOrWrites() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        val result =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
            )
        assertEquals(f.events, result.events)
        assertEquals(f.provider.progress, result.progress)
        assertEquals(listOf<Pair<String?, Int>>(null to 16), f.requests)
        assertNotEquals(f.provider.traversal.deviceId, f.base.runtime.deviceId)
        assertEquals(0, f.transition.writes)
        assertTrue(f.provider.requests.isEmpty())
        assertTrue(f.provider.saved.isEmpty())
    }

    @Test
    fun latestWithoutARetainedPageCannotReadIncomingReferences() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.capture(f.room.captured.copy(phase = CollaborationSpaceIncomingPhase.Latest))
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun groupReceiptCannotReplaceOriginalPhysicalDevice() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.room = f.room.copy(traversal = f.room.traversal.copy(deviceId = f.base.runtime.deviceId))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(f.requests.isEmpty())
        assertFalse("progress" in f.provider.calls)
    }

    @Test
    fun missingProgressIsReturnedForRecoveryWithoutStartingHistory() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.provider.progress = null
        assertEquals(
            AppResult.Success(CollaborationHistoryReferences(null, emptyList(), null)),
            f.run(),
        )
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun retainedSeekAndThroughBoundOneFinitePage() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        val position = CollaborationIncomingHistoryPosition("start", null, "seek", 20)
        f.provider.progress = f.transition.checkpoint.copy(references = 50, incoming = position)
        f.events = (16 until 32).map(f::reference)
        f.next = "more"
        val page =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
            )
        assertEquals(listOf<Pair<String?, Int>>("seek" to 16), f.requests)
        assertEquals((16L until 20L).toList(), page.events.map { it.sequence })
        assertNull(page.after)
        assertEquals(position, assertNotNull(page.progress).incoming)
    }

    @Test
    fun appendsDuringReadCannotExtendAnAlreadyCapturedPass() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        val before =
            f.transition.checkpoint.copy(
                references = 50,
                incoming = CollaborationIncomingHistoryPosition("start", null, "seek", 20),
            )
        f.provider.progress = before
        f.events = (16 until 32).map(f::reference)
        f.next = "more"
        f.provider.hooks["pending"] = {
            f.provider.progress = before.copy(references = 100, revision = 1)
        }
        val page =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
            )
        assertEquals((16L until 20L).toList(), page.events.map { it.sequence })
        assertNull(page.after)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun fullPageKeepsItsSeekWithoutFetchingAnotherPage() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.provider.progress = f.transition.checkpoint.copy(references = 20)
        f.events = (0 until 16).map(f::reference)
        f.next = "more"
        val page =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
            )
        assertEquals(16, page.events.size)
        assertEquals("more", page.after)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun referencesEntirelyBeyondThePassReturnAnEmptyFinalPage() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.provider.progress =
            f.transition.checkpoint.copy(
                references = 30,
                incoming = CollaborationIncomingHistoryPosition("start", null, "seek", 5),
            )
        f.events = (5 until 21).map(f::reference)
        f.next = "more"
        val page =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
            )
        assertTrue(page.events.isEmpty())
        assertNull(page.after)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun emptyPendingResultRetainsOwnedProgressWithoutAcknowledgement() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.events = emptyList()
        assertEquals(
            AppResult.Success(
                CollaborationHistoryReferences(f.provider.progress, emptyList(), null)
            ),
            f.run(),
        )
        assertEquals(0, f.transition.writes)
        assertEquals(f.transition.checkpoint, f.provider.progress)
    }

    @Test
    fun malformedOversizedOrResolvedReferencesCannotEnterThePass() = runTest {
        for (variant in 0..4) {
            val f = SpaceOriginalPendingReadFixture()
            f.events =
                when (variant) {
                    0 -> (0 until 17).map(f::reference)
                    1 -> listOf(f.reference(0), f.reference(0))
                    2 -> listOf(f.reference(0).copy(resolved = true))
                    3 -> listOf(f.reference(0).copy(sequence = -1))
                    else -> listOf(f.reference(2))
                }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun aFlowWithoutAResultCannotMasqueradeAsEmptyHistory() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.emptyFlow = true
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun changedFrameWaveGenerationOrSeekInvalidatesCapturedReferences() = runTest {
        for (variant in 0..7) {
            val f = SpaceOriginalPendingReadFixture()
            val old = f.transition.checkpoint.copy(revision = 2, references = 4)
            f.provider.progress = old
            val incoming = assertNotNull(old.incoming)
            f.provider.hooks["pending"] = {
                f.provider.progress =
                    when (variant) {
                        0 -> old.copy(generation = f.base.id(140))
                        1 ->
                            old.copy(
                                provider =
                                    CollaborationHistoryProviderIdentity(
                                        f.base.position.companyId,
                                        f.provider.traversal.deviceId,
                                    )
                            )
                        2 -> old.copy(incoming = incoming.copy(head = "other-head"))
                        3 -> old.copy(incoming = incoming.copy(until = "other-boundary"))
                        4 ->
                            old.copy(
                                incoming = incoming.copy(pendingAfter = "later", pendingThrough = 2)
                            )
                        5 -> old.copy(incoming = incoming.copy(pendingRetryRequired = true))
                        6 -> old.copy(revision = 1)
                        else -> old.copy(references = 3)
                    }
            }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun accountNativeSourceAndDenialChangesFenceSuspendedReads() = runTest {
        for (stage in listOf("progress", "pending")) {
            for (change in listOf("account", "native", "source", "denial")) {
                val f = SpaceOriginalPendingReadFixture()
                f.provider.hooks[stage] = {
                    when (change) {
                        "account" -> f.base.native.setup.session = null
                        "native" -> {
                            val old = assertNotNull(f.base.native.setup.session)
                            f.base.native.setup.session =
                                old.copy(binding = old.binding.copy(serverId = f.base.id(141)))
                        }
                        "source" -> f.base.installed = null
                        "denial" -> f.base.directory.deny()
                    }
                }
                val failure = assertIs<AppResult.Failed>(f.run()).failure
                if (change == "denial") assertEquals(f.base.directory.denied, failure)
                assertEquals(0, f.transition.writes)
            }
        }
    }

    @Test
    fun removedOrSupersededTasksReturnNoObsoleteReferences() = runTest {
        for (stage in listOf("progress", "pending")) {
            for (variant in 0..1) {
                val f = SpaceOriginalPendingReadFixture()
                f.provider.hooks[stage] = {
                    if (variant == 0) f.base.snapshot = null
                    else
                        f.base.demand(
                            assertNotNull(f.base.snapshot)
                                .demand
                                .copy(
                                    rooms =
                                        listOf(f.room.captured.copy(generation = f.base.id(142)))
                                )
                        )
                }
                assertEquals(AppResult.Success(null), f.run())
                assertEquals(0, f.transition.writes)
            }
        }
    }

    @Test
    fun changedHeadUnderSameGenerationCannotBecomeThisRead() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.provider.hooks["pending"] = {
            f.base.demand(
                assertNotNull(f.base.snapshot)
                    .demand
                    .copy(rooms = listOf(f.room.captured.copy(headEventId = "\$later")))
            )
        }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
    }

    @Test
    fun missingMetadataOrRegistrationCannotTriggerReacquisition() = runTest {
        for (variant in 0..1) {
            val f = SpaceOriginalPendingReadFixture()
            if (variant == 0) f.base.retained = null else f.base.registration = null
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.requests.isEmpty())
            assertTrue(f.provider.requests.isEmpty())
        }
    }

    @Test
    fun ordinaryCredentialAgeAndSelectedCompanyCannotHideOwnedPendingRows() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        assertEquals(
            f.events,
            assertNotNull(
                    assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
                )
                .events,
        )
    }

    @Test
    fun changedRegistrationRevisionInvalidatesLateReferences() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.provider.hooks["pending"] = {
            val old = assertNotNull(f.base.registration)
            f.base.registration = old.copy(revision = old.revision + 1)
        }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
    }

    @Test
    fun storageFailureIsPreservedAndCannotLookLikeNoPendingMessages() = runTest {
        for (stage in listOf("progress", "pending")) {
            val f = SpaceOriginalPendingReadFixture()
            f.provider.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.run()).failure,
            )
        }
    }

    @Test
    fun eachLocalSuspensionHasAFiveSecondDeadline() = runTest {
        for (stage in listOf("metadata", "registration", "demand", "progress", "pending")) {
            val f = SpaceOriginalPendingReadFixture()
            f.provider.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun cancellationPropagatesWithoutDiscardingPendingReferences() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.provider.hooks["pending"] = { throw CancellationException("retired") }
        assertFailsWith<CancellationException> { f.run() }
        assertEquals(f.transition.checkpoint, f.provider.progress)
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun mutableProviderListIsDetachedBeforeLaterAdmissionReads() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        val mutable = f.events.toMutableList()
        f.events = mutable
        f.provider.hooks["pending"] = { f.provider.hooks["metadata"] = { mutable.clear() } }
        val page =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryReferences?>>(f.run()).value
            )
        assertEquals(2, page.events.size)
        assertTrue(mutable.isEmpty())
    }

    @Test
    fun unrelatedProducerPositionDoesNotInvalidateTheSameTask() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.provider.hooks["pending"] = { f.transition.independentProducer() }
        assertIs<AppResult.Success<*>>(f.run())
        assertEquals("later", assertNotNull(f.base.snapshot).demand.sources.single().position)
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun malformedQueueCannotExposePendingReferences() = runTest {
        val f = SpaceOriginalPendingReadFixture()
        f.base.snapshot = assertNotNull(f.base.snapshot).copy(generation = "bad")
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertFalse("progress" in f.provider.calls)
        assertTrue(f.requests.isEmpty())
    }
}
