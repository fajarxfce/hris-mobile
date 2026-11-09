package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SpaceRoomPreparationTest {
    @Test
    fun pendingUnconfirmedAndRecordedRecoverOfflineWithoutReplacingNativeIntent() = runTest {
        for (state in 0..2) {
            val f = SpaceRoomPreparationFixture()
            val original =
                when (state) {
                    0 -> f.record()
                    1 ->
                        f.resolution.original.copy(
                            outcome =
                                CollaborationSpaceConversationOutcome.Unconfirmed(f.base.denied)
                        )
                    else ->
                        f.resolution.resolved(
                            CollaborationSpaceConversationOutcome.Recorded(f.resolution.receipt)
                        )
                }
            f.active = listOf(original)
            f.native.setup.now += 365.days
            f.native.setup.workspace = null
            f.native.setup.session =
                f.native.session.copy(binding = f.native.session.binding.copy(serverId = f.peer))
            f.base.deny()
            assertEquals(AppResult.Success(original), f.prepare()(f.conversation))
            assertEquals(0, f.roomReads)
            assertEquals(0, f.deviceReads)
            assertTrue(f.inserted.isEmpty() && f.replacements.isEmpty())
        }
    }

    @Test
    fun newRequestUsesCurrentDeviceMetadataAndRepeatedPreparationReturnsOriginalLocally() =
        runTest {
            val f = SpaceRoomPreparationFixture()
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.prepare()(f.conversation)
                    )
                    .value
            val request = assertIs<CollaborationSpaceConversationCommand.Room>(result.command)
            assertEquals(
                CollaborationSpaceRoomRequest(f.device.id, f.device.version),
                request.intent.request,
            )
            assertEquals(f.owner.parent.nativeSessionId, request.binding.nativeSessionId)
            assertEquals(f.peer, request.peerAccountId)
            assertNull(request.predecessorId)
            assertEquals(CollaborationSpaceConversationOutcome.Pending, result.outcome)
            f.native.setup.now += 365.days
            assertEquals(AppResult.Success(result), f.prepare()(f.conversation))
            assertEquals(listOf(request), f.inserted)
            assertEquals(1, f.roomReads)
            assertEquals(1, f.deviceReads)
        }

    @Test
    fun existingFailedOrPublishedRoomDoesNotImplicitlyRequestAProvisioningRetry() = runTest {
        for (published in listOf(false, true)) {
            val f = SpaceRoomPreparationFixture()
            f.view =
                f.view.copy(
                    provisioning =
                        f.terminal(
                            if (published) CollaborationProvisioningState.SUCCEEDED
                            else CollaborationProvisioningState.FAILED
                        )
                )
            if (published)
                f.view =
                    f.view.copy(
                        room =
                            CollaborationSpacePublishedRoom(
                                "!fixture:example.test",
                                "example.test",
                                CollaborationSpaceRoomOrigin.LEGACY,
                                f.conversation.createdAt,
                                CollaborationSpaceOriginalRoom(
                                    CollaborationOriginalConversationPosition(
                                        "60000000-0000-4000-8000-000000000001",
                                        "60000000-0000-4000-8000-000000000002",
                                    ),
                                    "!fixture:example.test",
                                    f.conversation.createdAt,
                                ),
                            )
                    )
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                f.prepare()(f.conversation)
            )
            assertNull(f.inserted.single().intent.request.expectedAttempt)
            assertEquals(CollaborationSpaceConversationOutcome.Pending, f.active.single().outcome)
        }
    }

    @Test
    fun explicitRetryRetainsExactParticipantAndRevisionForEitherTerminalState() = runTest {
        for (state in
            listOf(
                CollaborationProvisioningState.FAILED,
                CollaborationProvisioningState.CANCELLED,
            )) {
            for (actorIsPeer in listOf(false, true)) {
                val f = SpaceRoomPreparationFixture()
                val attempt =
                    f.terminal(
                        state,
                        if (actorIsPeer) f.peer else f.access.accountId,
                        if (actorIsPeer) 9 else 1,
                    )
                f.view = f.view.copy(provisioning = attempt)
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.prepare()(f.conversation, attempt.attempt)
                )
                assertEquals(attempt.attempt, f.inserted.single().intent.request.expectedAttempt)
            }
        }
    }

    @Test
    fun wrongRetryAccountRevisionOrNonterminalSnapshotCannotPrepare() = runTest {
        for (phase in 0..3) {
            val f = SpaceRoomPreparationFixture()
            val expected = f.terminal().attempt
            f.view =
                f.view.copy(
                    provisioning =
                        when (phase) {
                            0 -> f.terminal(account = f.access.accountId)
                            1 -> f.terminal(revision = 8)
                            2 -> null
                            else ->
                                CollaborationSpaceRoomStatus(
                                    CollaborationSpaceRoomAttempt(f.peer, 0),
                                    CollaborationProvisioningState.QUEUED,
                                    0,
                                    f.native.setup.now,
                                    f.native.setup.now,
                                    f.native.setup.now + 60.seconds,
                                    null,
                                    null,
                                    null,
                                )
                        }
                )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.prepare()(f.conversation, expected)).failure.kind,
            )
            assertTrue(f.inserted.isEmpty())
            assertEquals(0, f.deviceReads)
        }
    }

    @Test
    fun absentRevokedContradictoryOrForeignDeviceNeverReenrollsOrPrepares() = runTest {
        for (phase in 0..5) {
            val f = SpaceRoomPreparationFixture()
            val device =
                when (phase) {
                    0 -> null
                    1 -> f.device.copy(state = CollaborationDeviceState.REVOKED)
                    2 -> f.device.copy(state = CollaborationDeviceState.PROVISIONING)
                    3 -> f.device.copy(platform = CollaborationNativePlatform.IOS)
                    4 -> f.device.copy(version = 1_000_000)
                    else -> f.device
                }
            f.onDevice = {
                AppResult.Success(
                    CollaborationSpaceDeviceObservation(
                        if (phase == 5) f.owner.copy(spaceId = f.peer) else f.owner,
                        device,
                    )
                )
            }
            assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            assertTrue(f.inserted.isEmpty() && f.replacements.isEmpty())
        }
    }

    @Test
    fun contradictoryOrForeignCanonicalRoomCannotReachDeviceRead() = runTest {
        for (phase in 0..3) {
            val f = SpaceRoomPreparationFixture()
            f.view =
                when (phase) {
                    0 -> f.view.copy(conversation = f.conversation.copy(id = f.peer))
                    1 ->
                        f.view.copy(
                            conversation = f.conversation.copy(createdBy = f.access.accountId)
                        )
                    2 ->
                        f.view.copy(
                            room =
                                CollaborationSpacePublishedRoom(
                                    "!fixture:example.test",
                                    "example.test",
                                    CollaborationSpaceRoomOrigin.SPACE,
                                    f.conversation.createdAt,
                                )
                        )
                    else ->
                        f.view.copy(
                            provisioning =
                                f.terminal()
                                    .copy(attempt = CollaborationSpaceRoomAttempt(f.peer, -1))
                        )
                }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.kind,
            )
            assertEquals(0, f.deviceReads)
            assertTrue(f.inserted.isEmpty())
        }
    }

    @Test
    fun capturedInventoryIsDetachedAndInvalidOrArchivedActiveFactsAreRejected() = runTest {
        val f = SpaceRoomPreparationFixture()
        val original = f.record()
        val mutable = mutableListOf(original)
        f.active = mutable
        var reads = 0
        f.local.beforeSession = { if (++reads == 2) mutable.clear() }
        assertEquals(AppResult.Success(original), f.prepare()(f.conversation))
        assertEquals(0, f.roomReads)
        for (phase in 0..4) {
            val bad = SpaceRoomPreparationFixture()
            bad.active =
                when (phase) {
                    0 -> List(129) { bad.record() }
                    1 -> listOf(bad.record(), bad.record())
                    2 -> listOf(bad.record(bad.original.copy(peerAccountId = bad.owner.spaceId)))
                    3 -> listOf(bad.fenced().copy(successorId = bad.peer))
                    else -> listOf(bad.record().copy(revision = 1))
                }
            assertIs<AppResult.Failed>(bad.prepare()(bad.conversation))
            assertEquals(0, bad.roomReads)
        }
    }

    @Test
    fun retainedDirectoryDenialBlocksNewAndReplacementPreparationBeforeHttp() = runTest {
        for (replacing in listOf(false, true)) {
            val f = SpaceRoomPreparationFixture()
            if (replacing) f.active = listOf(f.fenced())
            f.base.deny()
            assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
            assertEquals(0, f.roomReads)
            assertTrue(f.inserted.isEmpty() && f.replacements.isEmpty())
        }
    }

    @Test
    fun globalDenialSurvivesFailedOrPendingRestrictionAndCallerCancellation() = runTest {
        for (phase in 0..2) {
            val f = SpaceRoomPreparationFixture()
            f.base.afterRestrict = {
                if (phase == 1) awaitCancellation()
                AppResult.Failed(f.base.storageFailure)
            }
            f.onRoom = {
                if (phase == 2) currentCoroutineContext().cancel()
                AppResult.Failed(f.base.denied)
            }
            if (phase == 2)
                supervisorScope {
                    val job = async { f.prepare()(f.conversation) }
                    assertFailsWith<CancellationException> { job.await() }
                }
            else assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
            assertEquals(
                f.base.denied,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(f.base.gate.value)
                    .value
                    .denial,
            )
            assertEquals(1, f.base.restrictions)
            assertTrue(f.inserted.isEmpty())
        }
    }

    @Test
    fun peerRejectionDoesNotCloseOtherContactsAndDoesNotTriggerAutomaticRetry() = runTest {
        val f = SpaceRoomPreparationFixture()
        val peer =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.onRoom = { AppResult.Failed(peer) }
        assertEquals(AppResult.Failed(peer), f.prepare()(f.conversation))
        assertEquals(0, f.base.restrictions)
        assertEquals(1, f.roomReads)
        assertTrue(f.inserted.isEmpty())
        f.onRoom = { AppResult.Success(f.view) }
        assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
            f.prepare()(f.conversation)
        )
    }

    @Test
    fun changedDirectoryAdmissionAfterDeviceReadCannotPrepare() = runTest {
        val f = SpaceRoomPreparationFixture()
        f.onDevice = {
            f.base.deny()
            AppResult.Success(CollaborationSpaceDeviceObservation(f.owner, f.device))
        }
        assertEquals(AppResult.Failed(f.base.denied), f.prepare()(f.conversation))
        assertTrue(f.inserted.isEmpty())
        assertEquals(0, f.base.admits)
    }

    @Test
    fun wallRollbackMonotonicExpiryAndOriginalHardDeadlineRejectLateMetadata() = runTest {
        for (phase in 0..3) {
            val f = SpaceRoomPreparationFixture()
            if (phase == 3)
                f.native.setup.session =
                    f.native.session.copy(
                        binding =
                            f.native.session.binding.copy(
                                expiresAtEpochMillis =
                                    (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                            )
                    )
            f.onRoom = {
                when (phase) {
                    0 -> f.native.setup.now -= 1.seconds
                    1 -> f.native.time += 15.seconds
                    2 -> f.native.setup.now += 16.seconds
                    else -> {
                        f.native.setup.now += 3.seconds
                        f.native.setup.session = f.native.session
                    }
                }
                AppResult.Success(f.view)
            }
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.code,
            )
            assertTrue(f.inserted.isEmpty())
        }
    }

    @Test
    fun everySuspendedReadOrWriteRejectsResultsAfterLocalOwnershipChanges() = runTest {
        for (phase in 0..9) {
            val f = SpaceRoomPreparationFixture()
            val logout: suspend () -> Unit = { f.native.setup.session = null }
            when (phase) {
                0 -> f.beforeObserve = logout
                1 -> f.base.beforeAdmission = logout
                2 -> f.onRoom = {
                        logout()
                        AppResult.Success(f.view)
                    }
                3 -> f.onDevice = {
                        logout()
                        AppResult.Success(CollaborationSpaceDeviceObservation(f.owner, f.device))
                    }
                4 -> f.onInsert = {
                        f.active = listOf(f.record(it))
                        logout()
                        AppResult.Success(CollaborationSetupInsertion.STORED)
                    }
                5 -> f.beforeFind = { logout() }
                6 -> {
                    f.active = listOf(f.fenced())
                    f.onReplace = { previous, next -> f.commit(previous, next).also { logout() } }
                }
                7,
                8 -> {
                    f.active = listOf(f.fenced())
                    f.beforeFind = { id -> if ((id == f.original.id) == (phase == 7)) logout() }
                }
                else -> {
                    var admissions = 0
                    f.base.beforeAdmission = { if (++admissions == 2) logout() }
                }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.kind,
            )
            assertTrue(f.inserted.size + f.replacements.size <= 1)
        }
    }

    @Test
    fun fencedOriginalHandsOffOnceUsingCurrentNativeAndDeviceWithoutChangingItsIntent() = runTest {
        val f = SpaceRoomPreparationFixture()
        val original =
            f.fenced().let {
                val command = assertIs<CollaborationSpaceConversationCommand.Room>(it.command)
                val old =
                    command.copy(
                        intent =
                            command.intent.copy(
                                binding = command.binding.copy(nativeSessionId = f.peer)
                            )
                    )
                it.copy(
                    command = old,
                    outcome =
                        CollaborationSpaceConversationOutcome.Fenced(
                            f.resolution.evidence.copy(original = old.intent)
                        ),
                )
            }
        f.active = listOf(original)
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.prepare()(f.conversation)
                )
                .value
        val replacement = assertIs<CollaborationSpaceConversationCommand.Room>(result.command)
        assertEquals(original.command.id, replacement.predecessorId)
        assertEquals(f.owner.parent.nativeSessionId, replacement.binding.nativeSessionId)
        assertEquals(f.device.version, replacement.intent.request.expectedDeviceVersion)
        assertEquals(original.copy(successorId = replacement.id), f.archives[original.command.id])
        assertEquals(listOf(original.command.id, replacement.id), f.findIds)
        assertEquals(AppResult.Success(result), f.prepare()(f.conversation))
        assertEquals(1, f.replacements.size)
        assertTrue(f.inserted.isEmpty())
    }

    @Test
    fun lostInsertResponseAndCompetingResourceRecoverTheExactRetainedOriginal() = runTest {
        for (competing in listOf(false, true)) {
            val f = SpaceRoomPreparationFixture()
            f.onInsert = { next ->
                f.active = listOf(if (competing) f.resolution.original else f.record(next))
                if (competing) AppResult.Success(CollaborationSetupInsertion.OCCUPIED)
                else AppResult.Failed(f.base.storageFailure)
            }
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.prepare()(f.conversation)
                    )
                    .value
            assertEquals(f.active.single(), result)
            assertEquals(1, f.inserted.size)
            if (competing) assertEquals(f.resolution.original, result)
        }
    }

    @Test
    fun lostOrCompetingHandoffReturnsOnlyTheKnownOriginalsAuthenticatedSuccessor() = runTest {
        for (competing in listOf(false, true)) {
            val f = SpaceRoomPreparationFixture()
            val original = f.fenced()
            f.active = listOf(original)
            f.onReplace = { previous, next ->
                val actual =
                    if (competing) next.copy(intent = next.intent.copy(operationId = f.peer))
                    else next
                f.commit(previous, actual)
                if (competing) AppResult.Success(false) else AppResult.Failed(f.base.storageFailure)
            }
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.prepare()(f.conversation)
                    )
                    .value
            assertEquals(f.active.single(), result)
            assertEquals(result.command.id, f.archives[original.command.id]?.successorId)
            assertEquals(listOf(original.command.id, result.command.id), f.findIds)
            assertEquals(1, f.replacements.size)
            assertEquals(1, f.observations)
        }
    }

    @Test
    fun malformedMissingOrAlreadyChainedSuccessorCannotBeReturnedOrWalked() = runTest {
        for (phase in 0..4) {
            val f = SpaceRoomPreparationFixture()
            f.active = listOf(f.fenced())
            f.onReplace = { previous, next ->
                f.commit(previous, next)
                when (phase) {
                    0 ->
                        f.archives[previous.command.id] =
                            previous.copy(successorId = previous.command.id)
                    1 -> f.active = emptyList()
                    2 -> f.active = listOf(f.record(next.copy(predecessorId = f.peer)))
                    3 -> f.active = listOf(f.record(next.copy(peerAccountId = f.owner.spaceId)))
                    else ->
                        f.active =
                            listOf(
                                f.record(next)
                                    .copy(
                                        revision = 2,
                                        attempts = 1,
                                        outcome =
                                            CollaborationSpaceConversationOutcome.Fenced(
                                                f.resolution.evidence.copy(original = next.intent)
                                            ),
                                        successorId = f.peer,
                                    )
                            )
                }
                AppResult.Success(true)
            }
            assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            assertEquals(2, f.findIds.size)
            assertEquals(1, f.replacements.size)
        }
    }

    @Test
    fun failedUncommittedHandoffKeepsOriginalAndNeverAllocatesAnotherAttempt() = runTest {
        for (missing in listOf(false, true)) {
            val f = SpaceRoomPreparationFixture()
            val original = f.fenced()
            f.active = listOf(original)
            f.onReplace = { _, _ ->
                if (missing) f.active = emptyList()
                AppResult.Failed(f.base.storageFailure)
            }
            val result = assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            assertEquals(
                if (missing) FailureKind.Conflict else FailureKind.Storage,
                result.failure.kind,
            )
            assertEquals(1, f.replacements.size)
            assertTrue(f.archives.isEmpty())
            if (!missing) assertEquals(listOf(original), f.active)
        }
    }

    @Test
    fun capacityScopeAndUnconfirmedStoredResultNeverImplySuccessfulPreparation() = runTest {
        for (outcome in
            listOf(
                CollaborationSetupInsertion.CAPACITY_EXCEEDED,
                CollaborationSetupInsertion.SCOPE_CHANGED,
                CollaborationSetupInsertion.STORED,
            )) {
            val f = SpaceRoomPreparationFixture()
            f.onInsert = { AppResult.Success(outcome) }
            assertIs<AppResult.Failed>(f.prepare()(f.conversation))
            assertEquals(1, f.inserted.size)
            assertTrue(f.active.isEmpty())
        }
    }

    @Test
    fun cancelledCommittedInsertionOrHandoffRecoversNextTimeWithoutNewIdOrHttp() = runTest {
        for (replacing in listOf(false, true)) {
            val f = SpaceRoomPreparationFixture()
            if (replacing) {
                f.active = listOf(f.fenced())
                f.onReplace = { previous, next ->
                    f.commit(previous, next)
                    throw CancellationException("Lost fixture handoff reply")
                }
            } else
                f.onInsert = { next ->
                    f.active = listOf(f.record(next))
                    throw CancellationException("Lost fixture insert reply")
                }
            assertFailsWith<CancellationException> { f.prepare()(f.conversation) }
            val result = f.active.single()
            f.native.setup.now += 365.days
            assertEquals(AppResult.Success(result), f.prepare()(f.conversation))
            assertEquals(1, f.roomReads)
            assertEquals(1, f.inserted.size + f.replacements.size)
        }
    }

    @Test
    fun committedWorkRemainsOwnedWhenNativeDeadlineExpiresDuringTheWrite() = runTest {
        for (replacing in listOf(false, true)) {
            val f = SpaceRoomPreparationFixture()
            if (replacing) {
                f.active = listOf(f.fenced())
                f.onReplace = { previous, next ->
                    f.commit(previous, next).also { f.native.setup.now += 365.days }
                }
            } else
                f.onInsert = { next ->
                    f.active = listOf(f.record(next))
                    f.native.setup.now += 365.days
                    AppResult.Success(CollaborationSetupInsertion.STORED)
                }
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                f.prepare()(f.conversation)
            )
            assertEquals(1, f.inserted.size + f.replacements.size)
        }
    }

    @Test
    fun pendingReadsTimeoutAndOwnerDisposalCancelsWithoutStartingBackgroundWork() = runTest {
        val f = SpaceRoomPreparationFixture()
        f.beforeObserve = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.prepare()(f.conversation)).failure.kind,
        )
        f.beforeObserve = {}
        val entered = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        f.onRoom = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                disposed.complete(Unit)
            }
        }
        val job = async { f.prepare()(f.conversation) }
        entered.await()
        job.cancelAndJoin()
        disposed.await()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(f.inserted.isEmpty())
        assertEquals(0, f.deviceReads)
    }
}
