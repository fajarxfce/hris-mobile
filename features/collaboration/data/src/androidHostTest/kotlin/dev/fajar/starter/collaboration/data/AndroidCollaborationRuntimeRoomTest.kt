@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationTimelineLease
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

/** Parent/child integration uses the real lease and raw room adapter with inert FFI handles. */
class AndroidCollaborationRuntimeRoomTest {
    @Test
    fun joinedRoomEntryKeepsItsTimelineUnderTheOriginalParentWithoutJoiningOrSending() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val room = QueueRoom()
        f.readRoom = {
            assertEquals("!original:test", it)
            room
        }
        try {
            f.lease.completeAcquisition()
            val timeline =
                assertIs<AppResult.Success<AndroidCollaborationTimelineLease>>(
                        f.lease.enterTimeline("!original:test")
                    )
                    .value
            assertEquals(f.lease.owner, timeline.owner)
            assertEquals("!original:test", timeline.matrixRoomId)
            assertIs<AppResult.Success<*>>(timeline.updates.first())
            assertEquals(1, f.calls.count { it == "room.lookup" })
            assertFalse("room.join" in f.calls)
            assertFalse("room.close" in room.calls)
            assertFalse(room.enabled)
            assertEquals(AppResult.Success(Unit), timeline.close())
            assertEquals(1, room.calls.count { it == "room.close" })
            assertFalse("client.close" in f.calls)
        } finally {
            f.close()
        }
        assertEquals(1, room.calls.count { it == "room.close" })
        assertEquals(1, f.calls.count { it == "client.close" })
    }

    @Test
    fun credentialsRejectedDuringLocalAcquisitionStillPreventTheTimelineFromEscaping() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val room = QueueRoom()
        f.readRoom = {
            f.persistence.reportAuthenticationFailure()
            room
        }
        try {
            f.lease.completeAcquisition()
            assertIs<AppResult.Failed>(f.lease.enterTimeline("!original:test"))
            assertFalse("room.join" in f.calls)
            assertFalse(room.enabled)
            assertEquals(1, room.calls.count { it == "room.close" })
        } finally {
            f.close()
        }
        assertEquals(1, room.calls.count { it == "room.close" })
    }
}
