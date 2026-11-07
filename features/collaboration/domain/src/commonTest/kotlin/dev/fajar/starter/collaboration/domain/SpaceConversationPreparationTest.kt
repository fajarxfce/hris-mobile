package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SpaceConversationPreparationTest {
    @Test
    fun canonicalLocalConversationAvoidsNewSetupAfterTokenExpiryAndCompanyChange() = runTest {
        val f = SpaceConversationPreparationFixture()
        val canonical = f.canonical()
        f.canonical = AppResult.Success(canonical)
        f.native.setup.now += 365.days
        f.native.setup.workspace = null
        f.native.setup.beforeWorkspace = { error("No company lookup") }
        f.base.deny()
        assertEquals(
            AppResult.Success(CollaborationSpaceOpenPreparation.Canonical(canonical.id)),
            f.prepare()(f.access, f.peer),
        )
        assertTrue(f.inserted.isEmpty())
        assertEquals(0, f.admissionReads)
        assertEquals(0, f.native.discoveryCalls)
        assertEquals(0, f.native.pageCalls)
    }

    @Test
    fun pendingUnconfirmedAndRecordedOriginalsSurviveParentRotationAndConfirmedDenial() = runTest {
        for (phase in 0..2) {
            val f = SpaceConversationPreparationFixture()
            val original =
                f.record().let {
                    when (phase) {
                        0 -> it
                        1 ->
                            it.copy(
                                revision = 1,
                                attempts = 1,
                                outcome =
                                    CollaborationSpaceConversationOutcome.Unconfirmed(f.base.denied),
                            )
                        else ->
                            it.copy(
                                revision = 2,
                                attempts = 1,
                                outcome =
                                    CollaborationSpaceConversationOutcome.Recorded(
                                        CollaborationSetupReceipt(f.canonical().id, 0)
                                    ),
                            )
                    }
                }
            f.active = AppResult.Success(listOf(original))
            f.native.setup.now += 365.days
            f.native.setup.session =
                f.native.session.copy(binding = f.native.session.binding.copy(serverId = f.peer))
            f.base.deny()
            assertEquals(
                AppResult.Success(CollaborationSpaceOpenPreparation.Original(original)),
                f.prepare()(f.access, f.peer),
            )
            assertTrue(f.inserted.isEmpty())
            assertEquals(0, f.metadataReads)
            assertEquals(0, f.admissionReads)
        }
    }

    @Test
    fun freshDiscoveryStoresOneOriginalAndRepeatedPreparationNeverReallocatesIt() = runTest {
        val f = SpaceConversationPreparationFixture()
        val discovery = f.discovery()
        val result =
            assertIs<AppResult.Success<CollaborationSpaceOpenPreparation>>(
                    f.prepare()(f.access, f.peer, discovery)
                )
                .value
        val first = assertIs<CollaborationSpaceOpenPreparation.Original>(result).record
        assertEquals(f.peer, first.command.peerAccountId)
        assertEquals(f.native.parent.nativeSessionId, first.command.binding.nativeSessionId)
        assertEquals(CollaborationSpaceConversationOutcome.Pending, first.outcome)
        f.native.setup.now += 365.days
        assertEquals(AppResult.Success(result), f.prepare()(f.access, f.peer))
        assertEquals(listOf(first.command), f.inserted)
        assertEquals(1, f.native.discoveryCalls)
    }

    @Test
    fun newIntentRequiresMatchingCurrentDiscoveryAndRetainsExistingAccountRestriction() = runTest {
        for (phase in 0..4) {
            val f = SpaceConversationPreparationFixture()
            val discovery = f.discovery()
            when (phase) {
                1 -> f.native.setup.now += 120.seconds
                2 ->
                    f.native.setup.session =
                        f.native.session.copy(
                            binding = f.native.session.binding.copy(serverId = f.peer)
                        )
                3 -> f.base.deny()
                4 -> f.base.beforeAdmission = { f.native.setup.now += 120.seconds }
            }
            val result =
                assertIs<AppResult.Failed>(
                    f.prepare()(f.access, f.peer, if (phase == 0 || phase == 3) null else discovery)
                )
            if (phase == 3) assertEquals(f.base.denied, result.failure)
            assertTrue(f.inserted.isEmpty())
        }
    }

    @Test
    fun lostInsertResponseIsRecoveredByTheExactKnownIdWithoutAnotherWrite() = runTest {
        val f = SpaceConversationPreparationFixture()
        f.onInsert = { command ->
            f.active = AppResult.Success(listOf(f.record(command)))
            AppResult.Failed(Failure(FailureKind.Storage, "Lost fixture response"))
        }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceOpenPreparation>>(
                    f.prepare()(f.access, f.peer, f.discovery())
                )
                .value
        assertEquals(
            f.inserted.single(),
            assertIs<CollaborationSpaceOpenPreparation.Original>(result).record.command,
        )
        assertEquals(AppResult.Success(result), f.prepare()(f.access, f.peer))
        assertEquals(1, f.inserted.size)
    }

    @Test
    fun competingResourceReturnsTheRetainedOriginalAndNeverReplacesItsBinding() = runTest {
        val f = SpaceConversationPreparationFixture()
        val winner =
            f.record(
                f.original(9).copy(binding = f.original().binding.copy(nativeSessionId = f.peer))
            )
        f.onInsert = {
            f.active = AppResult.Success(listOf(winner))
            AppResult.Success(CollaborationSetupInsertion.OCCUPIED)
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceOpenPreparation.Original(winner)),
            f.prepare()(f.access, f.peer, f.discovery()),
        )
        assertEquals(1, f.inserted.size)
        assertNotEquals(winner.command.id, f.inserted.single().id)
    }

    @Test
    fun confirmedLocalWriteRemainsAvailableWhenDiscoveryExpiresDuringInsertion() = runTest {
        val f = SpaceConversationPreparationFixture()
        f.onInsert = { command ->
            f.active = AppResult.Success(listOf(f.record(command)))
            f.native.setup.now += 365.days
            AppResult.Success(CollaborationSetupInsertion.STORED)
        }
        assertIs<AppResult.Success<CollaborationSpaceOpenPreparation>>(
            f.prepare()(f.access, f.peer, f.discovery())
        )
        assertEquals(1, f.inserted.size)
    }

    @Test
    fun everySuspendedRepositoryBoundaryRejectsResultsAfterTheLocalOwnerChanges() = runTest {
        for (phase in 0..4) {
            val f = SpaceConversationPreparationFixture()
            val discovery = f.discovery()
            val logout: suspend () -> Unit = { f.native.setup.session = null }
            when (phase) {
                0 -> f.beforeObserve = logout
                1 -> f.beforeMetadata = logout
                2 -> f.base.beforeAdmission = logout
                3 -> f.onInsert = { command ->
                        f.active = AppResult.Success(listOf(f.record(command)))
                        logout()
                        AppResult.Success(CollaborationSetupInsertion.STORED)
                    }
                4 -> f.beforeFind = logout
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.prepare()(f.access, f.peer, discovery)).failure.kind,
            )
            assertTrue(f.inserted.size <= 1)
        }
    }

    @Test
    fun capturedActiveListCannotBeSubstitutedWhileCheckingLocalOwnership() = runTest {
        val f = SpaceConversationPreparationFixture()
        val original = f.record()
        val mutable = mutableListOf(original)
        f.active = AppResult.Success(mutable)
        var reads = 0
        f.beforeSession = { if (++reads == 2) mutable[0] = f.record(f.original(2)) }
        assertEquals(
            AppResult.Success(CollaborationSpaceOpenPreparation.Original(original)),
            f.prepare()(f.access, f.peer),
        )
        assertTrue(f.inserted.isEmpty())
    }

    @Test
    fun invalidInventoryOrCanonicalPeerCannotStartAnotherSetup() = runTest {
        for (phase in 0..4) {
            val f = SpaceConversationPreparationFixture()
            when (phase) {
                0 -> f.active = AppResult.Success(List(129) { f.record(f.original(it + 1)) })
                1 -> f.active = AppResult.Success(listOf(f.record(), f.record()))
                2 -> f.active = AppResult.Success(listOf(f.record(), f.record(f.original(2))))
                3 ->
                    f.active =
                        AppResult.Success(
                            listOf(
                                f.record()
                                    .copy(
                                        revision = 2,
                                        attempts = 1,
                                        outcome =
                                            CollaborationSpaceConversationOutcome.Recorded(
                                                CollaborationSetupReceipt("invalid", 0)
                                            ),
                                    )
                            )
                        )
                4 ->
                    f.canonical =
                        AppResult.Success(
                            f.canonical()
                                .copy(secondAccountId = "71000000-0000-4000-8000-000000000001")
                        )
            }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.prepare()(f.access, f.peer)).failure.kind,
            )
            assertTrue(f.inserted.isEmpty())
        }
    }

    @Test
    fun capacityScopeAndMissingStoredResultFailWithoutAnAutomaticRetry() = runTest {
        for (outcome in
            listOf(
                CollaborationSetupInsertion.CAPACITY_EXCEEDED,
                CollaborationSetupInsertion.SCOPE_CHANGED,
                CollaborationSetupInsertion.STORED,
            )) {
            val f = SpaceConversationPreparationFixture()
            f.onInsert = { AppResult.Success(outcome) }
            val expected =
                when (outcome) {
                    CollaborationSetupInsertion.CAPACITY_EXCEEDED -> FailureKind.Storage
                    CollaborationSetupInsertion.SCOPE_CHANGED -> FailureKind.Unauthorized
                    else -> FailureKind.Conflict
                }
            assertEquals(
                expected,
                assertIs<AppResult.Failed>(f.prepare()(f.access, f.peer, f.discovery()))
                    .failure
                    .kind,
            )
            assertEquals(1, f.inserted.size)
        }
    }

    @Test
    fun cancelledCommittedInsertIsRecoveredOnTheNextExplicitPreparation() = runTest {
        val f = SpaceConversationPreparationFixture()
        f.onInsert = { command ->
            f.active = AppResult.Success(listOf(f.record(command)))
            throw CancellationException("Fixture response interrupted")
        }
        val discovery = f.discovery()
        assertFailsWith<CancellationException> { f.prepare()(f.access, f.peer, discovery) }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceOpenPreparation>>(
                    f.prepare()(f.access, f.peer)
                )
                .value
        assertEquals(
            f.inserted.single(),
            assertIs<CollaborationSpaceOpenPreparation.Original>(result).record.command,
        )
        assertEquals(1, f.inserted.size)
    }

    @Test
    fun pendingObservationHasAFiniteTimeoutAndOwnerDisposalPropagatesCancellation() = runTest {
        val f = SpaceConversationPreparationFixture()
        f.beforeObserve = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.prepare()(f.access, f.peer)).failure.kind,
        )
        val entered = CompletableDeferred<Unit>()
        f.beforeObserve = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.prepare()(f.access, f.peer) }
        entered.await()
        work.cancelAndJoin()
        assertFailsWith<CancellationException> { work.await() }
        assertTrue(f.inserted.isEmpty())
    }
}
