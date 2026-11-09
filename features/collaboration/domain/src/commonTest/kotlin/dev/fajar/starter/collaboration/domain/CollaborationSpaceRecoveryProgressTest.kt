package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryProgressRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceRecoveryProgressTest {
    @Test
    fun claimedRoomAndRawCursorSurviveInterruptedWorkWithoutSkipping() = runTest {
        val f = Fixture()
        f.begin()
        val claim = f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(1)))
        val retained = f.snapshot()
        assertEquals(claim.snapshot, retained)
        assertNull(retained.progress!!.sweep!!.afterId)
        assertEquals(id(101), retained.progress.sweep!!.pending!!.conversationId)
        assertIs<AppResult.Failed>(
            f.update(retained, CollaborationSpaceRecoveryChange.Attempt(f.page(2)))
        )
        assertIs<AppResult.Failed>(f.update(retained, CollaborationSpaceRecoveryChange.Finish))
        f.complete()
        assertEquals(after(1), f.snapshot().progress!!.sweep!!.cursor!!.afterId)
    }

    @Test
    fun emptyIndexPagesAdvanceAndOnlyTheValidatedEndCanFinish() = runTest {
        val f = Fixture()
        f.begin()
        f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(1).copy(bindings = emptyList())))
        f.complete()
        assertFalse(f.snapshot().progress!!.sweep!!.reachedEnd)
        assertIs<AppResult.Failed>(f.update(f.snapshot(), CollaborationSpaceRecoveryChange.Finish))
        f.change(
            CollaborationSpaceRecoveryChange.Attempt(
                f.page(2, end = true).copy(bindings = emptyList())
            )
        )
        f.complete()
        assertEquals(f.wake, f.change(CollaborationSpaceRecoveryChange.Finish).completedWake)
    }

    @Test
    fun absentInventoryStillRequiresDurableClaimAndCompletion() = runTest {
        val f = Fixture()
        f.begin()
        f.change(
            CollaborationSpaceRecoveryChange.Attempt(
                CollaborationSpaceRoomBindingPage(null, emptyList(), null, false)
            )
        )
        assertIs<AppResult.Failed>(f.update(f.snapshot(), CollaborationSpaceRecoveryChange.Finish))
        f.complete()
        assertEquals(f.wake, f.change(CollaborationSpaceRecoveryChange.Finish).completedWake)
    }

    @Test
    fun newWakesCannotResetAClaimOrAcknowledgeANewerGeneration() = runTest {
        val f = Fixture()
        f.begin()
        f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(1, end = true)))
        val original = f.snapshot().progress!!.sweep
        repeat(20) {
            f.change(
                CollaborationSpaceRecoveryChange.Begin(CollaborationSpaceRecoveryWake(id(500 + it)))
            )
        }
        assertEquals(original, f.snapshot().progress!!.sweep)
        f.complete()
        assertEquals(f.wake, f.change(CollaborationSpaceRecoveryChange.Finish).completedWake)
    }

    @Test
    fun failureContinuationAndAppendsEachRequireAFreshSweepWithoutAcknowledgment() = runTest {
        for (mode in 0..2) {
            val f = Fixture()
            f.begin()
            f.change(
                CollaborationSpaceRecoveryChange.Attempt(
                    f.page(1).copy(hasNewerBindings = mode == 2)
                )
            )
            f.change(CollaborationSpaceRecoveryChange.Complete(mode == 0, mode == 1))
            f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(2, end = true)))
            f.complete()
            val old = f.snapshot().progress!!.sweep!!
            assertEquals(mode == 0, old.retryRequired)
            assertEquals(mode == 1, old.continuationRequired)
            assertEquals(mode == 2, old.hasNewerBindings)
            val restarted = f.change(CollaborationSpaceRecoveryChange.Finish)
            assertNull(restarted.completedWake)
            assertEquals(
                CollaborationSpaceRecoverySweep(f.wake.generation),
                restarted.snapshot.progress!!.sweep,
            )
        }
    }

    @Test
    fun lateCompletionCannotOverwriteAnotherCommittedCheckpoint() = runTest {
        val f = Fixture()
        f.begin()
        val old = f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(1))).snapshot
        f.complete()
        f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(2)))
        val later = f.snapshot()
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.update(old, CollaborationSpaceRecoveryChange.Complete(false))
                )
                .failure
                .kind,
        )
        assertEquals(later, f.snapshot())
    }

    @Test
    fun backwardForeignOrReplacedInventoryCannotAdvanceTheSweep() = runTest {
        val f = Fixture()
        f.begin()
        f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(2)))
        f.complete()
        val page = f.page(3)
        for (bad in
            listOf(
                f.page(1),
                f.page(2),
                page.copy(boundary = f.boundary.copy(generation = id(999))),
                page.copy(boundary = f.boundary.copy(revision = 99)),
                page.copy(boundary = f.boundary.copy(accountId = id(99))),
                page.copy(next = page.next!!.copy(boundary = f.boundary.copy(spaceId = id(98)))),
                page.copy(bindings = page.bindings + page.bindings),
                page.copy(boundary = null),
                page.copy(next = page.next!!.copy(afterId = "invalid")),
            )) assertIs<AppResult.Failed>(
            f.update(f.snapshot(), CollaborationSpaceRecoveryChange.Attempt(bad))
        )
        assertEquals(after(2), f.snapshot().progress!!.sweep!!.afterId)
    }

    @Test
    fun foreignConversationAndMalformedFirstBoundaryNeverWrite() = runTest {
        val f = Fixture()
        f.begin()
        val p = f.page(1)
        val binding = p.bindings.single()
        for (bad in
            listOf(
                p.copy(
                    bindings =
                        listOf(
                            binding.copy(conversation = binding.conversation.copy(spaceId = id(90)))
                        )
                ),
                p.copy(boundary = f.boundary.copy(revision = -1)),
                p.copy(boundary = f.boundary.copy(revision = Long.MAX_VALUE)),
                p.copy(boundary = f.boundary.copy(generation = "bad")),
                CollaborationSpaceRoomBindingPage(null, emptyList(), null, true),
            )) assertIs<AppResult.Failed>(
            f.update(f.snapshot(), CollaborationSpaceRecoveryChange.Attempt(bad))
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun oldTransportExpiryDoesNotHideOwnedProgressOrReadTheWorkspace() = runTest {
        val f = Fixture()
        f.setup.workspace = null
        f.setup.beforeWorkspace = { error("No selected-company dependency") }
        f.setup.session =
            f.setup.session!!.let {
                it.copy(
                    tokens = it.tokens.copy(expiresAtEpochMillis = 0),
                    binding = it.binding.copy(expiresAtEpochMillis = 0),
                )
            }
        f.begin()
        assertNotNull(f.snapshot().progress)
        assertEquals(0, f.setup.calls)
    }

    @Test
    fun missingOrReplacedAccountAndNativeFamilyCannotReadOrWrite() = runTest {
        val f = Fixture()
        val initial = f.snapshot()
        val session = f.setup.session!!
        val reads = f.reads
        for (replacement in
            listOf(
                null,
                session.copy(id = "new"),
                session.copy(user = session.user.copy(id = id(99))),
                session.copy(binding = session.binding.copy(serverId = id(98))),
                session.copy(binding = session.binding.copy(audience = "https://other.test/")),
            )) {
            f.setup.session = replacement
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read(f.owner)).failure.kind,
            )
            assertIs<AppResult.Failed>(
                f.update(initial, CollaborationSpaceRecoveryChange.Begin(f.wake))
            )
        }
        assertEquals(reads, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun ownerChangeAfterStorageRetainsFactsButRejectsTheirPublication() = runTest {
        val f = Fixture()
        val initial = f.snapshot()
        val session = f.setup.session!!
        f.afterWrite = {
            f.setup.session = session.copy(binding = session.binding.copy(serverId = id(80)))
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(
                    f.update(initial, CollaborationSpaceRecoveryChange.Begin(f.wake))
                )
                .failure
                .kind,
        )
        assertNotNull(f.stored)
        f.setup.session = session
        f.afterWrite = {}
        f.afterRead = { f.setup.session = null }
        assertIs<AppResult.Failed>(f.read(f.owner))
    }

    @Test
    fun cancelledWriteCannotPublishSuccessAndItsOriginalClaimRemains() = runTest {
        val f = Fixture()
        f.begin()
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterWrite = {
            withContext(NonCancellable) {
                committed.complete(Unit)
                release.await()
            }
        }
        val job = async { f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(1))) }
        committed.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(id(101), f.stored!!.sweep!!.pending!!.conversationId)
    }

    @Test
    fun storageFailureAndMalformedReadAreNotAnEmptyHealthySweep() = runTest {
        val f = Fixture()
        val initial = f.snapshot()
        f.failure = Failure(FailureKind.Storage, "Fixture unavailable")
        assertIs<AppResult.Failed>(f.read(f.owner))
        assertIs<AppResult.Failed>(
            f.update(initial, CollaborationSpaceRecoveryChange.Begin(f.wake))
        )
        f.failure = null
        f.stored = CollaborationSpaceRecoveryProgress("invalid", null)
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.read(f.owner)).failure.kind,
        )
    }

    @Test
    fun lostWakeAcknowledgmentStartsAFreshSweepInsteadOfAssumingCompletion() = runTest {
        val f = Fixture()
        f.begin()
        f.change(CollaborationSpaceRecoveryChange.Attempt(f.page(1, end = true)))
        f.complete()
        assertEquals(f.wake, f.change(CollaborationSpaceRecoveryChange.Finish).completedWake)
        f.begin()
        assertEquals(
            CollaborationSpaceRecoverySweep(f.wake.generation),
            f.snapshot().progress!!.sweep,
        )
    }

    private class Fixture {
        val setup =
            CollaborationSetupFixture().apply {
                session =
                    initial.copy(
                        user = initial.user.copy(id = id(1)),
                        binding = initial.binding.copy(serverId = id(2)),
                    )
            }
        val owner =
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    setup.session!!.id,
                    setup.session!!.binding.audience,
                    id(1),
                    id(2),
                ),
                id(3),
            )
        val wake = CollaborationSpaceRecoveryWake(id(4))
        val boundary =
            CollaborationSpaceRoomBindingBoundary(owner.parent.audience, id(1), id(3), id(5), 5)
        var stored: CollaborationSpaceRecoveryProgress? = null
        var reads = 0
        var writes = 0
        var failure: Failure? = null
        var afterRead: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        val repository =
            object : CollaborationSpaceRecoveryProgressRepository {
                override suspend fun current(
                    owner: CollaborationSpaceOwner
                ): AppResult<CollaborationSpaceRecoveryProgress?> {
                    reads++
                    failure?.let {
                        return AppResult.Failed(it)
                    }
                    val found = stored
                    afterRead()
                    return AppResult.Success(found)
                }

                override suspend fun replace(
                    owner: CollaborationSpaceOwner,
                    expected: CollaborationSpaceRecoveryProgress?,
                    sweep: CollaborationSpaceRecoverySweep?,
                ): AppResult<CollaborationSpaceRecoveryProgress?> {
                    failure?.let {
                        return AppResult.Failed(it)
                    }
                    if (expected != stored) return AppResult.Success(null)
                    val saved = CollaborationSpaceRecoveryProgress(id(1000 + ++writes), sweep)
                    stored = saved
                    afterWrite()
                    return AppResult.Success(saved)
                }
            }
        val server = IdentityServer(owner.parent.audience, "Fictional")
        val read = ReadCollaborationSpaceRecoveryProgress(repository, setup.sessions, server)
        val update = UpdateCollaborationSpaceRecoveryProgress(repository, setup.sessions, server)

        suspend fun snapshot() =
            assertIs<AppResult.Success<CollaborationSpaceRecoverySnapshot>>(read(owner)).value

        suspend fun change(change: CollaborationSpaceRecoveryChange) =
            assertIs<AppResult.Success<CollaborationSpaceRecoveryUpdate>>(
                    update(snapshot(), change)
                )
                .value

        suspend fun begin() = change(CollaborationSpaceRecoveryChange.Begin(wake))

        suspend fun complete() = change(CollaborationSpaceRecoveryChange.Complete(false))

        fun page(index: Int, end: Boolean = false): CollaborationSpaceRoomBindingPage {
            val time = Instant.parse("2026-11-07T00:00:00Z")
            val binding =
                CollaborationSpaceRoomBinding(
                    CollaborationSpaceConversationMetadata(
                        owner.spaceId,
                        id(100 + index),
                        id(1),
                        id(20),
                        id(1),
                        time,
                        0,
                    ),
                    CollaborationSpacePublishedRoom(
                        "!room$index:matrix.example.test",
                        "matrix.example.test",
                        CollaborationSpaceRoomOrigin.SPACE,
                        time,
                        null,
                    ),
                )
            return CollaborationSpaceRoomBindingPage(
                boundary,
                listOf(binding),
                if (end) null else CollaborationSpaceRoomBindingCursor(boundary, after(index)),
                false,
            )
        }
    }

    companion object {
        private fun id(n: Int) = "b0000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

        private fun after(n: Int) = "b-" + n.toString(16).padStart(64, '0')
    }
}
