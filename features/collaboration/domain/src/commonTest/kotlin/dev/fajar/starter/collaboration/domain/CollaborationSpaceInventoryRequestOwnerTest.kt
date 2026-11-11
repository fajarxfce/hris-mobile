package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.usecases.DeferCollaborationSpaceInventory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CollaborationSpaceInventoryRequestOwnerTest {
    @Test
    fun lossDuringNativeReadFinishesTheActivePassAndPromotesTheNewestRequest() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["inventory"] = {
            f.progress(
                f.captured.copy(position = "later", inventoryRequestedGeneration = f.base.id(750))
            )
        }
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Advanced), f.run())
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(f.base.source, "later", f.base.id(750)),
            f.snapshot?.demand?.sources?.single(),
        )
        assertEquals(1, f.enumerations)
        assertEquals(1, f.writes)
        assertNotNull(f.wake)
        assertEquals(listOf("!room:test"), f.snapshot?.demand?.physicalRooms?.map { it.roomId })
    }

    @Test
    fun laterRequestDuringPartialPageReadbackSurvivesEvenWhenTheWriteReplyIsLost() = runTest {
        for (lost in listOf(false, true)) {
            val f = SpaceRoomInventoryRetentionFixture()
            partialPage(f)
            f.replyLost = lost
            f.hooks["committed"] = {
                val kept = assertNotNull(f.snapshot).demand.sources.single()
                f.progress(
                    kept.copy(position = "latest", inventoryRequestedGeneration = f.base.id(751))
                )
            }
            assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Advanced), f.run())
            assertEquals(
                f.captured.copy(
                    position = "latest",
                    inventoryAfterRoomId = "!015:test",
                    inventoryRequestedGeneration = f.base.id(751),
                ),
                f.snapshot?.demand?.sources?.single(),
            )
            assertEquals(16, f.snapshot?.demand?.physicalRooms?.size)
            assertEquals(1, f.writes)
            assertNotNull(f.wake)
        }
    }

    @Test
    fun aPromotedPassKeepsItsOwnLaterRequestAndRejectsTheOldInvocation() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.progress(f.captured.copy(inventoryRequestedGeneration = f.base.id(752)))
        f.hooks["committed"] = {
            val kept = assertNotNull(f.snapshot).demand.sources.single()
            f.progress(kept.copy(position = "later", inventoryRequestedGeneration = f.base.id(753)))
        }
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Advanced), f.run())
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(
                f.base.source,
                "later",
                f.base.id(752),
                inventoryRequestedGeneration = f.base.id(753),
            ),
            f.snapshot?.demand?.sources?.single(),
        )
        val saved = f.snapshot
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete), f.run())
        assertEquals(saved, f.snapshot)
        assertEquals(1, f.writes)
        assertEquals(1, f.enumerations)
    }

    @Test
    fun lossWinningTheCasKeepsTheOldCursorAndRequestsAShortContentionRetry() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val newer =
            f.captured.copy(position = "later", inventoryRequestedGeneration = f.base.id(754))
        f.hooks["write"] = { f.progress(newer) }
        assertTrue(
            isCollaborationSpaceIncomingQueueContention(assertIs<AppResult.Failed>(f.run()).failure)
        )
        assertEquals(newer, f.snapshot?.demand?.sources?.single())
        assertTrue(assertNotNull(f.snapshot).demand.physicalRooms.isEmpty())
        assertNull(f.wake)
        assertEquals(1, f.writes)
    }

    @Test
    fun cancellationAfterPageCommitKeepsCursorWorkRequestAndWakeForTheNextOwner() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        partialPage(f)
        f.progress(f.captured.copy(inventoryRequestedGeneration = f.base.id(755)))
        f.hooks["committed"] = { awaitCancellation() }
        val task = async { f.run() }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals("!015:test", f.snapshot?.demand?.sources?.single()?.inventoryAfterRoomId)
        assertEquals(
            f.base.id(755),
            f.snapshot?.demand?.sources?.single()?.inventoryRequestedGeneration,
        )
        assertEquals(16, f.snapshot?.demand?.physicalRooms?.size)
        assertNotNull(f.wake)
        val saved = f.snapshot
        assertEquals(AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete), f.run())
        assertEquals(saved, f.snapshot)
        assertEquals(1, f.writes)
    }

    @Test
    fun deferralUsesTheCurrentRequestWithoutRewindingItsSourcePosition() = runTest {
        for (stage in listOf("demand1", "demand2")) {
            val f = SpaceRoomInventoryRetentionFixture()
            val wake = CollaborationSpaceIncomingWake(f.base.id(799))
            f.wake = wake
            f.hooks[stage] = {
                val current = assertNotNull(f.snapshot).demand.sources.single()
                f.progress(
                    current.copy(position = "later", inventoryRequestedGeneration = f.base.id(756))
                )
            }
            assertEquals(AppResult.Success(true), defer(f))
            val current = assertNotNull(f.snapshot).demand.sources.single()
            assertEquals("later", current.position)
            assertEquals(f.base.id(756), current.inventoryRequestedGeneration)
            assertEquals(f.captured.inventoryGeneration, current.inventoryGeneration)
            assertNotNull(current.inventoryRetry)
            assertEquals(wake, f.wake)
            assertEquals(1, f.writes)
            assertEquals(0, f.enumerations)
        }
    }

    @Test
    fun anUncertainDeferralReplyConfirmsRetryWhileKeepingANewerRequestedPass() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.replyLost = true
        f.hooks["committed"] = {
            val kept = assertNotNull(f.snapshot).demand.sources.single()
            f.progress(kept.copy(inventoryRequestedGeneration = f.base.id(757)))
        }
        assertEquals(AppResult.Success(true), defer(f))
        val saved = f.snapshot
        assertEquals(f.base.id(757), saved?.demand?.sources?.single()?.inventoryRequestedGeneration)
        assertNotNull(saved?.demand?.sources?.single()?.inventoryRetry)
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(saved, f.snapshot)
        assertEquals(1, f.writes)
    }

    @Test
    fun cancellationAfterDeferralCommitRetainsBothPassesAndDoesNotExtendBackoff() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.progress(f.captured.copy(inventoryRequestedGeneration = f.base.id(758)))
        f.hooks["committed"] = { awaitCancellation() }
        val task = async { defer(f) }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        val saved = f.snapshot
        assertEquals(
            f.captured.inventoryGeneration,
            saved?.demand?.sources?.single()?.inventoryGeneration,
        )
        assertEquals(f.base.id(758), saved?.demand?.sources?.single()?.inventoryRequestedGeneration)
        assertNotNull(saved?.demand?.sources?.single()?.inventoryRetry)
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(saved, f.snapshot)
        assertEquals(1, f.writes)
    }

    private fun partialPage(f: SpaceRoomInventoryRetentionFixture) {
        f.page =
            f.page.copy(
                rooms =
                    (0 until 16).map {
                        CollaborationSpaceInventoryRoom(
                            "!" + it.toString().padStart(3, '0') + ":test",
                            CollaborationSpaceInventoryMembership.Joined,
                        )
                    },
                nextAfterRoomId = "!015:test",
            )
    }

    private suspend fun defer(f: SpaceRoomInventoryRetentionFixture) =
        DeferCollaborationSpaceInventory(
            f.demands,
            f.base.registrations,
            f.base.sync,
            f.base.directory.directory,
            f.base.native.setup.sessions,
            f.base.native.server,
            f.base.setup.build,
            f.base.native.clock,
        )(f.runtime, f.captured, null)
}
