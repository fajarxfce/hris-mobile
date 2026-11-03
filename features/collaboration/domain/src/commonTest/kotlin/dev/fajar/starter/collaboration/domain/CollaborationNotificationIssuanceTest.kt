package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

class CollaborationNotificationIssuanceTest {
    @Test
    fun issuanceCarriesExactPreviousRenderAcrossReuseAndFamilyReplacement() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        suspend fun result() =
            assertIs<CollaborationNotificationIssuance.Ready>(
                assertIs<AppResult.Success<CollaborationNotificationIssuance>>(f.issue()).value
            )
        val first = result()
        assertNull(first.previous)
        val previous = first.publication
        val reused = result()
        assertEquals(previous.id, reused.previous!!.id)
        assertEquals(previous.version, reused.previous!!.version)
        assertEquals(previous.body.target, reused.previous!!.target)
        f.replaceFamily()
        val changed = result()
        assertNotEquals(previous.body.target.renderId, changed.publication.body.target.renderId)
        assertEquals(previous.body.target, changed.previous!!.target)
        assertEquals(previous.body.parent, changed.previous!!.parent)
        assertEquals(previous.body.state, changed.previous!!.state)
    }

    @Test
    fun onlyANewlyRetainedOriginalAllowsAnAlertWhileReplayAndFamilyRerenderStaySilent() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        suspend fun newOriginal() =
            assertIs<CollaborationNotificationIssuance.Ready>(
                    assertIs<AppResult.Success<CollaborationNotificationIssuance>>(f.issue()).value
                )
                .newOriginal
        assertTrue(newOriginal())
        assertFalse(newOriginal())
        f.replaceFamily()
        assertFalse(newOriginal())
        val original = f.projection.original!!
        f.projection.original =
            original.copy(
                primaryEventId = "\$fresh-original",
                eventIds = listOf("\$fresh-original"),
                timestamp = original.timestamp + 1,
            )
        assertTrue(newOriginal())
        assertFalse(newOriginal())
    }

    @Test
    fun exactActiveReplayKeepsNativeIdRenderAndAuthenticatedEnvelopesOffline() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.local.setup.now += 365.days
        f.local.setup.workspace = null
        f.local.setup.beforeWorkspace = { error("No selected company") }
        val first = ready(f.issue())
        assertEquals(CollaborationNotificationPublicationState.Prepared, first.body.state)
        assertEquals(4, f.seals)
        assertEquals(4, f.opens)
        assertEquals(first, ready(f.issue()))
        assertEquals(4, f.seals)
        assertEquals(8, f.opens)
        assertEquals(1, f.writes)
        assertEquals(0, f.local.setup.calls)
        assertTrue(f.local.scheduledKeys.isEmpty())
        assertFalse(first.toString().contains("Private message"))
    }

    @Test
    fun lostStorageResponseReusesTheCommittedRenderWithoutSealingAgain() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.writeOutcome = CollaborationNotificationPublicationWrite.Contended
        failed(f.issue(), FailureKind.Conflict)
        val retained = f.stored!!
        assertEquals(4, f.seals)
        f.writeOutcome = null
        assertEquals(retained, ready(f.issue()))
        assertEquals(1, f.writes)
        assertEquals(4, f.seals)
    }

    @Test
    fun acquiredNewerOriginalReplacesStableCardAndOlderCandidateDoesNothing() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val firstMessage = f.message()
        val firstOriginal = f.projection.original!!
        val first = ready(f.issue(firstMessage))
        f.projection.original =
            firstOriginal.copy(
                primaryEventId = "\$later",
                eventIds = listOf("\$later"),
                timestamp = firstOriginal.timestamp + 1,
                text = "Later original",
                contentHash = "b".repeat(64),
            )
        val second = ready(f.issue())
        assertEquals(first.id, second.id)
        assertEquals(first.body.target.notificationId, second.body.target.notificationId)
        assertNotEquals(first.body.target.renderId, second.body.target.renderId)
        f.projection.original = firstOriginal
        assertEquals(
            AppResult.Success(CollaborationNotificationIssuance.Unchanged),
            f.issue(firstMessage),
        )
        assertEquals(second, f.stored)
        assertEquals(8, f.seals)
    }

    @Test
    fun sameTimestampUsesExplicitEventIdTieBreakAndChangedOriginalStampIsConflict() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = f.projection.original!!
        f.projection.original = original.copy(primaryEventId = "\$m", eventIds = listOf("\$m"))
        val first = ready(f.issue())
        f.projection.original = original.copy(primaryEventId = "\$a", eventIds = listOf("\$a"))
        assertEquals(AppResult.Success(CollaborationNotificationIssuance.Unchanged), f.issue())
        assertEquals(first, f.stored)
        f.projection.original = original.copy(primaryEventId = "\$z", eventIds = listOf("\$z"))
        val next = ready(f.issue())
        f.projection.original = f.projection.original!!.copy(timestamp = original.timestamp + 1)
        failed(f.issue(), FailureKind.Conflict)
        assertEquals(next, f.stored)
    }

    @Test
    fun inactiveSameSourceNeverRevivesAcrossReauthenticationButNewSourceCanReplaceIt() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        ready(f.issue())
        f.inactive()
        val inactive = f.stored!!
        f.replaceFamily()
        assertEquals(AppResult.Success(CollaborationNotificationIssuance.Unchanged), f.issue())
        assertEquals(inactive, f.stored)
        val original = f.projection.original!!
        f.projection.original =
            original.copy(
                primaryEventId = "\$new",
                eventIds = listOf("\$new"),
                timestamp = original.timestamp + 1,
            )
        val next = ready(f.issue())
        assertEquals(inactive.body.target.notificationId, next.body.target.notificationId)
        assertEquals(f.parent, next.body.parent)
    }

    @Test
    fun changingPreviewReplacesAnActiveRenderAndDoesNotReuseOldMuteSettings() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val first = ready(f.issue())
        f.projection.preference(
            CollaborationNotificationSettings(preview = CollaborationNotificationPreview.Hidden)
        )
        // The fixture also advances its settings revision, as actual storage must.
        f.projection.settings.state =
            f.projection.settings.state.copy(
                version = f.projection.settings.state.version!!.copy(revision = 1)
            )
        val next = ready(f.issue())
        assertEquals(first.body.target.notificationId, next.body.target.notificationId)
        assertNotEquals(first.body.target.renderId, next.body.target.renderId)
        assertNull(next.body.message!!.body)
        assertNull(next.body.message!!.senderName)
        val mute =
            assertIs<CollaborationNotificationAction.Mute>(
                f.tokens[next.body.actions!!.mute]!!.second
            )
        assertEquals(next.body.message!!.settingsVersion, mute.expected)
        assertEquals(CollaborationNotificationPreview.Hidden, mute.preview)
    }

    @Test
    fun forgedProjectionAndChangedReadMuteSelfOrOriginalNeverBecomePublications() = runTest {
        for (change in 0..5) {
            val f = CollaborationNotificationIssuanceFixture()
            var message = f.message()
            when (change) {
                0 -> message = message.copy(body = "Injected preview")
                1 ->
                    f.projection.preference(
                        CollaborationNotificationSettings(
                            mute = CollaborationNotificationMute.Forever
                        )
                    )
                2 -> f.projection.self = CollaborationSelfIdentity(f.projection.original!!.sender)
                3 ->
                    f.projection.original =
                        f.projection.original!!.copy(redacted = true, text = null)
                4 ->
                    f.projection.read.state =
                        CollaborationReadState(
                            CollaborationReadStateRevision(
                                CollaborationNotificationIssuanceFixture.id(7777),
                                0,
                            ),
                            setOf(message.eventId),
                            emptyList(),
                            null,
                        )
                5 -> f.projection.original = null
            }
            assertIs<AppResult.Failed>(f.issue(message))
            assertNull(f.stored)
            assertEquals(0, f.seals)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun wrongOpenedEnvelopeAndStorageCapacityDoNotPublishOrRetry() = runTest {
        val wrong = CollaborationNotificationIssuanceFixture()
        wrong.wrongOpenedAction = true
        failed(wrong.issue(), FailureKind.Conflict)
        assertEquals(1, wrong.seals)
        assertEquals(0, wrong.writes)
        val full = CollaborationNotificationIssuanceFixture()
        full.commit = false
        full.writeOutcome = CollaborationNotificationPublicationWrite.CapacityExceeded
        failed(full.issue(), FailureKind.Storage)
        assertEquals(1, full.writes)
        assertNull(full.stored)
    }

    @Test
    fun parentReplacementDuringSealingAndAfterCommitWithholdsOutcome() = runTest {
        for (afterCommit in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val message = f.message()
            if (afterCommit) f.afterWrite = { f.replaceFamily() }
            else f.afterSeal = { f.replaceFamily() }
            failed(f.issue(message), FailureKind.Unauthorized)
            if (afterCommit) assertNotNull(f.stored) else assertNull(f.stored)
            assertEquals(if (afterCommit) 1 else 0, f.writes)
        }
    }

    @Test
    fun concurrentIssuanceDoesNotQueueAndCancellationJoinsPendingSealing() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val message = f.message()
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.afterSeal = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val job = async { f.issue(message) }
        select {
            entered.onAwait {}
            job.onAwait { fail("Operation returned before the test barrier") }
        }
        failed(f.issue(message), FailureKind.Unavailable)
        job.cancelAndJoin()
        assertTrue(joined)
        assertEquals(1, f.seals)
        assertEquals(0, f.writes)
    }

    @Test
    fun deadlineJoinsPendingIOAndCommittedCancellationKeepsExactInput() = runTest {
        val slow = CollaborationNotificationIssuanceFixture()
        val message = slow.message()
        var joined = false
        slow.afterRead = {
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        failed(slow.issue(message), FailureKind.Unavailable)
        assertTrue(joined)
        assertEquals(0, slow.writes)
        val f = CollaborationNotificationIssuanceFixture()
        val readyMessage = f.message()
        val entered = CompletableDeferred<Unit>()
        var commitJoined = false
        f.afterWrite = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                commitJoined = true
            }
        }
        val job = async { f.issue(readyMessage) }
        select {
            entered.onAwait {}
            job.onAwait { fail("Operation returned before the test barrier") }
        }
        job.cancelAndJoin()
        assertTrue(commitJoined)
        val retained = f.stored!!
        f.afterWrite = {}
        assertEquals(retained, ready(f.issue(readyMessage)))
        assertEquals(4, f.seals)
    }

    @Test
    fun originalTimestampChangesDuringSealingCannotCommitAnEarlierWatermark() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val message = f.message()
        f.afterSeal = { f.projection.original = f.projection.original!!.copy(timestamp = 200) }
        failed(f.issue(message), FailureKind.Conflict)
        assertEquals(0, f.writes)
    }

    @Test
    fun retainedDenialWinsEvenWhenSealingOrSessionReadAlsoFails() = runTest {
        for (afterCommit in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val message = f.message()
            val deny: suspend () -> Unit = {
                f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                f.local.setup.sessionFailure = f.local.storage
                f.sealFailure = f.local.storage
            }
            if (afterCommit) f.afterWrite = deny else f.afterSeal = deny
            assertEquals(AppResult.Failed(f.local.denied), f.issue(message))
            assertEquals(if (afterCommit) 1 else 0, f.writes)
        }
    }

    @Test
    fun privacyChangeAfterCommitKeepsPreparedRecordWithoutReturningIt() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val message = f.message()
        f.afterWrite = {
            f.projection.preference(
                CollaborationNotificationSettings(preview = CollaborationNotificationPreview.Hidden)
            )
        }
        failed(f.issue(message), FailureKind.Conflict)
        assertEquals(message, f.stored!!.body.message)
        assertEquals(CollaborationNotificationPublicationState.Prepared, f.stored!!.body.state)
    }

    @Test
    fun replayRejectsTamperedEnvelopeAndConcurrentRenderReplacement() = runTest {
        for (replace in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            ready(f.issue())
            val original = f.stored!!
            if (replace) f.afterOpen = { f.inactive() } else f.wrongOpenedAction = true
            failed(f.issue(), FailureKind.Conflict)
            assertEquals(4, f.seals)
            assertEquals(1, f.writes)
            if (!replace) assertEquals(original, f.stored)
        }
    }

    @Test
    fun malformedCandidateOrStaleCapturedParentNeverTouchesPublicationStorage() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val message = f.message()
        failed(f.issue(message.copy(body = "x".repeat(401))), FailureKind.Validation)
        val old = f.scope()
        f.replaceFamily()
        failed(f.issuer(old, message), FailureKind.Unauthorized)
        assertEquals(0, f.reads)
        assertEquals(0, f.seals)
        assertEquals(0, f.writes)
    }

    private fun ready(result: AppResult<CollaborationNotificationIssuance>) =
        assertIs<CollaborationNotificationIssuance.Ready>(
                assertIs<AppResult.Success<CollaborationNotificationIssuance>>(result).value
            )
            .publication

    private fun failed(result: AppResult<*>, kind: FailureKind) =
        assertEquals(kind, assertIs<AppResult.Failed>(result).failure.kind)
}
