@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SpaceRoomBindingPreparationTest {
    @Test
    fun newBindingSchedulesOnlyAfterExactReadbackAndRepeatedLocalReadsDoNoWork() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceRoomBindingPreparationFixture(legacy)
            f.onSchedule = {
                assertEquals(AppResult.Success(f.binding), f.retained)
                assertEquals(2, f.bindingReads)
                assertEquals(listOf(f.binding), f.bindingWrites)
                AppResult.Success(Unit)
            }
            assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
            assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduleRequests)
            repeat(3) { assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation)) }
            advanceTimeBy(60_000)
            assertEquals(1, f.scheduleRequests.size)
            assertEquals(1, f.bindingWrites.size)
            assertEquals(1, f.source.roomReads)
        }
    }

    @Test
    fun scheduleFailureOrTimeoutKeepsTheConfirmedBindingWithoutImmediateRetry() = runTest {
        for (timeout in listOf(false, true)) {
            val f = SpaceRoomBindingPreparationFixture()
            f.onSchedule = {
                if (timeout) awaitCancellation() else AppResult.Failed(f.base.unavailable)
            }
            val started = testScheduler.currentTime
            assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
            assertEquals(if (timeout) 1_500L else 0L, testScheduler.currentTime - started)
            assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduleRequests)
            assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
            advanceTimeBy(60_000)
            assertEquals(1, f.bindingWrites.size)
            assertEquals(1, f.scheduleRequests.size)
        }
    }

    @Test
    fun cancellationDuringSchedulingReleasesTheRequestAndPreservesOwnedBinding() = runTest {
        val f = SpaceRoomBindingPreparationFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.onSchedule = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val job = async { f.prepare()(f.conversation) }
        entered.await()
        job.cancelAndJoin()
        stopped.await()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(AppResult.Success(f.binding), f.retained)
        assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
        assertEquals(1, f.scheduleRequests.size)
        assertEquals(1, f.bindingWrites.size)
    }

    @Test
    fun lateOwnershipOrDirectoryDenialDuringSchedulingCannotReturnAdmittedSuccess() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceRoomBindingPreparationFixture()
            f.onSchedule = {
                if (denied) f.base.deny() else f.native.setup.session = null
                AppResult.Success(Unit)
            }
            val result = assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            if (denied) assertEquals(f.base.denied, result.failure)
            assertEquals(AppResult.Success(f.binding), f.retained)
            assertEquals(1, f.scheduleRequests.size)
            assertEquals(1, f.bindingWrites.size)
        }
    }

    @Test
    fun bothOwnedOriginsRemainReadableAfterNativeExpiryAndPriorDenialWithoutRemoteWork() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceRoomBindingPreparationFixture(legacy)
            f.retained = AppResult.Success(f.binding)
            f.native.setup.now += 1000.days
            f.base.deny()
            val rebound =
                f.conversation.copy(
                    owner =
                        f.owner.copy(
                            parent =
                                f.owner.parent.copy(
                                    nativeSessionId = "a3000000-0000-4000-8000-000000000001"
                                )
                        )
                )
            assertEquals(AppResult.Success(f.binding), f.prepare()(rebound))
            assertEquals(1, f.bindingReads)
            assertEquals(0, f.source.roomReads)
            assertTrue(f.scheduleRequests.isEmpty())
            assertEquals(0, f.admissionReads)
            assertEquals(0, f.canonicalReads)
            assertTrue(
                f.bindingWrites.isEmpty() &&
                    f.canonicalWrites.isEmpty() &&
                    f.originalReads.isEmpty()
            )
        }
    }

    @Test
    fun missingOrReplacedLocalOwnerNeverReadsRetainedPrivateFacts() = runTest {
        val f = SpaceRoomBindingPreparationFixture()
        f.retained = AppResult.Success(f.binding)
        f.native.setup.session = null
        assertIs<AppResult.Failed>(f.prepare()(f.conversation))
        assertEquals(0, f.bindingReads)
        assertEquals(0, f.source.roomReads)
    }

    @Test
    fun invalidInputOrConflictingRetainedFactsNeverFallBackToNetworkOrOverwrite() = runTest {
        val f = SpaceRoomBindingPreparationFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.prepare()(f.conversation.copy(id = "invalid")))
                .failure
                .kind,
        )
        assertEquals(0, f.bindingReads)
        for (bad in
            listOf(
                f.binding.copy(
                    conversation =
                        f.binding.conversation.copy(createdBy = f.conversation.firstAccountId)
                ),
                f.binding.copy(
                    conversation = f.binding.conversation.copy(spaceId = f.access.accountId)
                ),
                f.binding.copy(
                    room = f.binding.room.copy(origin = CollaborationSpaceRoomOrigin.LEGACY)
                ),
            )) {
            f.retained = AppResult.Success(bad)
            assertEquals(
                "client.collaboration_invalid_response",
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.code,
            )
        }
        f.retained = AppResult.Failed(f.base.storageFailure)
        assertEquals(f.retained, f.prepare()(f.conversation))
        assertEquals(0, f.source.roomReads)
        assertTrue(f.bindingWrites.isEmpty())
    }

    @Test
    fun spacePublicationStoresCanonicalFactsAndBindingOnceWithoutOriginalOrSetupRequests() =
        runTest {
            val f = SpaceRoomBindingPreparationFixture()
            f.native.setup.workspace = null
            assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
            assertEquals(listOf(f.conversation), f.canonicalWrites)
            assertEquals(listOf(f.binding), f.bindingWrites)
            assertTrue(f.originalReads.isEmpty())
            assertEquals(1, f.source.roomReads)
            assertEquals(0, f.source.deviceReads)
            assertEquals(0, f.base.begins)
            assertEquals(0, f.base.admits)
            advanceTimeBy(60_000)
            assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
            assertEquals(1, f.source.roomReads)
            assertEquals(1, f.bindingWrites.size)
        }

    @Test
    fun legacyReadsOnlyThePublishedOriginalPositionAndPreservesDifferentHistoricalCreators() =
        runTest {
            val f = SpaceRoomBindingPreparationFixture(legacy = true)
            f.native.setup.workspace = null
            assertNotEquals(f.access.spaceId, f.position.companyId)
            assertNotEquals(f.conversation.id, f.position.conversationId)
            assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
            assertEquals(listOf(f.owner to f.position), f.originalReads)
            assertEquals(1, f.source.roomReads)
            assertEquals(listOf(f.binding), f.bindingWrites)
            assertNotEquals(f.original.conversation.createdBy, f.original.room?.createdBy)
        }

    @Test
    fun missingPublicationReturnsNotReadyWithoutRequestingAProviderRoomOrSchedulingRetry() =
        runTest {
            val f = SpaceRoomBindingPreparationFixture()
            f.source.view = CollaborationSpaceRoomView(f.conversation, null, null)
            assertEquals(
                "client.collaboration_room_not_ready",
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.code,
            )
            advanceTimeBy(60_000)
            assertEquals(1, f.source.roomReads)
            assertTrue(
                f.originalReads.isEmpty() &&
                    f.bindingWrites.isEmpty() &&
                    f.canonicalWrites.isEmpty()
            )
        }

    @Test
    fun aDeniedMissingBindingCannotStartLiveAcquisition() = runTest {
        val f = SpaceRoomBindingPreparationFixture()
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
        assertEquals(0, f.source.roomReads)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun aDenialDuringRoomReadWinsOverNotReadyAndTransportFailure() = runTest {
        for (failure in listOf(false, true)) {
            val f = SpaceRoomBindingPreparationFixture()
            f.source.onRoom = {
                f.base.deny()
                if (failure) AppResult.Failed(f.base.unavailable)
                else AppResult.Success(CollaborationSpaceRoomView(f.conversation, null, null))
            }
            assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
            assertEquals(1, f.source.roomReads)
            assertTrue(f.bindingWrites.isEmpty() && f.canonicalWrites.isEmpty())
        }
    }

    @Test
    fun aConfirmedDirectoryDenialSurvivesCoincidentNativeExpiry() = runTest {
        for (duringRoom in listOf(false, true)) {
            val f = SpaceRoomBindingPreparationFixture()
            fun expireAndDeny() {
                f.base.deny()
                f.native.setup.now += 1000.days
            }
            if (duringRoom) {
                f.source.onRoom = {
                    expireAndDeny()
                    AppResult.Success(f.source.view)
                }
            } else expireAndDeny()
            assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
            assertEquals(if (duringRoom) 1 else 0, f.source.roomReads)
            assertTrue(
                f.originalReads.isEmpty() &&
                    f.bindingWrites.isEmpty() &&
                    f.canonicalWrites.isEmpty()
            )
            assertEquals(0, f.base.admits)
        }
        val unavailable = SpaceRoomBindingPreparationFixture()
        unavailable.source.onRoom = {
            unavailable.native.setup.now += 1000.days
            AppResult.Failed(unavailable.base.unavailable)
        }
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(unavailable.prepare()(unavailable.conversation)).failure.code,
        )
        assertTrue(unavailable.bindingWrites.isEmpty() && unavailable.canonicalWrites.isEmpty())
    }

    @Test
    fun confirmedRemoteDenialSurvivesFailedCleanupAndBlocksTheNextFreshAttempt() = runTest {
        val f = SpaceRoomBindingPreparationFixture(legacy = true)
        f.onOriginal = { AppResult.Failed(f.base.denied) }
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
        assertEquals(1, f.base.restrictions)
        assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
        assertEquals(1, f.source.roomReads)
        assertEquals(1, f.originalReads.size)
        assertEquals(0, f.base.admits)
        assertTrue(f.bindingWrites.isEmpty())
    }

    @Test
    fun malformedPublishedViewsCannotAcquireAnOriginalOrWriteMetadata() = runTest {
        for (variant in 0..3) {
            val f = SpaceRoomBindingPreparationFixture(legacy = true)
            f.source.view =
                when (variant) {
                    0 ->
                        f.source.view.copy(
                            conversation = f.conversation.copy(id = f.access.accountId)
                        )
                    1 -> f.source.view.copy(room = f.published.copy(original = null))
                    2 -> f.source.view.copy(room = f.published.copy(serverName = "bad\n"))
                    else -> f.source.view.copy(provisioning = null)
                }
            assertEquals(
                "client.collaboration_invalid_response",
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.code,
            )
            assertTrue(
                f.originalReads.isEmpty() &&
                    f.canonicalWrites.isEmpty() &&
                    f.bindingWrites.isEmpty()
            )
        }
    }

    @Test
    fun originalOwnerCoordinatesPairServerRoomAndBindingTimeMustMatchBeforeRetention() = runTest {
        for (variant in 0..6) {
            val f = SpaceRoomBindingPreparationFixture(legacy = true)
            val room = assertNotNull(f.original.room)
            val original =
                when (variant) {
                    0 -> f.original.copy(owner = f.owner.copy(spaceId = f.access.accountId))
                    1 ->
                        f.original.copy(
                            conversation =
                                f.original.conversation.copy(
                                    position = f.position.copy(companyId = f.access.spaceId)
                                )
                        )
                    2 ->
                        f.original.copy(
                            conversation =
                                f.original.conversation.copy(
                                    secondAccountId = "a4000000-0000-4000-8000-000000000001"
                                )
                        )
                    3 -> f.original.copy(room = null)
                    4 -> f.original.copy(room = room.copy(serverName = "other.example.test"))
                    5 -> f.original.copy(room = room.copy(roomId = "!other:matrix.example.test"))
                    else -> f.original.copy(room = room.copy(boundAt = room.boundAt + 1.seconds))
                }
            f.onOriginal = { AppResult.Success(original) }
            assertEquals(
                "client.collaboration_invalid_response",
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.code,
            )
            assertEquals(listOf(f.owner to f.position), f.originalReads)
            assertTrue(f.canonicalWrites.isEmpty() && f.bindingWrites.isEmpty())
        }
    }

    @Test
    fun originalNativeDeadlineWallRollbackAndMonotonicBudgetCannotBeExtendedByRenewal() = runTest {
        for (variant in 0..2) {
            val f = SpaceRoomBindingPreparationFixture()
            f.native.setup.session =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                        )
                )
            f.source.onRoom = {
                when (variant) {
                    0 -> {
                        f.native.setup.now += 3.seconds
                        f.native.setup.session = f.native.session
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    else -> f.native.time += 2.seconds
                }
                AppResult.Success(f.source.view)
            }
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.code,
            )
            assertEquals(1, f.source.roomReads)
            assertTrue(f.bindingWrites.isEmpty())
        }
    }

    @Test
    fun ownershipChangesAtEveryAcquisitionAndWriteBoundaryCannotReturnALateBinding() = runTest {
        for (phase in 0..6) {
            val f = SpaceRoomBindingPreparationFixture(legacy = true)
            when (phase) {
                0 -> f.beforeBindingRead = { f.native.setup.session = null }
                1 -> f.source.onRoom = {
                        f.native.setup.session = null
                        AppResult.Success(f.source.view)
                    }
                2 -> f.onOriginal = {
                        f.native.setup.session = null
                        AppResult.Success(f.original)
                    }
                3 -> f.beforeCanonicalRead = { f.native.setup.session = null }
                4 -> f.onCanonicalWrite = {
                        f.canonical = AppResult.Success(it.localMetadata())
                        f.native.setup.session = null
                        AppResult.Success(true)
                    }
                5 -> f.onBindingWrite = {
                        f.retained = AppResult.Success(it)
                        f.native.setup.session = null
                        AppResult.Success(true)
                    }
                else -> f.beforeBindingRead = {
                        if (f.bindingReads == 2) f.native.setup.session = null
                    }
            }
            assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            assertEquals(0, f.base.admits)
        }
    }

    @Test
    fun cancellationDrainsTheOnePendingReadAndDoesNotScheduleRecovery() = runTest {
        val f = SpaceRoomBindingPreparationFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.source.onRoom = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val job = async { f.prepare()(f.conversation) }
        entered.await()
        job.cancelAndJoin()
        stopped.await()
        assertFailsWith<CancellationException> { job.await() }
        advanceTimeBy(60_000)
        assertEquals(1, f.source.roomReads)
        assertTrue(f.bindingWrites.isEmpty() && f.canonicalWrites.isEmpty())
        assertTrue(f.scheduleRequests.isEmpty())
    }

    @Test
    fun nonCooperativeLateRemoteSuccessCannotWriteAfterTheOperationTimeout() = runTest {
        val f = SpaceRoomBindingPreparationFixture()
        f.source.onRoom = {
            withContext(NonCancellable) { delay(20_000) }
            AppResult.Success(f.source.view)
        }
        assertEquals(
            "client.collaboration_recovery_timeout",
            assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.code,
        )
        assertEquals(1, f.source.roomReads)
        assertTrue(f.canonicalWrites.isEmpty() && f.bindingWrites.isEmpty())
    }

    @Test
    fun lostCanonicalAndBindingResponsesReadBackTheExactSavedFactsWithoutAnotherWrite() = runTest {
        val f = SpaceRoomBindingPreparationFixture(legacy = true)
        f.onCanonicalWrite = {
            f.canonical = AppResult.Success(it.localMetadata())
            AppResult.Failed(f.base.storageFailure)
        }
        f.onBindingWrite = {
            f.retained = AppResult.Success(it)
            AppResult.Failed(f.base.storageFailure)
        }
        assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
        assertEquals(1, f.canonicalWrites.size)
        assertEquals(1, f.bindingWrites.size)
        assertEquals(2, f.canonicalReads)
        assertEquals(2, f.bindingReads)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduleRequests)
        assertEquals(1, f.source.roomReads)
        assertEquals(1, f.originalReads.size)
    }

    @Test
    fun uncertainOrConflictingCanonicalFactsNeverPublishARoomBinding() = runTest {
        for (variant in 0..2) {
            val f = SpaceRoomBindingPreparationFixture()
            when (variant) {
                0 ->
                    f.canonical =
                        AppResult.Success(
                            f.conversation
                                .localMetadata()
                                .copy(createdBy = f.conversation.firstAccountId)
                        )
                1 -> f.onCanonicalWrite = { AppResult.Success(false) }
                else -> f.onCanonicalWrite = { AppResult.Failed(f.base.storageFailure) }
            }
            val result = assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            assertEquals(
                if (variant == 2) FailureKind.Storage else FailureKind.Conflict,
                result.failure.kind,
            )
            assertTrue(f.bindingWrites.isEmpty())
            assertEquals(if (variant == 0) 0 else 1, f.canonicalWrites.size)
        }
    }

    @Test
    fun uncertainOrContradictoryBindingReadbackNeverBecomesAnAcknowledgement() = runTest {
        for (variant in 0..2) {
            val f = SpaceRoomBindingPreparationFixture()
            f.canonical = AppResult.Success(f.conversation.localMetadata())
            f.onBindingWrite = {
                when (variant) {
                    0 -> AppResult.Success(false)
                    1 -> AppResult.Failed(f.base.storageFailure)
                    else -> {
                        f.retained =
                            AppResult.Success(
                                it.copy(
                                    room = it.room.copy(roomId = "!different:matrix.example.test")
                                )
                            )
                        AppResult.Success(true)
                    }
                }
            }
            val result = assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            assertEquals(
                if (variant == 1) FailureKind.Storage else FailureKind.Conflict,
                result.failure.kind,
            )
            assertEquals(1, f.bindingWrites.size)
            assertTrue(f.canonicalWrites.isEmpty())
        }
    }

    @Test
    fun cancelledCommittedBindingIsRecoveredLocallyOnTheNextInvocation() = runTest {
        val f = SpaceRoomBindingPreparationFixture(legacy = true)
        f.onBindingWrite = {
            f.retained = AppResult.Success(it)
            throw CancellationException("Lost committed response")
        }
        assertFailsWith<CancellationException> { f.prepare()(f.conversation) }
        f.native.setup.now += 1000.days
        assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
        assertEquals(1, f.source.roomReads)
        assertEquals(1, f.originalReads.size)
        assertEquals(1, f.bindingWrites.size)
        assertTrue(f.scheduleRequests.isEmpty())
    }

    @Test
    fun lateDenialKeepsTheSavedMetadataWithoutReopeningContentAdmission() = runTest {
        val f = SpaceRoomBindingPreparationFixture()
        f.onBindingWrite = {
            f.retained = AppResult.Success(it)
            f.base.deny()
            AppResult.Success(true)
        }
        assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
        assertEquals(AppResult.Success(f.binding), f.prepare()(f.conversation))
        assertEquals(1, f.source.roomReads)
        assertEquals(1, f.bindingWrites.size)
        assertEquals(0, f.base.admits)
        assertEquals(f.base.denied, (f.base.gate.value as AppResult.Success).value.denial)
    }
}
