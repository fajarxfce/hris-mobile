package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationOriginalIncomingRoomTest {
    @Test
    fun cleanOriginalHeadUsesCompanyAndOriginalDeviceWithoutWritingOrScheduling() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = true)
        f.original.covered()
        assertEquals(AppResult.Success(false), f.queue())
        assertNull(f.snapshot)
        assertNull(f.wake)
        assertEquals(0, f.writes)
        assertEquals(0, f.schedules)
        assertFalse("source" in f.base.calls)
        assertFalse("pending" in f.base.calls)
        assertEquals(1, f.base.calls.count { it == "original-source" })
        assertEquals(1, f.base.calls.count { it == "original-pending" })
        assertEquals(1, f.base.calls.count { it == "original-progress" })
        assertTrue(f.original.observerReleased)
    }

    @Test
    fun unknownUnresolvedAndUnfinishedOriginalsRetainNormalLatestAliasWork() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingRequestFixture(originalRoom = true)
            f.original.covered(exhausted = mode != 0, unresolved = mode == 1)
            if (mode == 2) f.original.source = f.original.source.copy(reference = null)
            if (mode == 3) {
                val old = f.original.source.progress!!
                val active =
                    old.copy(
                        incoming =
                            old.incoming!!.copy(
                                pendingAfter = "seek",
                                pendingThrough = 20,
                                pendingRetryRequired = true,
                            )
                    )
                assertTrue(
                    dev.fajar.starter.collaboration.domain.policies
                        .validCollaborationHistoryProgress(active)
                )
                f.original.source = f.original.source.copy(progress = active)
                f.original.pending = f.original.pending.copy(progress = active)
                f.original.confirmed = active
            }
            assertEquals(AppResult.Success(true), f.queue())
            assertEquals(
                CollaborationSpaceIncomingPhase.Latest,
                f.snapshot!!.demand.rooms.single().phase,
            )
            assertEquals(1, f.writes)
            assertEquals(1, f.schedules)
            assertFalse("source" in f.base.calls)
        }
    }

    @Test
    fun missingPendingOrDifferentDeviceRegistrationCannotSuppressAHint() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingRequestFixture(originalRoom = true)
            f.original.covered()
            val record = f.original.registration!!
            f.original.registration =
                when (mode) {
                    0 -> null
                    1 ->
                        record.copy(
                            revision = 0,
                            attempts = 0,
                            outcome = CollaborationSpaceRegistrationOutcome.Pending,
                        )
                    2 ->
                        record.copy(
                            outcome =
                                CollaborationSpaceRegistrationOutcome.Registered(
                                    CollaborationSetupReceipt(f.provider.queue.id(1200), 1)
                                )
                        )
                    else ->
                        record.copy(
                            command =
                                record.command.copy(
                                    legacy =
                                        record.command.legacy!!.let {
                                            it.copy(
                                                binding =
                                                    it.binding.copy(
                                                        nativeSessionId = f.provider.queue.id(1201)
                                                    )
                                            )
                                        }
                                )
                        )
                }
            assertEquals(AppResult.Success(true), f.queue())
            assertFalse("original-source" in f.base.calls)
            assertEquals(
                CollaborationSpaceIncomingPhase.Latest,
                f.snapshot!!.demand.rooms.single().phase,
            )
        }
    }

    @Test
    fun registrationReplacementDuringEachHistoryReadCannotDischargeTheHint() = runTest {
        for (stage in listOf("original-source", "original-pending", "original-progress")) {
            val f = SpaceIncomingRequestFixture(originalRoom = true)
            f.original.covered()
            f.base.hooks[stage] = {
                f.original.registration =
                    f.original.registration!!.let { it.copy(revision = it.revision + 1) }
            }
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(0, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun sessionReplacementAndDenialAtEveryOriginalReadRemainAuthoritative() = runTest {
        for (stage in
            listOf(
                "original-registration",
                "original-source",
                "original-pending",
                "original-progress",
            )) {
            for (deny in listOf(false, true)) {
                val f = SpaceIncomingRequestFixture(originalRoom = true)
                f.original.covered()
                f.base.hooks[stage] = {
                    if (deny) f.base.directory.deny() else f.base.native.setup.session = null
                }
                val failure = assertIs<AppResult.Failed>(f.queue()).failure
                if (deny) assertEquals(f.base.directory.denied, failure)
                else assertEquals(FailureKind.Unauthorized, failure.kind)
                assertEquals(0, f.writes)
                assertEquals(0, f.schedules)
            }
        }
    }

    @Test
    fun originalStorageFailureNeverSchedulesAnUnconfirmedHint() = runTest {
        for (stage in
            listOf(
                "original-registration",
                "original-source",
                "original-pending",
                "original-progress",
            )) {
            val f = SpaceIncomingRequestFixture(originalRoom = true)
            f.original.covered()
            f.base.failures[stage] = Failure(FailureKind.Storage, "Fixture storage failure")
            assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(f.queue()).failure.kind)
            assertEquals(0, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun cancellationAtEachOriginalReadPropagatesWithoutWrites() = runTest {
        for (stage in
            listOf(
                "original-registration",
                "original-source",
                "original-pending",
                "original-progress",
            )) {
            val f = SpaceIncomingRequestFixture(originalRoom = true)
            f.original.covered()
            f.base.hooks[stage] = { throw CancellationException("Fixture cancelled") }
            assertFailsWith<CancellationException> { f.queue() }
            assertEquals(0, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun malformedOriginalSourceAndPublishedProviderCoordinatesAreRejected() = runTest {
        for (mode in 0..4) {
            val f = SpaceIncomingRequestFixture(originalRoom = true)
            f.original.covered()
            val source = f.original.source
            f.original.source =
                when (mode) {
                    0 -> source.copy(progress = null)
                    1 ->
                        source.copy(
                            progress = source.progress!!.copy(provider = f.provider.identity)
                        )
                    2 ->
                        source.copy(
                            reference =
                                source.reference!!.copy(sequence = source.progress!!.references)
                        )
                    3 ->
                        source.copy(
                            reference =
                                source.reference!!.copy(
                                    event = source.reference!!.event.copy(eventId = "\$other")
                                )
                        )
                    else -> source.copy(progress = source.progress!!.copy(incoming = null))
                }
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(0, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun inconsistentPendingOrChangedFinalProgressCannotConfirmCoverage() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingRequestFixture(originalRoom = true)
            f.original.covered()
            when (mode) {
                0 ->
                    f.original.pending =
                        f.original.pending.copy(
                            progress = f.original.pending.progress!!.copy(revision = 1)
                        )
                1 ->
                    f.original.source =
                        f.original.source.copy(
                            reference = f.original.source.reference!!.copy(resolved = false)
                        )
                2 -> f.original.confirmed = f.original.confirmed!!.copy(revision = 1)
                else ->
                    f.original.pending =
                        f.original.pending.copy(
                            events =
                                List(2) { f.original.source.reference!!.copy(resolved = false) }
                        )
            }
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(0, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun coveredOldHeadPreservesNewerAliasAndIndependentProducerWork() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = true)
        f.original.covered()
        f.queued("\$newer")
        f.retainIndependentProducerWork()
        val before = f.snapshot
        assertEquals(
            AppResult.Success(true),
            f.queue(f.request.copy(generation = f.provider.queue.id(1203))),
        )
        assertEquals(before, f.snapshot)
        assertEquals(0, f.writes)
        assertEquals(0, f.schedules)
    }

    @Test
    fun anotherNativeFamilyIsRejectedBeforeBindingOrHistoryReads() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = true)
        f.original.covered()
        val owner =
            f.owner.copy(parent = f.owner.parent.copy(nativeSessionId = f.provider.queue.id(1204)))
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.useCase(owner, f.key, f.request)).failure.kind,
        )
        assertTrue(f.base.calls.isEmpty())
        assertEquals(0, f.writes)
        assertEquals(0, f.schedules)
    }

    @Test
    fun ordinaryCredentialAgeDoesNotEraseOwnedOriginalCoverage() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = true)
        f.original.covered()
        f.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(false), f.queue())
        assertEquals(0, f.writes)
        assertEquals(0, f.schedules)
    }

    @Test
    fun anUnknownHeadKeepsItsIndependentRequestWithoutReadingHistory() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = true)
        f.original.covered()
        assertEquals(AppResult.Success(true), f.queue(f.request.copy(headEventId = null)))
        assertFalse(f.base.calls.any { it.startsWith("original-") })
        assertEquals(
            CollaborationSpaceIncomingPhase.Latest,
            f.snapshot!!.demand.rooms.single().phase,
        )
    }
}
