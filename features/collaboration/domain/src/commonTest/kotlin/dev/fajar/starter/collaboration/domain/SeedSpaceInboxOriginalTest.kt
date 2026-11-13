package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SeedSpaceInboxOriginalTest {
    @Test
    fun exactOwnedOriginalPublishesIndexReferenceAndDemandWithoutSending() = runTest {
        val f = SpaceInboxSeedFixture()
        assertEquals(AppResult.Success(true), f.invoke())
        assertEquals(1, f.probes)
        assertEquals(1, f.commits)
        assertEquals(1, f.checkpointReads)
        assertEquals(f.intent.text, f.publications.single().entry.newest?.preview?.text)
        assertTrue(f.source.base.writes.isEmpty())
        assertTrue(f.source.base.schedules.isEmpty())
        assertTrue(f.source.handoffs.isEmpty())
        assertEquals(0, f.source.draftReads)
        assertEquals(0, f.source.base.observations)
    }

    @Test
    fun anExactReplaySucceedsBeforeAnUnavailableProgressRead() = runTest {
        val f = SpaceInboxSeedFixture()
        assertEquals(AppResult.Success(true), f.invoke())
        val progress = f.storedProgress
        f.progressFailure = f.failure
        assertEquals(AppResult.Success(true), f.invoke())
        assertEquals(1, f.commits)
        assertEquals(1, f.checkpointReads)
        assertEquals(progress, f.storedProgress)
    }

    @Test
    fun anAbsentOriginalDoesNotCreateAnEntryOrReadMutableIndexState() = runTest {
        val f = SpaceInboxSeedFixture()
        f.source.base.stored = null
        assertEquals(AppResult.Success(false), f.invoke())
        assertEquals(0, f.source.base.canonicalReads)
        assertEquals(0, f.source.inboxReads)
        assertEquals(0, f.probes)
        assertEquals(0, f.checkpointReads)
    }

    @Test
    fun changedIdentityAndMalformedCommandEvidenceStopBeforeMetadata() = runTest {
        for (malformed in listOf(false, true)) {
            val f = SpaceInboxSeedFixture()
            f.source.base.stored =
                f.original.queued().let {
                    if (malformed) it.copy(revision = -1)
                    else it.copy(intent = it.intent.copy(text = "Different original"))
                }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.invoke()).failure.kind,
            )
            assertEquals(0, f.source.base.canonicalReads)
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun anUnavailableCanonicalConversationDoesNotFabricateMetadata() = runTest {
        val f = SpaceInboxSeedFixture()
        f.source.base.canonical = null
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertEquals(0, f.source.inboxReads)
        assertEquals(0, f.probes)
    }

    @Test
    fun repeatedProbeContentionHasThreeAttemptsAndNoProgressRead() = runTest {
        val f = SpaceInboxSeedFixture()
        f.probeConflicts = 3
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertEquals(3, f.probes)
        assertEquals(3, f.source.base.reads)
        assertEquals(0, f.checkpointReads)
        assertEquals(0, f.commits)
    }

    @Test
    fun repeatedCommitContentionUsesOneDemandAndStopsAfterThreeAttempts() = runTest {
        val f = SpaceInboxSeedFixture()
        f.commitConflicts = 3
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.invoke()).failure.kind)
        assertEquals(3, f.probes)
        assertEquals(3, f.commits)
        assertEquals(3, f.checkpointReads)
        assertEquals(1, f.demands.distinct().size)
        assertTrue(f.publications.isEmpty())
    }

    @Test
    fun operationalFailuresNeverBecomeAutomaticRetryLoops() = runTest {
        for (stage in listOf("command", "index", "anchor", "probe", "progress")) {
            val f = SpaceInboxSeedFixture()
            when (stage) {
                "command" -> f.source.base.readFailure = f.failure
                "index" -> f.source.inboxFailure = f.failure
                "anchor" -> f.source.activityFailure = f.failure
                "probe" -> f.seedFailure = f.failure
                "progress" -> f.progressFailure = f.failure
            }
            assertEquals(AppResult.Failed(f.failure), f.invoke())
            assertEquals(1, f.source.base.reads)
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun aLostCommittedResponseStopsAndNextInvocationConfirmsWithoutDemand() = runTest {
        val f = SpaceInboxSeedFixture()
        f.lostResponse = f.failure
        assertEquals(AppResult.Failed(f.failure), f.invoke())
        assertEquals(1, f.commits)
        assertTrue(f.retained)
        f.progressFailure = f.failure
        assertEquals(AppResult.Success(true), f.invoke())
        assertEquals(1, f.commits)
        assertEquals(1, f.checkpointReads)
    }

    @Test
    fun currentDenialIsCheckedAfterEveryStorageBoundary() = runTest {
        for (boundary in
            listOf("command", "metadata", "index", "anchor", "probe", "progress", "commit")) {
            val f = SpaceInboxSeedFixture()
            f.at(boundary) { f.source.base.base.deny() }
            assertEquals(
                FailureKind.AccessDenied,
                assertIs<AppResult.Failed>(f.invoke()).failure.kind,
            )
            assertEquals(if (boundary == "commit") 1 else 0, f.publications.size)
            assertEquals(1, f.source.base.reads)
        }
    }

    @Test
    fun accountReplacementRejectsResultsAtEveryBoundary() = runTest {
        for (boundary in
            listOf("command", "metadata", "index", "anchor", "probe", "progress", "commit")) {
            val f = SpaceInboxSeedFixture()
            f.at(boundary) { f.source.base.identity.value = AppResult.Success(null) }
            assertIs<AppResult.Failed>(f.invoke())
            assertEquals(if (boundary == "commit") 1 else 0, f.publications.size)
            assertEquals(1, f.source.base.reads)
        }
    }

    @Test
    fun disposalJoinsLateStorageAndCannotPublishAfterIt() = runTest {
        for (boundary in listOf("command", "metadata", "index", "anchor", "probe", "progress")) {
            val f = SpaceInboxSeedFixture()
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            f.at(boundary) {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    released.await()
                    exited.complete(Unit)
                }
            }
            val job = async { f.invoke() }
            entered.await()
            job.cancel()
            assertFalse(exited.isCompleted)
            released.complete(Unit)
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(exited.isCompleted)
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun aChangedIndexIsRereadBeforePublishingTheOriginal() = runTest {
        val f = SpaceInboxSeedFixture()
        f.commitConflicts = 1
        assertEquals(AppResult.Success(true), f.invoke())
        assertEquals(2, f.source.base.reads)
        assertEquals(2, f.probes)
        assertEquals(2, f.commits)
        assertEquals(1, f.publications.size)
    }

    @Test
    fun terminalEvidenceAndMalformedProgressRetainTheirSeparateMeaning() = runTest {
        val f = SpaceInboxSeedFixture()
        f.source.base.stored = f.original.sent()
        assertEquals(AppResult.Success(true), f.invoke())
        assertEquals(
            CollaborationMessageDelivery.SENT,
            f.publications.single().entry.newest?.preview?.delivery,
        )
        val invalid = SpaceInboxSeedFixture()
        invalid.storedProgress =
            CollaborationSpaceInboxRepairProgress("invalid", invalid.original.id(4), null, null)
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(invalid.invoke()).failure.kind,
        )
        assertEquals(0, invalid.commits)
    }
}
