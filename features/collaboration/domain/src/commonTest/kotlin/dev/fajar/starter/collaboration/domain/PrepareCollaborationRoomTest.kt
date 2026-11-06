@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PrepareCollaborationRoomTest {
    @Test
    fun aReadyRoomNeedsNoQueueReadSchedulingOrCommand() = runTest {
        val f = RoomPreparationFixture()
        f.base.room = f.base.room.copy(state = CollaborationDirectRoomState.READY)
        assertEquals(AppResult.Success(CollaborationRoomReadiness.READY), f.prepare())
        assertEquals(0, f.messageReads)
        assertTrue(f.scheduled.isEmpty())
        assertTrue(f.base.values.isEmpty())
    }

    @Test
    fun anEmptyRoomDoesNotProvisionImplicitlyAndPendingWorkOnlyWakesTheWorker() = runTest {
        val f = RoomPreparationFixture()
        assertEquals(AppResult.Success(CollaborationRoomReadiness.WAITING), f.prepare())
        assertTrue(f.scheduled.isEmpty())
        f.addPending()
        f.base.beforeRemote = {
            if (it == "room" && ++f.roomReads == 3)
                f.base.room = f.base.room.copy(state = CollaborationDirectRoomState.READY)
        }
        assertEquals(AppResult.Success(CollaborationRoomReadiness.READY), f.prepare())
        assertEquals(2_000, testScheduler.currentTime)
        assertEquals(1, f.scheduled.size)
        assertTrue(f.base.values.isEmpty())
        assertTrue(f.base.sent.isEmpty())
        assertEquals(0, f.activeObservers)
    }

    @Test
    fun pollingIsFiniteAndNeverReplaysProvisioningFromTheCaller() = runTest {
        val f = RoomPreparationFixture()
        f.addPending()
        f.base.beforeRemote = { if (it == "room") f.roomReads++ }
        assertEquals(AppResult.Success(CollaborationRoomReadiness.WAITING), f.prepare())
        assertEquals(13, f.roomReads)
        assertEquals(22_000, testScheduler.currentTime)
        assertEquals(1, f.scheduled.size)
        assertTrue(f.base.sent.isEmpty())
    }

    @Test
    fun explicitRetryRetainsItsIdentityAfterAnUncertainInsertion() = runTest {
        val f = RoomPreparationFixture()
        f.addPending()
        f.failedRoom()
        assertEquals(AppResult.Success(CollaborationRoomReadiness.WAITING), f.prepare())
        assertTrue(f.base.values.isEmpty())
        f.afterInsert = { throw CancellationException("Fixture lost insertion response") }
        assertFailsWith<CancellationException> { f.prepare(retry = true) }
        val retained = f.base.values.values.single()
        assertEquals(1, f.insertions)
        f.afterInsert = {}
        assertEquals(AppResult.Success(CollaborationRoomReadiness.WAITING), f.prepare(retry = true))
        assertEquals(listOf(retained), f.base.values.values.toList())
        assertEquals(1, f.insertions)
        assertTrue(f.base.sent.isEmpty())
        assertEquals(
            CollaborationConversationAction.RequestRoom(COMMAND_ROOM, COMMAND_DEVICE, 1, 7),
            retained.command.action,
        )
    }

    @Test
    fun aChangedVersionOrMissingRetryGenerationCannotCreateAnInitialRequest() = runTest {
        for (missing in listOf(false, true)) {
            val f = RoomPreparationFixture()
            f.addPending()
            f.failedRoom()
            if (missing) f.base.room = f.base.room.copy(version = null)
            else
                f.base.beforeRemote = {
                    if (it == "room" && ++f.roomReads == 2)
                        f.base.room = f.base.room.copy(version = 8)
                }
            val failure = assertIs<AppResult.Failed>(f.prepare(retry = true)).failure
            assertEquals("client.collaboration_room_changed", failure.code)
            assertTrue(f.base.values.isEmpty())
            assertTrue(f.scheduled.isEmpty())
            assertTrue(f.base.sent.isEmpty())
        }
    }

    @Test
    fun accountReplacementDuringALocalReadStopsPreparationBeforeScheduling() = runTest {
        val f = RoomPreparationFixture()
        f.addPending()
        f.beforeMessages = { f.base.setup.session = f.base.setup.initial.copy(id = "replacement") }
        assertIs<AppResult.Failed>(f.prepare(retry = true))
        assertTrue(f.scheduled.isEmpty())
        assertTrue(f.base.values.isEmpty())
        assertEquals(0, f.activeObservers)
    }

    @Test
    fun aProviderDenialWinsWhenTheSubsequentOwnerReadAlsoFails() = runTest {
        val f = RoomPreparationFixture()
        val denied = Failure(FailureKind.AccessDenied, "Fixture denied", code = "fixture.denied")
        f.base.remoteFailure = denied
        f.base.beforeRemote = {
            f.base.setup.sessionFailure = Failure(FailureKind.Storage, "Fixture unreadable")
        }
        assertEquals(AppResult.Failed(denied), f.prepare())
        assertTrue(f.scheduled.isEmpty())
        assertTrue(f.base.values.isEmpty())
    }

    @Test
    fun cancellationReleasesLocalObservationAndPreventsLaterPolling() = runTest {
        for (duringRead in listOf(false, true)) {
            val f = RoomPreparationFixture()
            f.addPending()
            f.base.beforeRemote = { if (it == "room") f.roomReads++ }
            if (duringRead) f.beforeMessages = { awaitCancellation() }
            val job = launch { f.prepare() }
            runCurrent()
            job.cancelAndJoin()
            val reads = f.roomReads
            val wakes = f.scheduled.size
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(reads, f.roomReads)
            assertEquals(wakes, f.scheduled.size)
            assertEquals(0, f.activeObservers)
            assertTrue(f.base.values.isEmpty())
        }
    }

    @Test
    fun foreignPendingCommandsCannotWakeDelivery() = runTest {
        val f = RoomPreparationFixture()
        f.addPending()
        f.records =
            f.records.map {
                it.copy(intent = it.intent.copy(owner = it.intent.owner.copy(accountId = "other")))
            }
        assertIs<AppResult.Failed>(f.prepare())
        assertTrue(f.scheduled.isEmpty())
        assertTrue(f.base.sent.isEmpty())
    }
}
