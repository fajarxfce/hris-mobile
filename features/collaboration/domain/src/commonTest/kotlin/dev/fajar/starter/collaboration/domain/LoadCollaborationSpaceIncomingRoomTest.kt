package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceSyncRepository
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSpaceIncomingRoom
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

class LoadCollaborationSpaceIncomingRoomTest {
    @Test
    fun sourceBoundRoomRequiresItsExactInstalledOriginalWithoutStartingSync() = runTest {
        val f = Fixture()
        f.attachSource()
        val local =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSpaceIncomingRoom?>>(f.load()).value
            )
        assertEquals(f.source.storeId, local.captured.sourceId)
        assertEquals(f.request.key, local.traversal)
        assertEquals(listOf("demand", "binding"), f.request.base.calls)
        assertTrue(f.sourceReads > 0)
    }

    @Test
    fun absentOrDifferentInstalledStoreCannotReadSourceBoundRoom() = runTest {
        for (missing in listOf(false, true)) {
            val f = Fixture()
            f.attachSource()
            f.installed = if (missing) null else f.source.copy(storeId = "2".repeat(64))
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertTrue(f.request.base.calls.isEmpty())
        }
    }

    @Test
    fun exactStoreNameDoesNotHideChangedMatrixIdentityInRetainedProgress() = runTest {
        val f = Fixture()
        f.attachSource()
        val snapshot = assertNotNull(f.request.snapshot)
        f.request.snapshot =
            snapshot.copy(
                demand =
                    snapshot.demand.copy(
                        sources =
                            snapshot.demand.sources.map {
                                it.copy(source = it.source.copy(matrixDeviceId = "OTHER"))
                            }
                    )
            )
        assertIs<AppResult.Failed>(f.load())
        assertEquals(listOf("demand"), f.request.base.calls)
    }

    @Test
    fun lateRuntimeOrNativeParentReplacementFencesSourceBoundRoomReads() = runTest {
        for (stage in listOf("demand", "binding")) for (native in listOf(false, true)) {
            val f = Fixture()
            f.attachSource()
            f.request.base.hooks[stage] = {
                if (native) {
                    val setup = f.request.base.native.setup
                    val session = assertNotNull(setup.session)
                    setup.session =
                        session.copy(
                            binding =
                                session.binding.copy(serverId = f.request.provider.queue.id(999))
                        )
                } else f.installed = f.source.copy(matrixDeviceId = "OTHER")
            }
            assertEquals(
                if (native) FailureKind.Unauthorized else FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.load()).failure.kind,
                stage,
            )
        }
    }

    @Test
    fun olderUnscopedWorkStillReadsLocallyWithoutAcquiringAnIncomingSource() = runTest {
        val f = Fixture()
        f.installed = null
        assertIs<AppResult.Success<*>>(f.load())
        assertEquals(0, f.sourceReads)
    }

    @Test
    fun capturedRoomResolvesCanonicalLocalCoordinatesWithoutTransport() = runTest {
        val f = Fixture()
        val room =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSpaceIncomingRoom?>>(f.load()).value
            )
        assertEquals(f.captured, room.captured)
        assertEquals(f.request.provider.conversation, room.conversation)
        assertEquals(f.request.key, room.traversal)
        assertEquals(listOf("demand", "binding"), f.request.base.calls)
    }

    @Test
    fun anAbsentOrNewerQueuedGenerationDoesNotReadTheOldBinding() = runTest {
        for (absent in listOf(false, true)) {
            val f = Fixture()
            val before = f.request.snapshot!!
            f.request.snapshot =
                before.copy(
                    demand =
                        before.demand.copy(
                            rooms =
                                if (absent) emptyList()
                                else
                                    listOf(
                                        f.captured.copy(
                                            generation = f.request.provider.queue.id(999)
                                        )
                                    )
                        )
                )
            assertEquals(AppResult.Success(null), f.load())
            assertEquals(listOf("demand"), f.request.base.calls)
        }
    }

    @Test
    fun changedFactsUnderTheCapturedGenerationCannotBeLoadedAsTheOldTask() = runTest {
        val f = Fixture()
        val before = f.request.snapshot!!
        f.request.snapshot =
            before.copy(
                demand =
                    before.demand.copy(
                        rooms =
                            listOf(f.captured.copy(phase = CollaborationSpaceIncomingPhase.Latest))
                    )
            )
        assertIs<AppResult.Failed>(f.load())
        assertEquals(listOf("demand"), f.request.base.calls)
    }

    @Test
    fun missingOrForeignCanonicalBindingDoesNotAcquireReplacementMetadata() = runTest {
        for (missing in listOf(false, true)) {
            val f = Fixture()
            val binding = f.request.base.retained!!
            f.request.base.retained =
                if (missing) null
                else
                    binding.copy(
                        conversation =
                            binding.conversation.copy(spaceId = f.request.provider.queue.id(999))
                    )
            assertIs<AppResult.Failed>(f.load())
            assertEquals(listOf("demand", "binding"), f.request.base.calls)
        }
    }

    @Test
    fun localCredentialAgeDoesNotBlockOwnedRoomCoordinates() = runTest {
        val f = Fixture()
        f.request.base.native.setup.now += 365.days
        assertIs<AppResult.Success<*>>(f.load())
    }

    @Test
    fun denialAndLateAccountReplacementCannotPublishTheOldRoom() = runTest {
        for (stage in listOf("denied", "demand", "binding")) {
            val f = Fixture()
            if (stage == "denied") f.request.base.directory.deny()
            else f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.load())
        }
    }

    @Test
    fun stalledBindingReadTimesOutWithoutAnotherAttempt() = runTest {
        val f = Fixture()
        f.request.base.hooks["binding"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(listOf("demand", "binding"), f.request.base.calls)
    }

    @Test
    fun cancelledBindingReadCannotPublishALateResult() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.request.base.hooks["binding"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.load() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, f.request.writes)
    }

    private class Fixture {
        val request = SpaceIncomingRequestFixture().also { it.queued() }
        var captured = request.snapshot!!.demand.rooms.single()
        val source =
            CollaborationSpaceIncomingSource(
                "1".repeat(64),
                request.base.queue.identity.matrixUserId,
                request.base.model.transport.matrixDeviceId,
            )
        var installed: CollaborationSpaceIncomingSource? = source
        var sourceReads = 0
        private val sync =
            object : CollaborationSpaceSyncRepository {
                override fun incomingSource(
                    runtime: CollaborationSpaceRuntimeReady
                ): CollaborationSpaceIncomingSource? {
                    assertEquals(request.provider.runtime, runtime)
                    sourceReads++
                    return installed
                }

                override suspend fun synchronize(
                    runtime: CollaborationSpaceRuntimeReady
                ): AppResult<Unit> = error("No sync start")

                override fun observeBatches(
                    runtime: CollaborationSpaceRuntimeReady
                ): Flow<AppResult<CollaborationSpaceSyncBatch>> = error("No subscription")
            }
        val useCase =
            LoadCollaborationSpaceIncomingRoom(
                request.demands,
                request.base.bindings,
                request.base.directory.directory,
                request.base.native.setup.sessions,
                request.base.native.server,
                sync,
            )

        suspend fun load() = useCase(request.provider.runtime, captured)

        fun attachSource() {
            captured = captured.copy(sourceId = source.storeId)
            val snapshot = assertNotNull(request.snapshot)
            request.snapshot =
                snapshot.copy(
                    demand =
                        snapshot.demand.copy(
                            rooms = listOf(captured),
                            sources =
                                listOf(CollaborationSpaceIncomingSourceProgress(source, "position")),
                        )
                )
        }
    }
}
