package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReviewCollaborationNotificationReplyTest {
    @Test
    fun currentValidContextOrMatchingCommandDoesNotJustifyHoldingInput() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        assertEquals(AppResult.Success(null), f.review())
        f.base.seed()
        f.native.replaceFamily()
        f.local.summary = null
        f.native.read.base.facts.clear()
        assertEquals(AppResult.Success(null), f.review())
        assertEquals(0, f.holds)
        assertEquals(f.expected, f.base.saved)
    }

    @Test
    fun immutableDifferentCommandIsHeldWithExactOriginalTextAndToken() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        f.conflict()
        val held =
            assertIs<AppResult.Success<CollaborationNotificationReplyInput?>>(f.review()).value!!
        assertEquals(CollaborationNotificationReplyReview.MessageChanged, held.review)
        assertEquals(f.expected.candidate, held.candidate)
        assertEquals(f.expected.sequence, held.sequence)
        assertTrue(held.version.revision > f.expected.version.revision)
        assertEquals(0, f.local.insertions)
        assertEquals(0, f.base.removals)
    }

    @Test
    fun oldNativeOrLocalFamilyRequiresReviewWithoutMakingItsTokenCurrent() = runTest {
        for (remote in listOf(false, true)) {
            val f = CollaborationNotificationReplyReviewFixture()
            if (remote) f.native.replaceFamily()
            else f.local.setup.session = f.local.setup.session!!.copy(id = "new-local")
            val held =
                assertIs<AppResult.Success<CollaborationNotificationReplyInput?>>(f.review())
                    .value!!
            assertEquals(CollaborationNotificationReplyReview.SessionChanged, held.review)
            assertEquals(f.expected.candidate.parent, held.candidate.parent)
            assertEquals(0, f.native.opens)
        }
    }

    @Test
    fun missingConversationOrOriginalRequiresContextReviewWhileTombstoneStillAllowsReply() =
        runTest {
            for (conversation in listOf(false, true)) {
                val f = CollaborationNotificationReplyReviewFixture()
                if (conversation) f.local.summary = null else f.native.read.base.facts.clear()
                assertEquals(
                    CollaborationNotificationReplyReview.ContextUnavailable,
                    assertIs<AppResult.Success<CollaborationNotificationReplyInput?>>(f.review())
                        .value!!
                        .review,
                )
            }
            val f = CollaborationNotificationReplyReviewFixture()
            val id = f.native.target.eventId
            f.native.read.base.facts[id] =
                f.native.read.base.facts.getValue(id).copy(redacted = true, timestamp = null)
            assertEquals(AppResult.Success(null), f.review())
            assertEquals(0, f.holds)
        }

    @Test
    fun alreadyHeldInputNeverSilentlyClearsItsReviewOrDispatchesAgain() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        f.conflict()
        val held =
            assertIs<AppResult.Success<CollaborationNotificationReplyInput?>>(f.review()).value!!
        f.base.messageResult = null
        assertEquals(AppResult.Success(held), f.review(held))
        assertEquals(1, f.holds)
        assertEquals(0, f.local.insertions)
    }

    @Test
    fun malformedOrCrossOwnerCommandIsAReadFailureNotPermanentReview() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        val command = f.base.command()
        for (wrong in
            listOf(
                command.copy(revision = -1),
                command.copy(intent = command.intent.copy(id = f.base.id(888))),
                command.copy(
                    intent =
                        command.intent.copy(
                            owner = command.intent.owner.copy(accountId = f.base.id(888))
                        )
                ),
                command.copy(
                    state =
                        CollaborationMessageState.Sent(f.base.device(), "transaction", "invalid")
                ),
            )) {
            f.base.messageResult = AppResult.Success(wrong)
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.review()).failure.kind)
        }
        assertEquals(0, f.holds)
    }

    @Test
    fun invalidInputOrReplacementAccountNeverGetsOrHoldsPrivateInput() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.review(f.expected.copy(id = "bad"))).failure.kind,
        )
        assertIs<AppResult.Failed>(f.makeReviewer(AppPlatform.Web)(f.expected))
        val session = f.local.setup.session!!
        f.local.setup.session = session.copy(user = session.user.copy(id = f.base.id(888)))
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.review()).failure.kind)
        assertEquals(0, f.base.gets)
        assertEquals(0, f.holds)
    }

    @Test
    fun denialOrFamilyChangeAroundPendingFactsPreventsPermanentHold() = runTest {
        for (denied in listOf(false, true)) for (stage in 0..3) {
            val f = CollaborationNotificationReplyReviewFixture()
            val change: suspend () -> Unit = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                else f.native.replaceFamily()
            }
            when (stage) {
                0 -> f.base.afterGet = change
                1 -> f.base.afterMessage = change
                2 -> f.local.afterConversation = change
                else -> f.native.read.base.afterFact = change
            }
            val result = assertIs<AppResult.Failed>(f.review())
            if (denied) assertEquals(f.local.denied, result.failure)
            else assertEquals(FailureKind.Unauthorized, result.failure.kind)
            assertEquals(0, f.holds)
            assertEquals(f.expected, f.base.saved)
        }
    }

    @Test
    fun transientInputCommandAndHistoryFailuresNeverBecomePermanentReview() = runTest {
        for (stage in 0..2) {
            val f = CollaborationNotificationReplyReviewFixture()
            when (stage) {
                0 -> f.base.inputFailure = f.local.storage
                1 -> f.base.messageResult = AppResult.Failed(f.local.storage)
                else -> f.native.read.base.factFailure = f.local.storage
            }
            assertEquals(AppResult.Failed(f.local.storage), f.review())
            assertEquals(0, f.holds)
        }
    }

    @Test
    fun concurrentReviewAndRecreatedInputArePreservedByExactVersionCas() = runTest {
        for (during in listOf(false, true)) {
            val f = CollaborationNotificationReplyReviewFixture()
            f.conflict()
            val replacement =
                f.expected.copy(version = f.expected.version.copy(generation = f.base.id(888)))
            if (during) f.beforeHold = { f.base.saved = replacement }
            else f.base.saved = replacement
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.review()).failure.kind)
            assertEquals(replacement, f.base.saved)
        }
    }

    @Test
    fun successfulHoldMustBeRereadExactlyAndNeverExposesRecreatedOrMissingInput() = runTest {
        for (missing in listOf(false, true)) {
            val f = CollaborationNotificationReplyReviewFixture()
            f.conflict()
            f.afterHold = {
                f.base.saved =
                    if (missing) null
                    else
                        f.expected.copy(
                            version = f.expected.version.copy(generation = f.base.id(888))
                        )
            }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.review()).failure.kind)
        }
    }

    @Test
    fun ownerLossOrStorageFailureAfterCommitKeepsHeldTextWithoutLateAcknowledgment() = runTest {
        for (replacement in listOf(false, true)) {
            val f = CollaborationNotificationReplyReviewFixture()
            f.conflict()
            if (replacement) f.afterHold = { f.native.replaceFamily() }
            else f.holdFailure = f.local.storage
            val result = assertIs<AppResult.Failed>(f.review())
            if (replacement) assertEquals(FailureKind.Unauthorized, result.failure.kind)
            else assertEquals(f.local.storage, result.failure)
            assertEquals(CollaborationNotificationReplyReview.MessageChanged, f.base.saved!!.review)
            assertEquals(f.expected.candidate, f.base.saved!!.candidate)
        }
    }

    @Test
    fun cancellationAndDeadlineJoinAfterHoldAndReleaseTheOwnerWithoutLosingInput() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        f.conflict()
        val entered = CompletableDeferred<Unit>()
        var released = false
        f.afterHold = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val pending = async { f.review() }
        entered.await()
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.review()).failure.kind)
        pending.cancelAndJoin()
        assertTrue(released)
        val held = f.base.saved!!
        f.afterHold = {}
        assertEquals(AppResult.Success(held), f.review(held))
        val timeout = CollaborationNotificationReplyReviewFixture()
        timeout.conflict()
        released = false
        timeout.afterHold = {
            try {
                delay(10_001)
            } finally {
                released = true
            }
        }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(timeout.review()).failure.kind)
        assertTrue(released)
        assertEquals(
            CollaborationNotificationReplyReview.MessageChanged,
            timeout.base.saved!!.review,
        )
    }
}
