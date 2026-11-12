package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceIncomingDiscoveryPolicyTest {
    @Test
    fun canonicalCompanyWorkConsumesOnlyItsAliasWithoutResettingTheTask() = runTest {
        for (phase in CollaborationSpaceIncomingPhase.entries) {
            val f = SpaceIncomingScanFixture()
            val binding = f.request.base.model.binding
            assertTrue(validCollaborationSpaceRoomBinding(f.access, binding))
            val original = assertNotNull(binding.original)
            val source = f.source
            val room =
                CollaborationSpaceIncomingRoomDemand(
                    CollaborationSpaceIncomingTarget.Original(original.conversation.position),
                    f.id(880),
                    0,
                    "\$retained",
                    phase,
                    CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                    source.storeId,
                )
            f.set(
                f.current()
                    .copy(
                        nextPosition = 1,
                        rooms = listOf(room),
                        sources =
                            listOf(CollaborationSpaceIncomingSourceProgress(source, "position")),
                    )
            )
            assertTrue(validCollaborationSpaceIncomingDemand(f.access, f.current()))
            f.nextPage = f.page(0, null).copy(bindings = listOf(binding))
            assertEquals(AppResult.Success(true), f.discover(f.access))
            assertEquals(listOf(room), f.current().rooms)
            assertNull(f.current().rescanGeneration)
            assertEquals(1, f.request.writes)
        }
    }

    @Test
    fun anotherCompanyWithTheSameConversationIdDoesNotCoverTheBinding() {
        val f = SpaceIncomingScanFixture()
        val binding = f.request.base.model.binding
        val original = assertNotNull(binding.original).conversation.position
        val source = f.source
        val room =
            CollaborationSpaceIncomingRoomDemand(
                CollaborationSpaceIncomingTarget.Original(original.copy(companyId = f.id(889))),
                f.id(880),
                0,
                null,
                CollaborationSpaceIncomingPhase.Latest,
                sourceId = source.storeId,
            )
        val demand =
            f.current()
                .copy(
                    nextPosition = 1,
                    rooms = listOf(room),
                    sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "position")),
                )
        assertTrue(validCollaborationSpaceIncomingDemand(f.access, demand))
        assertEquals(
            listOf(binding),
            undiscoveredCollaborationSpaceIncomingBindings(
                demand,
                listOf(binding),
                f.registered.command.legacy,
            ),
        )
    }

    @Test
    fun joinedPhysicalDiscoveryConsumesTheCursorWithoutCreatingALogicalCompetitor() = runTest {
        for (lostReply in listOf(false, true)) {
            val f = SpaceIncomingScanFixture()
            val binding = f.request.base.model.binding
            val source = f.source
            val physical =
                CollaborationSpaceIncomingPhysicalRoomDemand(
                    source.storeId,
                    binding.room.roomId,
                    f.id(880),
                    0,
                    "\$retained",
                    CollaborationSpaceIncomingMembership.Joined,
                    false,
                    CollaborationSpaceIncomingRetry(1_000, 31_000, 1),
                )
            val demand =
                f.current()
                    .copy(
                        nextPosition = 1,
                        physicalRooms = listOf(physical),
                        sources =
                            listOf(CollaborationSpaceIncomingSourceProgress(source, "position")),
                    )
            f.set(demand)
            f.request.responseLoss = lostReply
            f.nextPage = f.page(0, null).copy(bindings = listOf(binding))
            assertEquals(AppResult.Success(true), f.discover(f.access))
            assertEquals(demand.copy(rescanGeneration = null), f.current())
            assertEquals(f.originalWake, f.request.wake)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun differentRoomUnretainedSourceAndLeftHintDoNotSuppressDiscovery() {
        val f = SpaceIncomingScanFixture()
        val binding = f.request.base.model.binding
        val source = f.source
        val physical =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                source.storeId,
                binding.room.roomId,
                f.id(880),
                0,
                null,
                CollaborationSpaceIncomingMembership.Joined,
                true,
            )
        val demand =
            f.current()
                .copy(
                    nextPosition = 1,
                    physicalRooms = listOf(physical),
                    sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "position")),
                )
        for (changed in
            listOf(
                demand.copy(sources = emptyList()),
                demand.copy(physicalRooms = listOf(physical.copy(roomId = "!other:test"))),
                demand.copy(
                    physicalRooms =
                        listOf(
                            physical.copy(membership = CollaborationSpaceIncomingMembership.Left)
                        )
                ),
            )) assertEquals(
            listOf(binding),
            undiscoveredCollaborationSpaceIncomingBindings(
                changed,
                listOf(binding),
                f.registered.command.legacy,
            ),
        )
    }

    @Test
    fun previousDeviceCannotSuppressDiscoveryThroughTheCurrentRegisteredSource() = runTest {
        val f = SpaceIncomingScanFixture()
        val binding = f.request.base.model.binding
        val other = f.source.copy(storeId = "b".repeat(64), matrixDeviceId = "OTHER")
        val physical =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                other.storeId,
                binding.room.roomId,
                f.id(880),
                0,
                null,
                CollaborationSpaceIncomingMembership.Joined,
                true,
            )
        val before =
            f.current()
                .copy(
                    nextPosition = 1,
                    physicalRooms = listOf(physical),
                    sources =
                        listOf(
                            CollaborationSpaceIncomingSourceProgress(f.source, "current"),
                            CollaborationSpaceIncomingSourceProgress(other, "previous"),
                        ),
                )
        f.set(before)
        f.nextPage = f.page(0, null).copy(bindings = listOf(binding))
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertEquals(before.physicalRooms, f.current().physicalRooms)
        assertEquals(before.sources, f.current().sources)
        assertEquals(
            CollaborationSpaceIncomingTarget.Published(binding.conversation.id),
            f.current().rooms.single().target,
        )
    }

    @Test
    fun registrationReplacementBeforeWriteCannotConsumeTheScanCursor() = runTest {
        val f = SpaceIncomingScanFixture()
        val before = f.current()
        f.request.base.hooks["registration"] = {
            if (f.registrationReads == 2) f.registration = null
        }
        assertIs<AppResult.Failed>(f.discover(f.access))
        assertEquals(before, f.current())
        assertEquals(0, f.request.writes)
    }

    @Test
    fun registrationReplacementAfterCommitIsNotReportedAsCurrentSuccess() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.hooks["committed"] = { f.registration = null }
        assertIs<AppResult.Failed>(f.discover(f.access))
        assertEquals(4, f.current().rooms.size)
        assertNotNull(f.current().scan)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun registrationReadFailureOrLateAccountReplacementLeavesDiscoveryRetained() = runTest {
        for (replacement in listOf(false, true)) {
            val f = SpaceIncomingScanFixture()
            val before = f.current()
            if (replacement)
                f.request.base.hooks["registration"] = {
                    f.request.base.native.setup.session = null
                }
            else f.request.base.failures["registration"] = f.request.base.directory.storageFailure
            val failure = assertIs<AppResult.Failed>(f.discover(f.access)).failure
            assertEquals(
                if (replacement) FailureKind.Unauthorized else FailureKind.Storage,
                failure.kind,
            )
            if (replacement) assertEquals("client.session_changed", failure.code)
            assertEquals(before, f.current())
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun aDifferentNativeFamilyStopsBeforeMetadataReadsWithoutAQueueRetry() = runTest {
        val f = SpaceIncomingScanFixture()
        val before = f.current()
        val session = assertNotNull(f.request.base.native.setup.session)
        f.request.base.native.setup.session =
            session.copy(binding = session.binding.copy(serverId = f.id(990)))
        val failure = assertIs<AppResult.Failed>(f.discover(f.access)).failure
        assertEquals(FailureKind.Unauthorized, failure.kind)
        assertEquals(before, f.current())
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.registrationReads)
        assertEquals(0, f.request.writes)
    }
}
