package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationProviderHistoryTest {
    @Test
    fun onePageIsReadmittedAndStagedWithoutStartingDecryptionOrDelivery() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        assertEquals(
            AppResult.Success(CollaborationProviderHistoryAcquisition(20, false)),
            f.acquire(),
        )
        assertEquals(listOf(f.page), f.saved)
        assertEquals("next", f.progress!!.next)
        assertEquals(2, f.local.setup.calls)
        assertEquals(listOf("get", "room", "get", "room"), f.admissions)
        assertEquals(1, f.reads)
        assertEquals(1, f.stages)
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(0, f.delivery.windows)
    }

    @Test
    fun ownedContinuationIsUsedExactlyAndTerminalProgressDoesNotRefetch() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        f.progress =
            CollaborationHistoryProgress(
                ProviderHistoryAcquisitionFixture.generation,
                8,
                "retained",
                9,
            )
        f.page = f.page.copy(requestedPosition = "retained", start = "retained", next = null)
        assertEquals(
            AppResult.Success(CollaborationProviderHistoryAcquisition(20, true)),
            f.acquire(),
        )
        assertEquals(9, f.progress!!.revision)
        val saved = f.saved.toList()
        assertEquals(
            AppResult.Success(CollaborationProviderHistoryAcquisition(0, true)),
            f.acquire(),
        )
        assertEquals(1, f.reads)
        assertEquals(saved, f.saved)
    }

    @Test
    fun emptyAdvancingAndTerminalPagesStillReachAtomicStorage() = runTest {
        for (next in listOf("empty-gap", null)) {
            val f = ProviderHistoryAcquisitionFixture()
            f.page = f.page.copy(events = emptyList(), next = next)
            assertEquals(
                AppResult.Success(CollaborationProviderHistoryAcquisition(0, next == null)),
                f.acquire(),
            )
            assertEquals(listOf(f.page), f.saved)
            assertEquals(2, f.local.setup.calls)
        }
    }

    @Test
    fun invalidSelectionsAndExpiredOrRestrictedOwnersNeverStartTransport() = runTest {
        for (kind in listOf("device", "conversation", "expiry", "denial", "gate")) {
            val f = ProviderHistoryAcquisitionFixture()
            when (kind) {
                "expiry" -> f.local.setup.now += 3.hours
                "denial" ->
                    f.local.gate.value = CollaborationListAdmission("blocked", f.local.denied)
                "gate" -> f.local.gateFailure = f.local.storage
            }
            val result =
                f.useCase(
                    "selection",
                    if (kind == "device") "bad" else f.traversal.deviceId,
                    if (kind == "conversation") "bad" else f.traversal.conversationId,
                )
            assertIs<AppResult.Failed>(result)
            assertEquals(0, f.reads)
            assertEquals(0, f.local.setup.calls)
        }
    }

    @Test
    fun invalidOrUnavailableStoredProgressCannotDriveARequest() = runTest {
        for (invalid in
            listOf(
                CollaborationHistoryProgress("bad", 0, "next", 0),
                CollaborationHistoryProgress(
                    ProviderHistoryAcquisitionFixture.generation,
                    -1,
                    "next",
                    0,
                ),
                CollaborationHistoryProgress(
                    ProviderHistoryAcquisitionFixture.generation,
                    0,
                    "bad\nposition",
                    0,
                ),
                CollaborationHistoryProgress(
                    ProviderHistoryAcquisitionFixture.generation,
                    0,
                    "next",
                    -1,
                ),
            )) {
            val f = ProviderHistoryAcquisitionFixture()
            f.progress = invalid
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(0, f.reads)
        }
        val f = ProviderHistoryAcquisitionFixture()
        f.readFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.acquire())
        assertEquals(0, f.reads)
    }

    @Test
    fun foreignMalformedOrUnrequestedPagesCannotReachStorage() = runTest {
        val page = ProviderHistoryAcquisitionFixture().page
        for (invalid in
            listOf(
                page.copy(owner = page.owner.copy(nativeSessionId = "foreign")),
                page.copy(owner = page.owner.copy(accountId = "foreign")),
                page.copy(deviceId = "foreign"),
                page.copy(roomId = "!foreign:test"),
                page.copy(requestedPosition = "unrequested"),
                page.copy(next = page.start),
                page.copy(events = page.events + page.events.first()),
                page.copy(events = listOf(page.events.first(), page.events.first())),
                page.copy(events = listOf(page.events.first().copy(timestamp = -1))),
            )) {
            val f = ProviderHistoryAcquisitionFixture()
            f.page = invalid
            assertEquals(
                "client.collaboration_invalid_history_page",
                assertIs<AppResult.Failed>(f.acquire()).failure.code,
            )
            assertEquals(0, f.stages)
            assertEquals(1, f.local.setup.calls)
        }
    }

    @Test
    fun sessionReplacementDuringProgressOrPageReadCannotPublish() = runTest {
        for (duringPage in listOf(false, true)) {
            val f = ProviderHistoryAcquisitionFixture()
            val replace: suspend () -> Unit = { f.local.setup.session = null }
            if (duringPage) f.beforePageReturns = replace else f.beforeProgressReturns = replace
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(0, f.stages)
            assertEquals(if (duringPage) 1 else 0, f.reads)
        }
    }

    @Test
    fun companyDenialPersistsThroughFailedRestrictionStorageAndLaterRetries() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        f.pageFailure = f.local.denied
        assertEquals(AppResult.Failed(f.local.denied), f.acquire())
        assertEquals(f.local.denied, f.local.gate.value.denial)
        f.pageFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.denied), f.acquire())
        assertEquals(1, f.reads)
        assertEquals(0, f.stages)
    }

    @Test
    fun sdkExpiryAndRecipientFailureDoNotRestrictOwnedCompanyFacts() = runTest {
        for (failure in
            listOf(
                Failure(
                    FailureKind.Unauthorized,
                    "Expired",
                    code = "client.collaboration_sdk_unauthorized",
                ),
                Failure(
                    FailureKind.AccessDenied,
                    "Unavailable",
                    code = "collaboration_room_not_found",
                ),
            )) {
            val f = ProviderHistoryAcquisitionFixture()
            f.pageFailure = failure
            assertEquals(AppResult.Failed(failure), f.acquire())
            assertNull(f.local.gate.value.denial)
            assertEquals(0, f.stages)
        }
    }

    @Test
    fun changedRoomOrCompanyDenialAtReadmissionCannotStageThePage() = runTest {
        for (denial in listOf(false, true)) {
            val f = ProviderHistoryAcquisitionFixture()
            f.beforePageReturns = {
                if (denial) f.delivery.control.remoteFailure = f.local.denied
                else
                    f.delivery.control.room =
                        f.delivery.control.room.copy(matrixRoomId = "!changed:test")
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(0, f.stages)
            if (denial) assertEquals(f.local.denied, f.local.gate.value.denial)
        }
    }

    @Test
    fun cancellationStopsThePendingPageWithoutRetryOrStorage() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        val entered = CompletableDeferred<Unit>()
        f.beforePageReturns = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch { f.acquire() }
        entered.await()
        assertEquals(1, f.active)
        pending.cancelAndJoin()
        assertEquals(0, f.active)
        assertEquals(1, f.reads)
        assertEquals(0, f.stages)
    }

    @Test
    fun conflictOrLostCommitResponseNeverRetriesThePage() = runTest {
        for (lost in listOf(false, true)) {
            val f = ProviderHistoryAcquisitionFixture()
            if (lost) f.stageFailure = f.local.storage else f.rejectStage = true
            val rejected = assertIs<AppResult.Failed>(f.acquire())
            if (lost) assertEquals(f.local.storage, rejected.failure)
            else
                assertEquals("client.collaboration_history_progress_changed", rejected.failure.code)
            assertEquals(if (lost) listOf(f.page) else emptyList(), f.saved)
            assertEquals(1, f.reads)
            assertEquals(1, f.stages)
        }
    }

    @Test
    fun lateExpiryOrDenialAfterCommitKeepsFactsAndWithholdsSuccess() = runTest {
        for (denial in listOf(false, true)) {
            val f = ProviderHistoryAcquisitionFixture()
            f.afterStage = {
                if (denial)
                    f.local.gate.value = CollaborationListAdmission("blocked", f.local.denied)
                else f.local.setup.now += 3.hours
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(listOf(f.page), f.saved)
            assertEquals(1, f.reads)
            assertEquals(1, f.stages)
        }
    }

    @Test
    fun returnedReferencesAreDetachedBeforeReadmissionSuspends() = runTest {
        val f = ProviderHistoryAcquisitionFixture()
        val source = f.page.events.toMutableList()
        val original = source.toList()
        f.page = f.page.copy(events = source)
        f.local.beforeAdmission = { if (f.reads > 0) source.clear() }
        assertEquals(
            AppResult.Success(CollaborationProviderHistoryAcquisition(20, false)),
            f.acquire(),
        )
        assertEquals(original, f.saved.single().events)
        assertTrue(source.isEmpty())
    }
}
