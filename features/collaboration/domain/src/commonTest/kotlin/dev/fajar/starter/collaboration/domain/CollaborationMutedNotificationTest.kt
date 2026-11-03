package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

class CollaborationMutedNotificationTest {
    @Test
    fun initialMuteRetainsNoPreviewOrActionsAndUnmuteCannotReviveOriginal() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.mute()
        val plan = f.mutePlan()
        assertNull(f.stored)
        assertEquals(AppResult.Success(Unit), f.commitMute(plan))
        val saved = assertNotNull(f.stored)
        assertEquals(CollaborationNotificationPublicationState.Cancelled, saved.body.state)
        assertEquals(f.projection.original!!.contentHash, saved.body.contentHash)
        assertNull(saved.body.message)
        assertNull(saved.body.actions)
        assertEquals(0, f.seals)
        assertEquals(0, f.opens)
        assertEquals(0, f.projection.identityCalls)
        f.projection.preference(CollaborationNotificationSettings())
        assertEquals(AppResult.Success(CollaborationNotificationIssuance.Unchanged), f.issue())
        assertEquals(saved, f.stored)
    }

    @Test
    fun activeReplacementKeepsNativeIdentityAndStripsAllPrivateMaterial() = runTest {
        fFixture { f ->
            f.issue()
            val previous = f.stored!!
            f.mute()
            val plan = f.mutePlan()
            assertEquals(previous.version, plan.previous!!.version)
            assertEquals(AppResult.Success(Unit), f.commitMute(plan))
            val saved = f.stored!!
            assertEquals(previous.id, saved.id)
            assertEquals(previous.body.target.notificationId, saved.body.target.notificationId)
            assertNotEquals(previous.body.target.renderId, saved.body.target.renderId)
            assertTrue(saved.version.revision > previous.version.revision)
            assertNull(saved.body.actions)
            assertNull(saved.body.message)
            assertEquals(4, f.seals)
        }
    }

    @Test
    fun lostCommitResponseKeepsAnInactiveOriginalAndReplayDoesNotWriteAgain() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.mute()
        val plan = f.mutePlan()
        f.writeFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.commitMute(plan))
        assertEquals(CollaborationNotificationPublicationState.Cancelled, f.stored!!.body.state)
        assertEquals(
            AppResult.Success(CollaborationMutedNotificationPreparation.AlreadySuppressed),
            prepare(f),
        )
        assertEquals(1, f.writes)
        f.projection.preference(CollaborationNotificationSettings())
        assertEquals(AppResult.Success(CollaborationNotificationIssuance.Unchanged), f.issue())
    }

    @Test
    fun unavailableStorageCannotClaimDurableSuppression() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.mute()
        val plan = f.mutePlan()
        f.commit = false
        f.writeFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.commitMute(plan))
        assertNull(f.stored)
        f.writeFailure = null
        f.commit = true
        assertEquals(AppResult.Success(Unit), f.commitMute(f.mutePlan()))
    }

    @Test
    fun unmuteChangedSourceNativeFamilyAndDenialInvalidatePendingPlans() = runTest {
        for (change in 0..3) {
            val f = CollaborationNotificationIssuanceFixture()
            f.mute()
            val plan = f.mutePlan()
            when (change) {
                0 -> f.projection.preference(CollaborationNotificationSettings())
                1 ->
                    f.projection.original =
                        f.projection.original!!.copy(contentHash = "b".repeat(64))
                2 -> f.replaceFamily()
                3 -> f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            }
            val result = f.commitMute(plan)
            assertIs<AppResult.Failed>(result)
            if (change == 3) assertEquals(AppResult.Failed(f.local.denied), result)
            assertEquals(0, f.writes)
            assertNull(f.stored)
        }
    }

    @Test
    fun forgedMetadataAndChangedJournalNeverCommit() = runTest {
        for (change in 0..3) {
            val f = CollaborationNotificationIssuanceFixture()
            f.issue()
            f.mute()
            val original = f.mutePlan()
            val plan =
                when (change) {
                    0 -> original.copy(contentHash = "b".repeat(64))
                    1 ->
                        original.copy(
                            parent =
                                original.parent.copy(
                                    accountId = CollaborationNotificationIssuanceFixture.id(8888)
                                )
                        )
                    2 -> original.copy(previous = null)
                    else -> {
                        f.inactive()
                        original
                    }
                }
            assertIs<AppResult.Failed>(f.commitMute(plan))
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun olderCandidatePreservesNewerJournalAndSameEventMetadataMismatchFails() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.issue()
        val previous = f.stored!!
        f.mute()
        f.projection.original =
            f.projection.original!!.copy(
                timestamp = 99,
                primaryEventId = "\$old",
                eventIds = listOf("\$old"),
            )
        assertEquals(
            AppResult.Success(CollaborationMutedNotificationPreparation.AlreadySuppressed),
            f.prepareMute(
                f.parent,
                f.scope().access,
                f.projection.conversation,
                f.projection.room,
                "\$old",
            ),
        )
        assertEquals(previous, f.stored)
        f.projection.original =
            f.projection.original!!.copy(
                primaryEventId = f.projection.event,
                eventIds = listOf(f.projection.event),
            )
        assertIs<AppResult.Failed>(prepare(f))
        assertEquals(1, f.writes)
    }

    @Test
    fun parentOrDenialDuringFailedReadWinsOverItsTechnicalFailure() = runTest {
        for (denied in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            f.mute()
            f.readFailure = f.local.storage
            f.afterRead = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                else f.replaceFamily()
            }
            val result = prepare(f)
            if (denied) assertEquals(AppResult.Failed(f.local.denied), result)
            else
                assertEquals(
                    FailureKind.Unauthorized,
                    assertIs<AppResult.Failed>(result).failure.kind,
                )
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun prepareTimeoutAndCancellationJoinPendingHistoryWithoutWriting() = runTest {
        for (cancel in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            f.mute()
            val entered = CompletableDeferred<Unit>()
            var joined = false
            f.projection.afterHistory = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val job = async { prepare(f) }
            select {
                entered.onAwait {}
                job.onAwait { fail("Returned before history barrier") }
            }
            if (cancel) job.cancelAndJoin()
            else
                assertEquals(
                    FailureKind.Unavailable,
                    assertIs<AppResult.Failed>(job.await()).failure.kind,
                )
            assertTrue(joined)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun cancellationAfterCommitRetainsWatermarkAndConcurrentAttemptDoesNotWait() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.mute()
        val plan = f.mutePlan()
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.afterWrite = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val job = async { f.commitMute(plan) }
        select {
            entered.onAwait {}
            job.onAwait { fail("Returned before storage barrier") }
        }
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(f.commitMute(plan)).failure.kind,
        )
        job.cancelAndJoin()
        assertTrue(joined)
        assertEquals(CollaborationNotificationPublicationState.Cancelled, f.stored!!.body.state)
        assertEquals(
            AppResult.Success(CollaborationMutedNotificationPreparation.AlreadySuppressed),
            prepare(f),
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun settingsChangingDuringCommittedWriteDoesNotReportConfirmedCurrentSuppression() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.mute()
        val plan = f.mutePlan()
        f.afterWrite = { f.projection.preference(CollaborationNotificationSettings()) }
        assertIs<AppResult.Failed>(f.commitMute(plan))
        assertEquals(CollaborationNotificationPublicationState.Cancelled, f.stored!!.body.state)
        assertEquals(AppResult.Success(CollaborationNotificationIssuance.Unchanged), f.issue())
    }

    @Test
    fun missingOriginalStaysPendingAndOwnedRedactionNeedsNoSyntheticWatermark() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.mute()
        f.projection.original = null
        assertEquals(
            AppResult.Success(CollaborationMutedNotificationPreparation.PendingHistory),
            prepare(f),
        )
        val facts = f.projection.read.base.facts
        facts[f.projection.event] = facts.getValue(f.projection.event).copy(redacted = true)
        assertEquals(
            AppResult.Success(CollaborationMutedNotificationPreparation.AlreadySuppressed),
            prepare(f),
        )
        assertEquals(0, f.writes)
        assertNull(f.stored)
    }

    @Test
    fun aliasIsCanonicalizedAndNewerMutedOriginalReplacesThePresentationWatermark() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.mute()
        val aliases = f.projection.original!!.eventIds
        val aliased =
            assertIs<CollaborationMutedNotificationPreparation.Ready>(
                    assertIs<AppResult.Success<*>>(
                            f.prepareMute(
                                f.parent,
                                f.scope().access,
                                f.projection.conversation,
                                f.projection.room,
                                aliases.last(),
                            )
                        )
                        .value
                )
                .plan
        assertEquals(f.projection.event, aliased.eventId)
        assertEquals(AppResult.Success(Unit), f.commitMute(aliased))
        val previous = f.stored!!
        f.projection.original =
            f.projection.original!!.copy(
                primaryEventId = "\$later",
                eventIds = listOf("\$later"),
                timestamp = 101,
            )
        val later =
            assertIs<CollaborationMutedNotificationPreparation.Ready>(
                    assertIs<AppResult.Success<*>>(
                            f.prepareMute(
                                f.parent,
                                f.scope().access,
                                f.projection.conversation,
                                f.projection.room,
                                "\$later",
                            )
                        )
                        .value
                )
                .plan
        assertEquals(AppResult.Success(Unit), f.commitMute(later))
        assertEquals(previous.body.target.notificationId, f.stored!!.body.target.notificationId)
        assertEquals("\$later", f.stored!!.body.target.eventId)
        assertNull(f.stored!!.body.actions)
        assertEquals(0, f.seals)
    }

    @Test
    fun muteExpiringDuringHistoryReadCannotProduceAPlan() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val until = f.local.clock.now() + kotlin.time.Duration.parse("1s")
        f.projection.preference(
            CollaborationNotificationSettings(CollaborationNotificationMute.Until(until))
        )
        f.projection.afterHistory = { f.local.setup.now = until }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(prepare(f)).failure.kind)
        assertEquals(0, f.writes)
    }

    private suspend fun prepare(f: CollaborationNotificationIssuanceFixture) =
        f.prepareMute(
            f.parent,
            f.scope().access,
            f.projection.conversation,
            f.projection.room,
            f.projection.event,
        )

    private suspend fun fFixture(
        block: suspend (CollaborationNotificationIssuanceFixture) -> Unit
    ) = block(CollaborationNotificationIssuanceFixture())
}
