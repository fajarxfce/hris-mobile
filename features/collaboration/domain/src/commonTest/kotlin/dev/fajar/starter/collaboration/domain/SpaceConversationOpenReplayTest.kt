package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SpaceConversationOpenReplayTest {
    @Test
    fun originalIsMarkedBeforeHttpAndCanonicalMetadataPrecedesArchive() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.replay()(f.owner, f.command.id)
                )
                .value
        assertEquals(f.recorded(), result)
        assertNull(f.active)
        assertEquals(result, f.archived)
        assertEquals(f.conversation().localMetadata(), f.local)
        assertEquals(listOf(f.owner to f.command.id), f.opens)
        assertEquals(listOf(f.owner to f.receipt.id), f.gets)
        assertEquals(1, f.metadataWrites)
        assertEquals(0, f.base.admits)
        assertEquals(AppResult.Success(result), f.replay()(f.owner, f.command.id))
        assertEquals(1, f.opens.size)
        assertEquals(1, f.gets.size)
        assertEquals(2, f.updates.size)
    }

    @Test
    fun recordedLocalFactsArchiveOfflineDespiteNativeAgeCompanyChangeAndDirectoryDenial() =
        runTest {
            val f = SpaceConversationOpenReplayFixture()
            f.active = f.recorded()
            f.local = f.conversation().localMetadata()
            f.native.setup.now += 365.days
            f.native.setup.workspace = null
            f.native.setup.session =
                f.native.session.copy(binding = f.native.session.binding.copy(serverId = f.peer))
            f.base.deny()
            assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
            assertTrue(f.opens.isEmpty() && f.gets.isEmpty() && f.updates.isEmpty())
            assertEquals(0, f.base.admits)
            assertEquals(
                f.base.denied,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                    .value
                    .denial,
            )
        }

    @Test
    fun currentNativeParentReplaysTheOriginalStableOperationWithoutChangingProvenance() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.active =
            assertNotNull(f.active)
                .copy(
                    revision = 1,
                    attempts = 1,
                    outcome = CollaborationSpaceConversationOutcome.Unconfirmed(),
                )
        val renewed = f.owner.copy(parent = f.owner.parent.copy(nativeSessionId = f.peer))
        f.native.setup.session =
            f.native.session.copy(binding = f.native.session.binding.copy(serverId = f.peer))
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.replay()(renewed, f.command.id)
                )
                .value
        assertEquals(f.command, result.command)
        assertEquals(2, result.attempts)
        assertEquals(listOf(renewed to f.command.id), f.opens)
        assertEquals(listOf(renewed to f.receipt.id), f.gets)
    }

    @Test
    fun observedPositiveResponseIsRetainedWhenCallerCancellationArrivesWithIt() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onOpen = {
            currentCoroutineContext().cancel()
            AppResult.Success(f.receipt)
        }
        val work = async { f.replay()(f.owner, f.command.id) }
        assertFailsWith<CancellationException> { work.await() }
        assertEquals(f.recorded(), f.active)
        assertTrue(f.gets.isEmpty())
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        assertEquals(1, f.opens.size)
    }

    @Test
    fun peerDenialNeverClosesTheDirectoryAndSurvivesAFailedRetry() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        val peerDenied =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.onOpen = { AppResult.Failed(peerDenied) }
        assertEquals(AppResult.Failed(peerDenied), f.replay()(f.owner, f.command.id))
        assertEquals(0, f.base.restrictions)
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(peerDenied),
            f.active?.outcome,
        )
        f.onOpen = { AppResult.Failed(f.base.unavailable) }
        assertEquals(AppResult.Failed(peerDenied), f.replay()(f.owner, f.command.id))
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(peerDenied),
            f.active?.outcome,
        )
        assertEquals(0, f.base.restrictions)
        assertEquals(2, f.opens.size)
        assertEquals(0, f.archives)
    }

    @Test
    fun knownGlobalDenialIsDurablyMarkedAndCannotBeWeakenedByAPeerFailure() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.base.deny()
        val peerDenied =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.onOpen = {
            assertEquals(
                CollaborationSpaceConversationOutcome.Unconfirmed(f.base.denied),
                f.active?.outcome,
            )
            AppResult.Failed(peerDenied)
        }
        assertEquals(AppResult.Failed(f.base.denied), f.replay()(f.owner, f.command.id))
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(f.base.denied),
            f.active?.outcome,
        )
        assertEquals(0, f.base.restrictions)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun cancelledGlobalDenialStaysGatedWhenBothPersistenceAttemptsFail() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        f.onUpdate = { expected, outcome, attempts ->
            if (outcome == CollaborationSpaceConversationOutcome.Unconfirmed(f.base.denied))
                AppResult.Failed(f.base.storageFailure)
            else f.commitUpdate(expected, outcome, attempts)
        }
        f.onOpen = {
            currentCoroutineContext().cancel()
            AppResult.Failed(f.base.denied)
        }
        val work = async { f.replay()(f.owner, f.command.id) }
        assertFailsWith<CancellationException> { work.await() }
        assertEquals(1, f.base.restrictions)
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                .value
                .denial,
        )
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.active?.outcome)
        f.onUpdate = { expected, outcome, attempts -> f.commitUpdate(expected, outcome, attempts) }
        f.onOpen = { AppResult.Failed(f.base.unavailable) }
        assertEquals(AppResult.Failed(f.base.denied), f.replay()(f.owner, f.command.id))
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(f.base.denied),
            f.active?.outcome,
        )
        assertEquals(0, f.base.admits)
    }

    @Test
    fun parentRotationAfterHttpKeepsTheReceiptAndFetchesMetadataUnderTheNextParent() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        val renewed = f.owner.copy(parent = f.owner.parent.copy(nativeSessionId = f.peer))
        f.onOpen = {
            f.native.setup.session =
                f.native.session.copy(binding = f.native.session.binding.copy(serverId = f.peer))
            AppResult.Success(f.receipt)
        }
        assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id))
        assertEquals(f.recorded(), f.active)
        assertTrue(f.gets.isEmpty())
        assertEquals(AppResult.Success(f.recorded()), f.replay()(renewed, f.command.id))
        assertEquals(1, f.opens.size)
        assertEquals(listOf(renewed to f.receipt.id), f.gets)
        assertEquals(f.command, f.archived?.command)
    }

    @Test
    fun invalidReceiptsCannotBecomeRecordedOrStartCanonicalReads() = runTest {
        for (receipt in
            listOf(
                CollaborationSetupReceipt("invalid", 0),
                CollaborationSetupReceipt("00000000-0000-0000-0000-000000000000", 0),
                CollaborationSetupReceipt("82000000-0000-4000-8000-000000000001", 1),
            )) {
            val f = SpaceConversationOpenReplayFixture()
            f.onOpen = { AppResult.Success(receipt) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
            )
            assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.active?.outcome)
            assertTrue(f.gets.isEmpty())
            assertEquals(0, f.archives)
        }
    }

    @Test
    fun inconsistentCachedCanonicalFactsCannotBeArchivedOrSilentlyReplaced() = runTest {
        for (phase in 0..2) {
            val f = SpaceConversationOpenReplayFixture()
            f.active = f.recorded()
            f.local =
                f.conversation().localMetadata().let {
                    when (phase) {
                        0 -> it.copy(id = f.command.id)
                        1 -> it.copy(secondAccountId = "91000000-0000-4000-8000-000000000001")
                        else -> it.copy(version = 1)
                    }
                }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
            )
            assertTrue(f.opens.isEmpty() && f.gets.isEmpty())
            assertEquals(0, f.archives)
            assertEquals(f.recorded(), f.active)
        }
    }

    @Test
    fun remoteMetadataMustMatchTheExactReceiptOwnerAndParticipantPair() = runTest {
        for (phase in 0..3) {
            val f = SpaceConversationOpenReplayFixture()
            f.active = f.recorded()
            f.onGet = { owner, id ->
                AppResult.Success(
                    f.conversation(owner, id).let {
                        when (phase) {
                            0 -> it.copy(id = f.command.id)
                            1 -> it.copy(owner = owner.copy(spaceId = f.peer))
                            2 -> it.copy(secondAccountId = "91000000-0000-4000-8000-000000000001")
                            else -> it.copy(version = 1)
                        }
                    }
                )
            }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
            )
            assertEquals(0, f.metadataWrites)
            assertEquals(0, f.archives)
            assertEquals(f.recorded(), f.active)
        }
    }

    @Test
    fun lostDurableMarkNeverSendsUntilTheNextExplicitAttempt() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onUpdate = { expected, outcome, attempts ->
            f.commitUpdate(expected, outcome, attempts)
            AppResult.Failed(f.base.storageFailure)
        }
        assertEquals(AppResult.Failed(f.base.storageFailure), f.replay()(f.owner, f.command.id))
        assertTrue(f.opens.isEmpty())
        assertEquals(1, f.active?.attempts)
        f.onUpdate = { expected, outcome, attempts -> f.commitUpdate(expected, outcome, attempts) }
        assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
            f.replay()(f.owner, f.command.id)
        )
        assertEquals(2, f.archived?.attempts)
        assertEquals(listOf(f.owner to f.command.id), f.opens)
    }

    @Test
    fun lostRecordedOutcomeResponseIsReconciledWithoutAnotherOpen() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onUpdate = { expected, outcome, attempts ->
            val saved = f.commitUpdate(expected, outcome, attempts)
            if (outcome is CollaborationSpaceConversationOutcome.Recorded)
                AppResult.Failed(f.base.storageFailure)
            else saved
        }
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        assertEquals(1, f.opens.size)
        assertEquals(f.recorded(), f.archived)
    }

    @Test
    fun lostMetadataResponseUsesExactLocalEvidenceBeforeArchive() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onMetadataWrite = {
            f.local = it.localMetadata()
            AppResult.Failed(f.base.storageFailure)
        }
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        assertEquals(1, f.metadataWrites)
        assertEquals(f.recorded(), f.archived)
    }

    @Test
    fun interruptedArchiveIsRecoveredFromOwnedRecordedFactsWithoutHttp() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onArchive = {
            f.commitArchive(it)
            throw CancellationException("Fixture lost archive response")
        }
        assertFailsWith<CancellationException> { f.replay()(f.owner, f.command.id) }
        assertEquals(f.recorded(), f.archived)
        f.onArchive = { f.commitArchive(it) }
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        assertEquals(1, f.opens.size)
        assertEquals(1, f.gets.size)
    }

    @Test
    fun pendingCanonicalReadCancellationLeavesRecordedSetupAvailable() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.active = f.recorded()
        val entered = CompletableDeferred<Unit>()
        f.onGet = { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.replay()(f.owner, f.command.id) }
        entered.await()
        work.cancelAndJoin()
        assertEquals(f.recorded(), f.active)
        assertEquals(0, f.archives)
        f.onGet = { owner, id -> AppResult.Success(f.conversation(owner, id)) }
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        assertTrue(f.opens.isEmpty())
    }

    @Test
    fun monotonicNativeDeadlineAndClockRollbackStopTransportAfterDurableMark() = runTest {
        for (phase in 0..2) {
            val f = SpaceConversationOpenReplayFixture()
            if (phase == 1)
                f.native.setup.session =
                    f.native.session.copy(
                        binding =
                            f.native.session.binding.copy(
                                expiresAtEpochMillis =
                                    (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                            )
                    )
            f.onUpdate = { expected, outcome, attempts ->
                val result = f.commitUpdate(expected, outcome, attempts)
                when (phase) {
                    0 -> f.native.time += 16.seconds
                    1 -> f.native.time += 3.seconds
                    2 -> f.native.setup.now -= 1.seconds
                }
                result
            }
            assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id))
            assertTrue(f.opens.isEmpty())
            assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.active?.outcome)
        }
    }

    @Test
    fun pendingTransportTimesOutWithoutReplacingOriginalUncertainty() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onOpen = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
        )
        assertEquals(f.command, f.active?.command)
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.active?.outcome)
        assertEquals(1, f.opens.size)
        assertEquals(0, f.archives)
    }

    @Test
    fun concurrentAttemptsCannotSendOverAWinningCanonicalArchive() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        f.onUpdate = { expected, outcome, attempts ->
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
            f.commitUpdate(expected, outcome, attempts)
        }
        val stale = async { f.replay()(f.owner, f.command.id) }
        entered.await()
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        release.complete(Unit)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(stale.await()).failure.kind)
        assertEquals(1, f.opens.size)
        assertEquals(f.recorded(), f.archived)
    }

    @Test
    fun localOwnerChangesAtEveryMutationBoundaryCannotReturnOldAccountSuccess() = runTest {
        for (phase in 0..7) {
            val f = SpaceConversationOpenReplayFixture()
            var reads = 0
            fun logout() {
                f.native.setup.session = null
            }
            when (phase) {
                0 -> f.beforeFind = { logout() }
                1 -> f.onUpdate = { expected, outcome, attempts ->
                        val result = f.commitUpdate(expected, outcome, attempts)
                        logout()
                        result
                    }
                2 -> f.onOpen = {
                        logout()
                        AppResult.Success(f.receipt)
                    }
                3 -> f.onGet = { owner, id ->
                        logout()
                        AppResult.Success(f.conversation(owner, id))
                    }
                4 -> f.onMetadataWrite = {
                        f.local = it.localMetadata()
                        logout()
                        AppResult.Success(true)
                    }
                5 -> f.onArchive = {
                        val result = f.commitArchive(it)
                        logout()
                        result
                    }
                6 -> f.beforeMetadataRead = { logout() }
                7 -> f.beforeFind = { if (++reads == 2) logout() }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
            )
        }
    }

    @Test
    fun changedOrMissingDurableMarkCannotDispatchAnObsoleteAttempt() = runTest {
        for (change in 0..4) {
            val f = SpaceConversationOpenReplayFixture()
            var reads = 0
            f.beforeFind = {
                if (++reads == 2) {
                    val marked = assertNotNull(f.active)
                    f.active =
                        when (change) {
                            0 -> marked.copy(revision = marked.revision + 1)
                            1 ->
                                marked.copy(
                                    command =
                                        f.command.copy(createdAt = f.command.createdAt + 1.seconds)
                                )
                            2 -> f.recorded()
                            3 ->
                                marked.copy(
                                    outcome =
                                        CollaborationSpaceConversationOutcome.Unconfirmed(
                                            f.base.denied
                                        )
                                )
                            else -> null
                        }
                }
            }
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
            )
            assertTrue(f.opens.isEmpty())
            assertTrue(f.gets.isEmpty())
            assertEquals(0, f.archives)
        }
    }

    @Test
    fun metadataDenialNeverDowngradesRecordedReceiptOrReopensGlobalAdmission() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.active = f.recorded()
        val peerDenied =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.onGet = { _, _ -> AppResult.Failed(peerDenied) }
        assertEquals(AppResult.Failed(peerDenied), f.replay()(f.owner, f.command.id))
        assertEquals(f.recorded(), f.active)
        assertEquals(0, f.base.restrictions)
        f.onGet = { _, _ -> AppResult.Failed(f.base.denied) }
        assertEquals(AppResult.Failed(f.base.denied), f.replay()(f.owner, f.command.id))
        assertEquals(f.recorded(), f.active)
        assertEquals(1, f.base.restrictions)
        f.onGet = { owner, id -> AppResult.Success(f.conversation(owner, id)) }
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        assertEquals(0, f.base.admits)
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                .value
                .denial,
        )
    }

    @Test
    fun failedCanonicalPublicationKeepsRecordedSetupUntilExplicitRecovery() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onMetadataWrite = { AppResult.Success(false) }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
        )
        assertEquals(f.recorded(), f.active)
        assertEquals(0, f.archives)
        f.onMetadataWrite = {
            f.local = it.localMetadata()
            AppResult.Success(true)
        }
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.owner, f.command.id))
        assertEquals(1, f.opens.size)
    }

    @Test
    fun responseCaptureIsBoundedAndFailedCaptureRetainsTheOriginalOperation() = runTest {
        val f = SpaceConversationOpenReplayFixture()
        f.onUpdate = { expected, outcome, attempts ->
            if (outcome is CollaborationSpaceConversationOutcome.Recorded) awaitCancellation()
            else f.commitUpdate(expected, outcome, attempts)
        }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id)).failure.kind,
        )
        assertEquals(f.command, f.active?.command)
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.active?.outcome)
        assertEquals(1, f.opens.size)
        assertTrue(f.gets.isEmpty())
        assertEquals(0, f.archives)
    }

    @Test
    fun roomCommandsAttemptExhaustionAndInvalidNativeParentsCannotStartOpenTransport() = runTest {
        for (phase in 0..3) {
            val f = SpaceConversationOpenReplayFixture()
            var owner = f.owner
            when (phase) {
                0 ->
                    f.active =
                        assertNotNull(f.active)
                            .copy(
                                command =
                                    CollaborationSpaceConversationCommand.Room(
                                        CollaborationSpaceRoomRequestIntent(
                                            f.command.id,
                                            f.command.binding,
                                            f.receipt.id,
                                            CollaborationSpaceRoomRequest(f.peer, 0, null),
                                        ),
                                        f.peer,
                                        f.command.createdAt,
                                    )
                            )
                1 ->
                    f.active =
                        assertNotNull(f.active)
                            .copy(
                                revision = 2000,
                                attempts = 1000,
                                outcome = CollaborationSpaceConversationOutcome.Unconfirmed(),
                            )
                2 ->
                    f.active =
                        assertNotNull(f.active)
                            .copy(
                                revision = 9_007_199_254_740_990L,
                                attempts = 1,
                                outcome = CollaborationSpaceConversationOutcome.Unconfirmed(),
                            )
                3 -> owner = owner.copy(parent = owner.parent.copy(nativeSessionId = f.peer))
            }
            val original = f.active
            assertIs<AppResult.Failed>(f.replay()(owner, f.command.id))
            assertEquals(original, f.active)
            assertTrue(f.opens.isEmpty() && f.gets.isEmpty() && f.updates.isEmpty())
            assertEquals(0, f.archives)
        }
    }
}
