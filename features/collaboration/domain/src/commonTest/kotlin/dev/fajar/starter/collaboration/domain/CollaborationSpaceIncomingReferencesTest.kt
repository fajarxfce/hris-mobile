package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class CollaborationSpaceIncomingReferencesTest {
    @Test
    fun localReadReturnsOneSixteenReferencePageWithoutTransport() = runTest {
        val f = SpaceIncomingReferencesFixture()
        assertEquals(
            f.source,
            assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.read(f.access, f.key))
                .value,
        )
        assertEquals(listOf("pendingProgress", "references"), f.base.calls)
        assertTrue(f.resolved.isEmpty())
        assertTrue(f.advanced.isEmpty())
    }

    @Test
    fun missingProgressDoesNotInventProviderExhaustionOrObserveReferences() = runTest {
        val f = SpaceIncomingReferencesFixture()
        f.source = CollaborationHistoryReferences(null, emptyList(), null)
        assertEquals(
            f.source,
            assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.read(f.access, f.key))
                .value,
        )
        assertEquals(listOf("pendingProgress"), f.base.calls)
    }

    @Test
    fun expiredTransportCredentialsDoNotHideOwnedPendingWork() = runTest {
        val f = SpaceIncomingReferencesFixture()
        f.base.native.setup.now += 365.days
        assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.read(f.access, f.key))
        assertIs<AppResult.Success<CollaborationSpaceIncomingReferenceCompletion>>(f.finish())
        assertFalse(f.base.calls.any { it in listOf("transport", "page", "acquire", "resume") })
    }

    @Test
    fun denialBeforeReadingReturnsTheGateWithoutStorageOrTransport() = runTest {
        val f = SpaceIncomingReferencesFixture()
        f.base.directory.deny()
        assertEquals(
            f.base.directory.denied,
            assertIs<AppResult.Failed>(f.read(f.access, f.key)).failure,
        )
        assertTrue(f.base.calls.isEmpty())
    }

    @Test
    fun lateAccountChangesBeatEveryReadOrWriteResult() = runTest {
        for (stage in listOf("pendingProgress", "references", "resolve", "advance")) {
            val f = SpaceIncomingReferencesFixture()
            f.base.hooks[stage] = { f.base.native.setup.session = null }
            val result =
                if (stage in listOf("pendingProgress", "references")) f.read(f.access, f.key)
                else f.finish()
            assertIs<AppResult.Failed>(result)
            if (stage == "resolve") assertTrue(f.advanced.isEmpty())
        }
    }

    @Test
    fun missingKeyResolvesOtherSourcesAndRetainsRetryBeyondProviderExhaustion() = runTest {
        val f = SpaceIncomingReferencesFixture()
        f.source = f.source.copy(progress = f.source.progress!!.copy(next = null))
        val batch = f.batch!!
        f.batch =
            batch.copy(
                items =
                    batch.items.toMutableList().also {
                        it[0] =
                            it[0].copy(
                                outcome =
                                    CollaborationIncomingHistoryOutcome.AwaitDecryption(
                                        it[0].sourceEventId,
                                        CollaborationEventDecryptionFailure.UnableToDecrypt,
                                    )
                            )
                    }
            )
        assertEquals(
            CollaborationSpaceIncomingReferenceCompletion(15, 1, true, true),
            assertIs<AppResult.Success<CollaborationSpaceIncomingReferenceCompletion>>(f.finish())
                .value,
        )
        assertEquals(f.source.events.drop(1), f.resolved.single())
        assertTrue(f.advanced.single().second)
    }

    @Test
    fun failedOrLostResolutionCannotAdvanceThePendingSeek() = runTest {
        for (result in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "Fictional lost reply")),
            )) {
            val f = SpaceIncomingReferencesFixture()
            f.resolveResult = result
            assertIs<AppResult.Failed>(f.finish())
            assertEquals(1, f.resolved.size)
            assertTrue(f.advanced.isEmpty())
        }
    }

    @Test
    fun aLaterSeekFailureDoesNotReplayTheEarlierResolutionInTheSameInvocation() = runTest {
        val f = SpaceIncomingReferencesFixture()
        f.advanceResult = AppResult.Success(false)
        assertIs<AppResult.Failed>(f.finish())
        assertEquals(listOf("resolve", "advance"), f.base.calls)
        assertEquals(1, f.resolved.size)
        assertEquals(1, f.advanced.size)
    }

    @Test
    fun cancellationDisposesThePendingReadWithoutAnyWrite() = runTest {
        val f = SpaceIncomingReferencesFixture()
        val entered = CompletableDeferred<Unit>()
        f.references = {
            flow {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = async { f.read(f.access, f.key) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.resolved.isEmpty())
        assertTrue(f.advanced.isEmpty())
    }

    @Test
    fun blockedLocalResolutionTimesOutWithoutAnUnboundedRetry() = runTest {
        val f = SpaceIncomingReferencesFixture()
        f.base.hooks["resolve"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.finish()).failure.kind)
        assertEquals(1, f.resolved.size)
        assertTrue(f.advanced.isEmpty())
    }

    @Test
    fun callerMutationCannotChangeTheAlreadyCapturedResolutionOrSeek() = runTest {
        val f = SpaceIncomingReferencesFixture()
        val references = f.source.events.toMutableList()
        val expected = references.toList()
        f.source = f.source.copy(events = references)
        val items = f.batch!!.items.toMutableList()
        f.batch = f.batch!!.copy(items = items)
        f.base.directory.beforeAdmission = {
            references.clear()
            items.clear()
        }
        assertIs<AppResult.Success<CollaborationSpaceIncomingReferenceCompletion>>(f.finish())
        assertEquals(expected, f.resolved.single())
        assertEquals(expected, f.advanced.single().first.events)
    }

    @Test
    fun wrongPhysicalBatchNeverOpensStorageOrTheSendRuntime() = runTest {
        val f = SpaceIncomingReferencesFixture()
        f.batch =
            f.batch!!.copy(provider = f.batch!!.provider.copy(deviceId = f.provider.queue.id(777)))
        assertIs<AppResult.Failed>(f.finish())
        assertTrue(f.base.calls.isEmpty())
        assertTrue(f.resolved.isEmpty())
        assertTrue(f.advanced.isEmpty())
    }
}
