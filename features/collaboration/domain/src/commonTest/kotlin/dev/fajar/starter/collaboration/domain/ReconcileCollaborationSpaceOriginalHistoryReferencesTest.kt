package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReconcileCollaborationSpaceOriginalHistoryReferencesTest {
    @Test
    fun exactOriginalAndReplacementFactsResolveWithoutProviderOrQueueWork() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        f.retainReplacement()
        assertEquals(CollaborationHistoryReconciliation(2, 0, null), success(f))
        assertEquals(f.references.events, f.committed)
        assertTrue(f.provider.requests.isEmpty())
        assertTrue(f.pending.requests.isEmpty())
        assertTrue(f.provider.saved.isEmpty())
        assertEquals(0, f.pending.transition.writes)
    }

    @Test
    fun missingRetainedFactsStayPendingWithoutAnyWrite() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        assertEquals(CollaborationHistoryReconciliation(0, 2, null), success(f))
        assertTrue(f.attempts.isEmpty())
        assertTrue(f.committed.isEmpty())
    }

    @Test
    fun knownOriginalDoesNotReadAReplacementForTheSameSource() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.references.events.forEach(f::retainOriginal)
        assertEquals(2, success(f).resolved)
        assertFalse(f.provider.calls.any { it.startsWith("replacement:") })
    }

    @Test
    fun confirmedTombstoneMayResolveBeforeOriginalTimestampIsKnown() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        val id = f.references.events.first().event.eventId
        f.originals[id] = f.originals.getValue(id).copy(timestamp = null, redacted = true)
        assertEquals(CollaborationHistoryReconciliation(1, 1, null), success(f))
        assertEquals(listOf(f.references.events.first()), f.committed)
    }

    @Test
    fun mismatchedOriginalIdentityCannotResolveAnyPartOfTheBatch() = runTest {
        for (field in listOf("id", "sender", "timestamp", "unknown")) {
            val f = SpaceOriginalHistoryReconciliationFixture()
            f.retainOriginal()
            f.retainReplacement()
            val id = f.references.events.first().event.eventId
            val fact = f.originals.getValue(id)
            f.originals[id] =
                when (field) {
                    "id" -> fact.copy(eventId = "\$foreign")
                    "sender" -> fact.copy(sender = "@foreign:example.test")
                    "timestamp" -> fact.copy(timestamp = 999)
                    else -> fact.copy(timestamp = null)
                }
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun replacementTimestampMismatchCannotBeAcknowledged() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainReplacement()
        val id = f.references.events.last().event.eventId
        f.replacements[id] = f.replacements.getValue(id).copy(timestamp = 999)
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun ordinaryCredentialAgeDoesNotHideOwnedRetainedFacts() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.references.events.forEach(f::retainOriginal)
        f.base.native.setup.now += 365.days
        assertEquals(2, success(f).resolved)
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun supersededTaskReturnsNoWorkBeforeReadingContent() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        val obsolete = f.pending.room
        f.pending.capture(obsolete.captured.copy(generation = f.base.id(999)))
        val result = f.reconcile(f.runtime, obsolete, f.references)
        assertEquals(AppResult.Success(null), result)
        assertFalse(f.provider.calls.any { it.startsWith("original:") })
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun directoryDenialPreventsOwnedFactAcquisition() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        f.base.directory.deny()
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertFalse(f.provider.calls.any { it.startsWith("original:") })
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun falseResolveReplyNeverReportsCompletion() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        f.commit = false
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.attempts.size)
        assertTrue(f.committed.isEmpty())
    }

    @Test
    fun lostResolveReplyPreservesCommittedFactsWithoutProviderRecovery() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        f.response = AppResult.Failed(f.base.directory.storageFailure)
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(listOf(f.references.events.first()), f.committed)
        assertTrue(f.provider.requests.isEmpty())
        assertEquals(0, f.pending.transition.writes)
    }

    @Test
    fun denialAfterCommitCannotPublishSuccessOrUndoOwnedReferences() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        f.provider.hooks["resolved"] = { f.base.directory.deny() }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(listOf(f.references.events.first()), f.committed)
        assertEquals(0, f.pending.transition.writes)
    }

    @Test
    fun cancellationStopsBeforeAnyAcknowledgement() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        val entered = CompletableDeferred<Unit>()
        f.provider.hooks["original:\$pending-0"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.run() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun stalledLocalReadHasOneBoundedDeadline() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.provider.hooks["original:\$pending-0"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(f.attempts.isEmpty())
        assertEquals(1, f.provider.calls.count { it == "original:\$pending-0" })
    }

    @Test
    fun nonPendingAndOversizedInputsFailBeforeStorage() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        val room =
            f.pending.room.copy(
                captured =
                    f.pending.room.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider)
            )
        assertIs<AppResult.Failed>(f.reconcile(f.runtime, room, f.references))
        val oversized = f.references.copy(events = List(17) { f.pending.reference(it) })
        assertIs<AppResult.Failed>(f.reconcile(f.runtime, f.pending.room, oversized))
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun spaceProgressCannotBeRelabeledAsCompanyReferences() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        val progress = assertNotNull(f.references.progress)
        f.references =
            f.references.copy(
                progress =
                    progress.copy(
                        provider =
                            CollaborationHistoryProviderIdentity(
                                f.base.position.companyId,
                                f.runtime.deviceId,
                            )
                    )
            )
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun groupReceiptCannotReplaceTheOriginalHistoryDevice() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        val room =
            f.pending.room.copy(
                traversal = f.pending.room.traversal.copy(deviceId = f.runtime.deviceId)
            )
        assertIs<AppResult.Failed>(f.reconcile(f.runtime, room, f.references))
        assertFalse(f.provider.calls.any { it.startsWith("original:") })
    }

    @Test
    fun changedOrRegressingProgressCannotConfirmCapturedReferences() = runTest {
        val seed = SpaceOriginalHistoryReconciliationFixture()
        val progress = assertNotNull(seed.references.progress)
        val changed =
            listOf(
                null,
                progress.copy(generation = seed.base.id(999)),
                progress.copy(revision = -1),
                progress.copy(references = 1),
                progress.copy(
                    incoming = assertNotNull(progress.incoming).copy(head = "another-wave")
                ),
                progress.copy(
                    provider =
                        CollaborationHistoryProviderIdentity(
                            seed.base.position.companyId,
                            seed.runtime.deviceId,
                        )
                ),
            )
        for (value in changed) {
            val f = SpaceOriginalHistoryReconciliationFixture()
            f.provider.progress = value
            assertIs<AppResult.Failed>(f.run())
            assertFalse(f.provider.calls.any { it.startsWith("original:") })
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun lateSourceMetadataRegistrationAccountAndNativeChangesPreventResolution() = runTest {
        for (change in listOf("source", "metadata", "registration", "account", "native")) {
            val f = SpaceOriginalHistoryReconciliationFixture()
            f.retainOriginal()
            f.provider.hooks["original:\$pending-0"] = {
                when (change) {
                    "source" -> f.base.installed = f.base.source.copy(storeId = "b".repeat(64))
                    "metadata" -> f.base.retained = null
                    "registration" ->
                        f.base.registration =
                            assertNotNull(f.base.registration).copy(revision = 999)
                    "account" -> f.base.native.setup.session = null
                    "native" -> {
                        val session = assertNotNull(f.base.native.setup.session)
                        f.base.native.setup.session =
                            session.copy(binding = session.binding.copy(serverId = f.base.id(999)))
                    }
                }
            }
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun removedTaskDuringFactReadReturnsNoCompletion() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        f.provider.hooks["original:\$pending-0"] = {
            f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rooms = emptyList()))
        }
        assertEquals(AppResult.Success(null), f.run())
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun independentSourcePositionAdvanceKeepsExactTaskOwnership() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        f.retainOriginal()
        f.provider.hooks["original:\$pending-0"] = {
            val snapshot = assertNotNull(f.base.snapshot)
            f.base.snapshot =
                snapshot.copy(
                    generation = f.base.id(999),
                    demand =
                        snapshot.demand.copy(
                            sources =
                                snapshot.demand.sources.map { it.copy(position = "later-position") }
                        ),
                )
        }
        assertEquals(1, success(f).resolved)
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun mutableCallerPageCannotReplaceTheCapturedReferenceList() = runTest {
        val f = SpaceOriginalHistoryReconciliationFixture()
        val expected = f.references.events.toList()
        expected.forEach(f::retainOriginal)
        val mutable = expected.toMutableList()
        f.references = f.references.copy(events = mutable)
        f.provider.hooks["metadata"] = { mutable.clear() }
        assertEquals(2, success(f).resolved)
        assertEquals(expected, f.committed)
    }

    @Test
    fun emptyAndFiniteBoundaryPagesNeverAdvanceTheSeek() = runTest {
        val empty = SpaceOriginalHistoryReconciliationFixture()
        empty.references = empty.references.copy(events = emptyList())
        assertEquals(CollaborationHistoryReconciliation(0, 0, null), success(empty))
        assertTrue(empty.attempts.isEmpty())
        val f = SpaceOriginalHistoryReconciliationFixture()
        val position = CollaborationIncomingHistoryPosition("start", null, "seek", 20)
        val progress =
            assertNotNull(f.references.progress).copy(references = 50, incoming = position)
        f.provider.progress = progress
        f.references =
            CollaborationHistoryReferences(progress, (16 until 20).map(f.pending::reference), null)
        f.references.events.forEach(f::retainOriginal)
        assertEquals(CollaborationHistoryReconciliation(4, 0, null), success(f))
        assertEquals((16L until 20L).toList(), f.committed.map { it.sequence })
        assertEquals(position, assertNotNull(f.provider.progress).incoming)
        assertEquals(0, f.pending.transition.writes)
    }

    private suspend fun success(f: SpaceOriginalHistoryReconciliationFixture) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationHistoryReconciliation?>>(f.run()).value
        )
}
