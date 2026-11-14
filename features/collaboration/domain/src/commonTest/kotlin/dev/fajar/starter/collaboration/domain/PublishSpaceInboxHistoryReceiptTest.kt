package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class PublishSpaceInboxHistoryReceiptTest {
    @Test
    fun bothNamespacesReturnTheirActualOwnerAndVersionsWithoutParsingSnapshotTokens() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture(legacy)
            f.revision = "opaque-comparison-token"
            f.sourceVersion = f.sourceVersion.copy(revision = 7)
            val value =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
            assertEquals(collaborationHistoryChangeOwner(f.base.location), value.owner)
            assertEquals(f.sourceVersion, value.receipt.source)
            assertEquals(f.current.version, value.receipt.destination)
            assertEquals(0L, value.receipt.destination?.revision)
            assertEquals(1, f.commits)
        }
    }

    @Test
    fun unresolvedOnlyWorkConfirmsTheCurrentPairWithoutAnIndexWrite() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        f.originalByEvent.clear()
        val value =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
        assertEquals(listOf(f.base.original.primaryEventId), value.unresolvedEventIds)
        assertTrue(value.retainedEventIds.isEmpty())
        assertEquals(CollaborationHistoryInboxReceipt(f.sourceVersion, null), value.receipt)
        assertNull(f.proposals.single().projection)
        assertTrue(f.publications.isEmpty())
        assertEquals(0, f.commits)
        assertEquals(1, f.noops)
    }

    @Test
    fun sourceAbsenceKeepsTheObservedDestinationAndNeverInventsARevision() = runTest {
        for (existing in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture()
            f.originalByEvent.clear()
            f.revision = null
            if (existing) f.retain(f.base.activity)
            val before = f.current
            val value =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
            assertEquals(CollaborationHistoryInboxReceipt(null, before.version), value.receipt)
            assertEquals(before, f.current)
            assertTrue(f.publications.isEmpty())
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun semanticReplayReturnsTheSameDestinationVersionWithoutASecondWrite() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val first =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
        val second =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
        assertEquals(first, second)
        assertEquals(1, f.commits)
        assertEquals(1, f.noops)
    }

    @Test
    fun malformedReceiptsCannotConfirmSourceOrIndexProgress() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        f.retain(f.base.activity)
        val version = assertNotNull(f.current.version)
        val valid = CollaborationHistoryInboxReceipt(f.sourceVersion, version)
        for (bad in
            listOf(
                valid.copy(source = null),
                valid.copy(source = f.sourceVersion.copy(generation = "invalid")),
                valid.copy(source = f.sourceVersion.copy(revision = -1)),
                valid.copy(destination = null),
                valid.copy(destination = version.copy(generation = "invalid")),
                valid.copy(destination = version.copy(generation = f.sourceVersion.generation)),
                valid.copy(destination = version.copy(revision = -1)),
                valid.copy(destination = version.copy(revision = version.revision + 2)),
                valid.copy(destination = version.copy(lastOrdinal = version.lastOrdinal + 1)),
            )) {
            f.receiptOverride = bad
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.result()).failure.kind,
            )
        }
    }

    @Test
    fun anUncertainWriteExposesNoReceiptUntilExactAdmittedReplay() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        f.lostResponse = f.failure
        assertEquals(AppResult.Failed(f.failure), f.result())
        val stored = f.current.version
        f.lostResponse = null
        val retry =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
        assertEquals(stored, retry.receipt.destination)
        assertEquals(f.sourceVersion, retry.receipt.source)
        assertEquals(1, f.commits)
        assertEquals(1, f.noops)
    }

    @Test
    fun unresolvedConfirmationContentionRemainsBoundedAndRetainsAllReferences() = runTest {
        for (conflicts in listOf(1, 3)) {
            val f = SpaceInboxHistoryAdmissionFixture()
            f.originalByEvent.clear()
            f.writeConflicts = conflicts
            val result = f.result()
            if (conflicts == 3)
                assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(result).failure.kind)
            else
                assertEquals(
                    listOf(f.base.original.primaryEventId),
                    assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(result)
                        .value
                        .unresolvedEventIds,
                )
            assertEquals(if (conflicts == 3) 3 else 2, f.proposals.size)
            assertTrue(f.publications.isEmpty())
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun deniedOrCancelledUnresolvedConfirmationCannotPublishAReceipt() = runTest {
        for (cancel in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture()
            f.originalByEvent.clear()
            f.before = {
                if (it == "publish") {
                    if (cancel) throw CancellationException("Confirmation cancelled")
                    f.base.thread.base.deny()
                }
            }
            if (cancel) assertFailsWith<CancellationException> { f.result() }
            else assertEquals(AppResult.Failed(f.base.thread.base.denied), f.result())
            assertEquals(0, f.commits)
            assertTrue(f.publications.isEmpty())
        }
    }
}
