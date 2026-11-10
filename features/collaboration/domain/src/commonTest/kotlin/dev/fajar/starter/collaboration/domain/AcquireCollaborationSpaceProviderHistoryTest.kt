package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationSpaceProviderHistoryTest {
    @Test
    fun invalidRequestDoesNotReadOrInstallAnything() = runTest {
        val f = SpaceProviderHistoryFixture()
        assertIs<AppResult.Failed>(f.useCase(f.runtime.copy(operationId = "bad"), f.conversation))
        assertTrue(f.base.calls.isEmpty())
    }

    @Test
    fun onePageUsesOneTransportAndRetainsMetadataWithoutContentOrSendWork() = runTest {
        val f = SpaceProviderHistoryFixture()
        assertEquals(CollaborationProviderHistoryAcquisition(20, false), success(f))
        assertEquals(listOf<Pair<String?, String?>>(null to null), f.requests)
        assertEquals(listOf(f.page), f.saved)
        assertEquals(listOf<CollaborationHistoryProgress?>(null), f.expected)
        assertEquals(1, f.base.count("transport"))
        assertFalse(
            f.base.calls.any { it in setOf("acquire", "snapshot", "resume", "settle", "append") }
        )
    }

    @Test
    fun exhaustedSavedProgressDoesNotFetchAgainOrResolvePendingContent() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.progress = f.checkpoint(null)
        repeat(3) { assertEquals(CollaborationProviderHistoryAcquisition(0, true), success(f)) }
        assertEquals(0, f.base.count("transport"))
        assertTrue(f.requests.isEmpty())
        assertTrue(f.saved.isEmpty())
        assertEquals(20, f.progress!!.references)
    }

    @Test
    fun newWaveUsesLastCompletedHeadAndPreservesIndependentPendingSeek() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.progress =
            f.checkpoint(null)
                .copy(
                    incoming =
                        CollaborationIncomingHistoryPosition(
                            "old-head",
                            "older-bound",
                            "pending-cursor",
                            12,
                            true,
                        )
                )
        f.page =
            f.page.copy(
                physical = f.page.physical.copy(requestedUntilPosition = "old-head", next = null)
            )
        success(f, CollaborationHistoryPageOrigin.Latest)
        assertEquals(listOf<Pair<String?, String?>>(null to "old-head"), f.requests)
        assertEquals("pending-cursor", f.expected.single()!!.incoming!!.pendingAfter)
        assertEquals(listOf(CollaborationHistoryPageOrigin.Latest), f.origins)
    }

    @Test
    fun restartingIncompleteWaveKeepsItsOriginalOlderBoundary() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.progress =
            f.checkpoint()
                .copy(incoming = CollaborationIncomingHistoryPosition("unfinished", "completed"))
        f.page = f.page.copy(physical = f.page.physical.copy(requestedUntilPosition = "completed"))
        success(f, CollaborationHistoryPageOrigin.Latest)
        assertEquals(listOf<Pair<String?, String?>>(null to "completed"), f.requests)
    }

    @Test
    fun browseProgressCannotBorrowTheIncomingWave() = runTest {
        val f = SpaceProviderHistoryFixture(CollaborationHistoryPurpose.Browse)
        f.progress = f.checkpoint()
        f.page =
            f.page.copy(
                physical =
                    f.page.physical.copy(requestedPosition = "next", start = "next", next = "older")
            )
        success(f)
        assertEquals(listOf<Pair<String?, String?>>("next" to null), f.requests)
        assertNull(f.expected.single()!!.incoming)
    }

    @Test
    fun malformedOrUntypedProgressFailsBeforeTransport() = runTest {
        val seed = SpaceProviderHistoryFixture()
        for (value in
            listOf(
                seed.checkpoint().copy(provider = null),
                seed.checkpoint().copy(incoming = null),
                seed.checkpoint().copy(revision = -1),
            )) {
            val f = SpaceProviderHistoryFixture()
            f.progress = value
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(0, f.base.count("transport"))
            assertTrue(f.saved.isEmpty())
        }
    }

    @Test
    fun wrongLogicalOrPhysicalProvenanceAndProviderBoundsNeverReachStorage() = runTest {
        val seed = SpaceProviderHistoryFixture()
        val s = seed.page
        val p = s.physical
        for (value in
            listOf(
                s.copy(runtime = s.runtime.copy(operationId = seed.queue.id(999))),
                s.copy(physical = p.copy(owner = p.owner.copy(sessionId = "different"))),
                s.copy(physical = p.copy(owner = p.owner.copy(audience = "https://other.example"))),
                s.copy(physical = p.copy(owner = p.owner.copy(accountId = seed.queue.id(999)))),
                s.copy(physical = p.copy(owner = p.owner.copy(companyId = seed.queue.id(999)))),
                s.copy(physical = p.copy(owner = p.owner.copy(nativeSessionId = "bad"))),
                s.copy(physical = p.copy(deviceId = seed.queue.id(999))),
                s.copy(physical = p.copy(roomId = "!different:example.test")),
                s.copy(physical = p.copy(requestedPosition = "wrong")),
                s.copy(physical = p.copy(requestedUntilPosition = "wrong")),
                s.copy(physical = p.copy(next = p.start)),
                s.copy(physical = p.copy(events = p.events + p.events.first())),
                s.copy(physical = p.copy(events = listOf(p.events.first(), p.events.first()))),
                s.copy(physical = p.copy(events = listOf(p.events.first().copy(timestamp = -1)))),
            )) {
            val f = SpaceProviderHistoryFixture()
            f.page = value
            assertIs<AppResult.Failed>(f.acquire())
            assertTrue(f.saved.isEmpty())
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun latestCannotResetAPositionFromAnotherPhysicalCompanyOrDevice() = runTest {
        for (identity in
            listOf(
                CollaborationHistoryProviderIdentity(
                    "90000000-0000-4000-8000-000000000001",
                    "90000000-0000-4000-8000-000000000002",
                )
            )) {
            val f = SpaceProviderHistoryFixture()
            f.progress = f.checkpoint(null).copy(provider = identity)
            f.page = f.page.copy(physical = f.page.physical.copy(requestedUntilPosition = "start"))
            assertIs<AppResult.Failed>(f.acquire(CollaborationHistoryPageOrigin.Latest))
            assertTrue(f.saved.isEmpty())
        }
    }

    @Test
    fun recipientReadinessIsNotAReceivingGateAndPhysicalNativeSessionStaysIndependent() = runTest {
        val f = SpaceProviderHistoryFixture()
        assertNotEquals(f.physical.nativeSessionId, f.runtime.owner.parent.nativeSessionId)
        f.base.observed = f.base.observed.copy(recipientReady = false)
        success(f)
        assertEquals(f.physical, f.saved.single().physical.owner)
    }

    @Test
    fun emptyProviderPageStillCommitsExhaustionBeforeAnotherInvocation() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.page = f.page.copy(physical = f.page.physical.copy(events = emptyList(), next = null))
        f.afterCommit = f.checkpoint(null).copy(references = 0)
        repeat(2) { assertEquals(CollaborationProviderHistoryAcquisition(0, true), success(f)) }
        assertEquals(1, f.requests.size)
        assertEquals(1, f.saved.size)
    }

    @Test
    fun lateAccountDenialBindingAndRuntimeChangesPreventPageRetention() = runTest {
        for (stage in listOf("progress", "transport", "page")) for (change in 0..3) {
            val f = SpaceProviderHistoryFixture()
            f.base.hooks[stage] = {
                when (change) {
                    0 -> f.native.setup.session = null
                    1 -> f.base.directory.deny()
                    2 -> f.base.retained = null
                    3 -> f.base.open = false
                }
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertTrue(f.saved.isEmpty())
        }
    }

    @Test
    fun sdkCredentialDenialWinsEvenWhenRestrictionAndCleanupFail() = runTest {
        val f = SpaceProviderHistoryFixture()
        val denied = f.base.directory.denied.copy(code = "collaboration_peer_unavailable")
        f.base.failures["page"] = denied
        f.base.directory.afterRestrict = { throw IllegalStateException("Fictional storage loss") }
        f.base.hooks["closeSpace"] = { throw IllegalStateException("Fictional cleanup loss") }
        assertEquals(denied, assertIs<AppResult.Failed>(f.acquire()).failure)
        assertEquals(1, f.base.directory.restrictions)
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun nativeConversationPeerUnavailableDoesNotCloseTheDirectory() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.base.failures["transport"] =
            f.base.directory.denied.copy(code = "collaboration_peer_unavailable")
        assertIs<AppResult.Failed>(f.acquire())
        assertEquals(0, f.base.directory.restrictions)
        assertEquals(0, f.base.count("closeSpace"))
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun providerOutagePreservesFailureAndDoesNotRetryOrDenyLocalContent() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.base.failures["page"] = f.base.directory.unavailable
        assertEquals(f.base.directory.unavailable, assertIs<AppResult.Failed>(f.acquire()).failure)
        assertEquals(1, f.requests.size)
        assertEquals(0, f.base.directory.restrictions)
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun elapsedTransportValidityIncludesTimeSpentObtainingTransport() = runTest {
        val f = SpaceProviderHistoryFixture()
        val device = f.base.observed.device
        f.base.observed =
            f.base.observed.copy(
                device =
                    device.copy(
                        gateway = device.gateway!!.copy(expiresAt = device.observedAt + 1.seconds)
                    )
            )
        f.base.hooks["transport"] = { f.native.time += 2.seconds }
        assertIs<AppResult.Failed>(f.acquire())
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun elapsedAndWallDeadlinesPreventLatePageRetention() = runTest {
        for (change in 0..1) {
            val f = SpaceProviderHistoryFixture()
            f.base.hooks["page"] = {
                if (change == 0) f.native.time += 15.seconds else f.native.setup.now += 20.seconds
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertTrue(f.saved.isEmpty())
        }
    }

    @Test
    fun hungProviderTerminatesWithoutRetryOrCommit() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.base.hooks["page"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.acquire()).failure.kind)
        assertEquals(1, f.requests.size)
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun callerCancellationPropagatesWithoutACommitOrAnotherRequest() = runTest {
        val f = SpaceProviderHistoryFixture()
        val entered = CompletableDeferred<Unit>()
        f.base.hooks["page"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.acquire() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.saved.isEmpty())
        assertEquals(1, f.requests.size)
    }

    @Test
    fun returnedPageIsCapturedBeforeAdmissionCanMutateProviderOwnedLists() = runTest {
        val f = SpaceProviderHistoryFixture()
        val mutable = f.page.physical.events.toMutableList()
        val before = mutable.toList()
        f.page = f.page.copy(physical = f.page.physical.copy(events = mutable))
        f.base.hooks["page"] = { f.base.directory.beforeAdmission = { mutable.clear() } }
        success(f)
        assertEquals(before, f.saved.single().physical.events)
        assertTrue(mutable.isEmpty())
    }

    @Test
    fun unsuccessfulCompareAndSetCannotClaimProviderProgress() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.commit = false
        f.response = AppResult.Success(false)
        assertIs<AppResult.Failed>(f.acquire())
        assertNull(f.progress)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun lostCommitResponseDoesNotResubmitTheOldPageOnNextInvocation() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.response = AppResult.Failed(f.base.directory.storageFailure)
        assertEquals(
            f.base.directory.storageFailure,
            assertIs<AppResult.Failed>(f.acquire()).failure,
        )
        assertEquals(f.afterCommit, f.progress)
        f.response = AppResult.Success(true)
        f.page =
            f.page.copy(
                physical =
                    f.page.physical.copy(requestedPosition = "next", start = "next", next = null)
            )
        success(f)
        assertEquals(listOf<Pair<String?, String?>>(null to null, "next" to null), f.requests)
        assertEquals(2, f.saved.size)
    }

    @Test
    fun denialAfterAtomicCommitWinsWithoutRollingBackOwnedProgress() = runTest {
        val f = SpaceProviderHistoryFixture()
        f.base.hooks["committed"] = { f.base.directory.deny() }
        assertIs<AppResult.Failed>(f.acquire())
        assertEquals(f.afterCommit, f.progress)
        assertEquals(1, f.requests.size)
        assertEquals(1, f.base.count("closeSpace"))
    }

    @Test
    fun competingCapturesCannotBothAcknowledgeTheSameCheckpoint() = runTest {
        val f = SpaceProviderHistoryFixture()
        val both = CompletableDeferred<Unit>()
        var pages = 0
        f.base.hooks["page"] = {
            if (++pages == 2) both.complete(Unit)
            both.await()
        }
        val a = async { f.acquire() }
        val b = async { f.acquire() }
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it is AppResult.Success })
        assertEquals(1, results.count { it is AppResult.Failed })
        assertEquals(2, f.requests.size)
    }

    private suspend fun success(
        f: SpaceProviderHistoryFixture,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
    ) =
        assertIs<AppResult.Success<CollaborationProviderHistoryAcquisition>>(f.acquire(origin))
            .value
}
