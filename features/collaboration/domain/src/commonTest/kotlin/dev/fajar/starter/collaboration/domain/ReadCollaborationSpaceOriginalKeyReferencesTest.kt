package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationSpaceOriginalKeyReferencesTest {
    private suspend fun pending(f: SpaceOriginalKeyReferenceReadFixture) =
        assertIs<CollaborationHistoryRecoveryRead.Pending>(
                assertIs<AppResult.Success<*>>(f.run()).value
            )
            .references

    private suspend fun failed(f: SpaceOriginalKeyReferenceReadFixture) =
        assertIs<AppResult.Failed>(f.run()).failure

    @Test
    fun freshPassUsesSixteenLocalReferencesWithoutPrimaryOrProviderWork() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.progress = assertNotNull(f.progress).copy(references = 48)
        f.events = List(16) { f.reference(it) }
        f.after = "cursor-15"
        f.provider.failures["demand"] = f.base.directory.storageFailure
        val page = pending(f)
        assertEquals(f.events, page.events)
        assertEquals(48L, page.progress?.recovery?.through)
        assertEquals(f.generation, page.progress?.recovery?.generation)
        assertEquals(listOf<Pair<String?, Int>>(null to 16), f.requests)
        assertEquals(1, f.writes)
        assertNull(f.base.snapshot)
        assertTrue(f.provider.requests.isEmpty())
        assertFalse("demand" in f.provider.calls)
        assertNotEquals(f.provider.traversal.deviceId, f.keyHistory.traversal.deviceId)
    }

    @Test
    fun absentProgressCreatesNoPassOrPendingRead() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.progress = null
        assertEquals(AppResult.Success(CollaborationHistoryRecoveryRead.Absent), f.run())
        assertEquals(0, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun zeroReferencesCreateAnAlreadyCompleteLocalPass() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.progress = assertNotNull(f.progress).copy(references = 0)
        val value =
            assertIs<CollaborationHistoryRecoveryRead.Complete>(
                assertIs<AppResult.Success<*>>(f.run()).value
            )
        assertEquals(0L, value.progress.recovery?.through)
        assertEquals(true, value.progress.recovery?.complete)
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun activeReplayKeepsTheOldBoundaryCursorAndUnresolvedEvidence() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val active =
            CollaborationHistoryRecoveryPosition(f.generation, 40, "cursor-15", unresolved = true)
        f.progress = assertNotNull(f.progress).copy(references = 80, recovery = active)
        f.events = List(16) { f.reference(it + 16) }
        f.after = "cursor-31"
        assertEquals(active, pending(f).progress?.recovery)
        assertEquals(listOf<Pair<String?, Int>>("cursor-15" to 16), f.requests)
        assertEquals(0, f.writes)
    }

    @Test
    fun requestedReplayDoesNotReplaceTheActivePass() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val active =
            CollaborationHistoryRecoveryPosition(
                f.base.id(981),
                2,
                requestedGeneration = f.generation,
            )
        f.progress = assertNotNull(f.progress).copy(recovery = active)
        assertEquals(active, pending(f).progress?.recovery)
        assertEquals(0, f.writes)
    }

    @Test
    fun laterRequestCoalescesWithoutRewindingTheOldCursor() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val active =
            CollaborationHistoryRecoveryPosition(f.base.id(981), 40, "cursor-15", unresolved = true)
        f.progress = assertNotNull(f.progress).copy(references = 80, recovery = active)
        f.events = List(16) { f.reference(it + 16) }
        f.after = "cursor-31"
        assertEquals(active.copy(requestedGeneration = f.generation), pending(f).progress?.recovery)
        assertEquals(listOf<Pair<String?, Int>>("cursor-15" to 16), f.requests)
        assertEquals(1, f.writes)
    }

    @Test
    fun completedCurrentGenerationStaysIdleDespiteNewPrimaryReferences() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val active =
            CollaborationHistoryRecoveryPosition(
                f.generation,
                2,
                complete = true,
                unresolved = true,
            )
        f.progress = assertNotNull(f.progress).copy(references = 80, recovery = active)
        val value =
            assertIs<CollaborationHistoryRecoveryRead.Complete>(
                assertIs<AppResult.Success<*>>(f.run()).value
            )
        assertEquals(active, value.progress.recovery)
        assertEquals(0, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun genuinelyLaterGenerationStartsAfterThePriorPassCompletes() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.progress =
            assertNotNull(f.progress)
                .copy(
                    references = 4,
                    recovery =
                        CollaborationHistoryRecoveryPosition(f.base.id(981), 2, complete = true),
                )
        f.events = List(4) { f.reference(it) }
        assertEquals(
            CollaborationHistoryRecoveryPosition(f.generation, 4),
            pending(f).progress?.recovery,
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun appendsDuringTheReadCannotExtendTheCapturedBoundary() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.hooks["pending"] = {
            f.progress = assertNotNull(f.progress).copy(revision = 2, references = 4)
            f.events = List(4) { f.reference(it) }
        }
        val page = pending(f)
        assertEquals(listOf(0L, 1L), page.events.map { it.sequence })
        assertEquals(2L, page.progress?.recovery?.through)
        assertNull(page.after)
    }

    @Test
    fun malformedInputCannotReachLocalIo() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val bad =
            listOf(
                f.keyHistory.copy(traversal = f.keyHistory.traversal.copy(deviceId = "bad")),
                f.keyHistory.copy(
                    traversal = f.keyHistory.traversal.copy(conversationId = f.base.id(999))
                ),
                f.keyHistory.copy(source = f.keyHistory.source.copy(storeId = "")),
            )
        for (history in bad) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read(f.base.runtime, history, f.generation)).failure.kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read(f.base.runtime, f.keyHistory, "bad")).failure.kind,
        )
        assertTrue(f.calls.isEmpty())
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun absentMetadataAndRegistrationDoNotCreateARecoveryPass() = runTest {
        for (missing in listOf("metadata", "registration")) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            if (missing == "metadata") f.base.retained = null else f.base.registration = null
            failed(f)
            assertEquals(0, f.writes)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun anotherOriginalCompanyCannotAuthorizeThisHistory() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val record = assertNotNull(f.base.registration)
        val legacy = assertNotNull(record.command.legacy)
        f.base.registration =
            record.copy(
                command =
                    record.command.copy(
                        legacy =
                            legacy.copy(binding = legacy.binding.copy(companyId = f.base.id(999)))
                    )
            )
        failed(f)
        assertEquals(0, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun capturedSourceMustStillBeInstalledBeforeAnyHistoryRead() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.base.installed = f.base.source.copy(matrixDeviceId = "OTHER")
        assertEquals(FailureKind.Unavailable, failed(f).kind)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun accountNativeSourceAndDenialChangesFenceEachHistoryOperation() = runTest {
        for (stage in listOf("progress", "replace", "pending")) for (change in
            listOf("account", "native", "source", "denial")) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            f.hooks[stage] = {
                when (change) {
                    "account" -> f.base.native.setup.session = null
                    "native" -> {
                        val session = assertNotNull(f.base.native.setup.session)
                        f.base.native.setup.session =
                            session.copy(binding = session.binding.copy(serverId = f.base.id(998)))
                    }
                    "source" -> f.base.installed = f.base.source.copy(matrixDeviceId = "OTHER")
                    else -> f.base.directory.deny()
                }
            }
            val failure = failed(f)
            if (change == "denial") assertEquals(f.base.directory.denied, failure)
            if (stage != "pending") assertTrue(f.requests.isEmpty())
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun metadataAndRegistrationChangesAfterLocalReadsRejectTheResult() = runTest {
        for (stage in listOf("progress", "pending")) for (change in
            listOf("metadata", "registration")) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            f.hooks[stage] = {
                if (change == "metadata") f.base.retained = null else f.base.registration = null
            }
            failed(f)
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun aLostCompareAndSwapDoesNotStartAHiddenRetry() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.commit = false
        f.response = AppResult.Success(false)
        assertEquals(FailureKind.Conflict, failed(f).kind)
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
        assertNull(f.progress?.recovery)
    }

    @Test
    fun lostWriteResponseIsConfirmedByTheExactPersistedPass() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.response = AppResult.Failed(f.base.directory.storageFailure)
        assertEquals(f.generation, pending(f).progress?.recovery?.generation)
        assertEquals(1, f.writes)
        assertEquals(2, f.progressReads)
    }

    @Test
    fun anUncommittedLostResponseKeepsItsOriginalFailure() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.commit = false
        f.response = AppResult.Failed(f.base.directory.storageFailure)
        assertEquals(f.base.directory.storageFailure, failed(f))
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun aSuccessfulReplyWithoutThePersistedPassIsRejected() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.commit = false
        assertEquals(FailureKind.Conflict, failed(f).kind)
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun aCompetingRecoveryPassCannotBeAcceptedAsThisRequest() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val other = CollaborationHistoryRecoveryPosition(f.base.id(997), 2)
        f.hooks["replaced"] = { f.progress = assertNotNull(f.progress).copy(recovery = other) }
        failed(f)
        assertEquals(other, f.progress?.recovery)
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun changedCheckpointFamilyAndRegressionsCannotConfirmAWrite() = runTest {
        for (change in listOf("generation", "revision", "count", "absence")) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            f.progress = assertNotNull(f.progress).copy(revision = 2)
            f.hooks["replaced"] = {
                val value = assertNotNull(f.progress)
                f.progress =
                    when (change) {
                        "generation" -> value.copy(generation = f.base.id(997))
                        "revision" -> value.copy(revision = 1)
                        "count" -> value.copy(references = 1)
                        else -> null
                    }
            }
            failed(f)
            assertTrue(f.requests.isEmpty())
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun classifiedReadFailuresRemainFailuresWithoutProviderFallback() = runTest {
        for (stage in listOf("progress", "pending")) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            f.failures[stage] = f.base.directory.storageFailure
            assertEquals(f.base.directory.storageFailure, failed(f))
            assertTrue(f.provider.requests.isEmpty())
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun anEmptyPendingFlowIsNotFabricatedEofEvidence() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.emptyFlow = true
        assertEquals(FailureKind.Conflict, failed(f).kind)
        assertEquals(1, f.requests.size)
        assertEquals(false, f.progress?.recovery?.complete)
    }

    @Test
    fun malformedPagesAreRejectedAndReturnedEventsAreCopied() = runTest {
        for (change in
            listOf(
                "over-limit",
                "duplicate",
                "resolved",
                "order",
                "cursor",
                "checkpoint",
                "provider",
            )) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            f.hooks["pending"] = {
                val progress = assertNotNull(f.progress)
                val page = CollaborationHistoryReferences(progress, f.events, null)
                f.page =
                    when (change) {
                        "over-limit" -> page.copy(events = List(17) { f.reference(it) })
                        "duplicate" -> page.copy(events = listOf(f.reference(0), f.reference(0)))
                        "resolved" ->
                            page.copy(events = listOf(f.reference(0).copy(resolved = true)))
                        "order" -> page.copy(events = f.events.reversed())
                        "cursor" -> page.copy(after = "")
                        "checkpoint" -> page.copy(progress = null)
                        else ->
                            page.copy(
                                progress =
                                    progress.copy(
                                        provider =
                                            CollaborationHistoryProviderIdentity(
                                                f.base.position.companyId,
                                                f.base.setup.sdkDevice,
                                            )
                                    )
                            )
                    }
            }
            failed(f)
        }
        val f = SpaceOriginalKeyReferenceReadFixture()
        val mutable = f.events.toMutableList()
        f.events = mutable
        val returned = pending(f)
        mutable.clear()
        assertEquals(2, returned.events.size)
    }

    @Test
    fun aLaterRequestDuringThePageReadPreservesTheActiveTurn() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.hooks["pending"] = {
            val value = assertNotNull(f.progress)
            f.progress =
                value.copy(
                    recovery =
                        assertNotNull(value.recovery).copy(requestedGeneration = f.base.id(997))
                )
        }
        val active = assertNotNull(pending(f).progress?.recovery)
        assertEquals(f.generation, active.generation)
        assertEquals(f.base.id(997), active.requestedGeneration)
        assertEquals(1, f.writes)
    }

    @Test
    fun anAdvancedOrReplacedTurnAndDisappearingKnownRequestLoseTheRead() = runTest {
        for (change in listOf("cursor", "generation", "complete", "requested")) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            if (change == "requested")
                f.progress =
                    assertNotNull(f.progress)
                        .copy(
                            recovery =
                                CollaborationHistoryRecoveryPosition(
                                    f.base.id(996),
                                    2,
                                    requestedGeneration = f.generation,
                                )
                        )
            f.hooks["pending"] = {
                val value = assertNotNull(f.progress)
                val active = assertNotNull(value.recovery)
                val changed =
                    when (change) {
                        "cursor" -> active.copy(after = "another-cursor")
                        "generation" -> active.copy(generation = f.base.id(997))
                        "complete" -> active.copy(complete = true)
                        else -> active.copy(requestedGeneration = null)
                    }
                f.progress = value.copy(recovery = changed)
            }
            assertEquals(FailureKind.Conflict, failed(f).kind)
        }
    }

    @Test
    fun pendingLocalOperationsHaveOneBoundedLifetime() = runTest {
        for (stage in listOf("progress", "replace", "pending")) {
            val f = SpaceOriginalKeyReferenceReadFixture()
            f.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, failed(f).kind)
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun cancellationAfterCommitDoesNotRestartThePassOnReopen() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        val committed = CompletableDeferred<Unit>()
        f.hooks["replaced"] = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.run() }
        committed.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
        f.hooks.remove("replaced")
        assertEquals(f.generation, pending(f).progress?.recovery?.generation)
        assertEquals(1, f.writes)
    }

    @Test
    fun oldCredentialsAndUnrelatedSelectedCompanyDoNotHideLocalReferences() = runTest {
        val f = SpaceOriginalKeyReferenceReadFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        assertEquals(2, pending(f).events.size)
        assertTrue(f.provider.requests.isEmpty())
    }
}
