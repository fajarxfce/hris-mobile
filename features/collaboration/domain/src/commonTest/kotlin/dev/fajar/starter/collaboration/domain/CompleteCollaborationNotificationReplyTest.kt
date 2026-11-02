package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CompleteCollaborationNotificationReplyTest {
    @Test
    fun exactDurableAndArchivedStatesRetireInputWithoutHistoryOrLiveCredentials() = runTest {
        val device = CollaborationNotificationReplyCompletionFixture().device()
        for (state in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Cancelled,
                CollaborationMessageState.Staging(device, emptySet()),
                CollaborationMessageState.Staged(device, "transaction"),
                CollaborationMessageState.Sent(device, "transaction", "\$sent"),
            )) {
            val f = CollaborationNotificationReplyCompletionFixture()
            f.seed(state)
            f.native.read.base.facts.clear()
            f.local.setup.now += 365.days
            f.local.setup.workspace = null
            f.local.setup.beforeWorkspace = { error("No selected workspace") }
            assertEquals(
                AppResult.Success(CollaborationNotificationReplyCompletion.Retired(state)),
                f.complete(),
            )
            assertNull(f.saved)
            assertEquals(1, f.removals)
            assertEquals(0, f.local.insertions)
            assertEquals(0, f.local.setup.calls)
            assertEquals(0, f.native.opens)
            assertEquals(0, f.native.read.base.factReads)
            assertTrue(f.local.scheduledKeys.isEmpty())
            assertEquals(state, f.local.records.value.single().state)
        }
    }

    @Test
    fun absentInputAndMissingCommandNeverClaimAStoredOrSentMessage() = runTest {
        val f = CollaborationNotificationReplyCompletionFixture()
        assertEquals(
            AppResult.Success(CollaborationNotificationReplyCompletion.Pending),
            f.complete(),
        )
        assertEquals(f.expected, f.saved)
        f.saved = null
        assertEquals(
            AppResult.Success(CollaborationNotificationReplyCompletion.Absent),
            f.complete(),
        )
        assertEquals(0, f.removals)
    }

    @Test
    fun allImmutableIdentityAndContentFieldsAndStateMustMatchBeforeRemoval() = runTest {
        val f = CollaborationNotificationReplyCompletionFixture()
        val c = f.command()
        for (changed in
            listOf(
                c.copy(intent = c.intent.copy(id = f.id(880))),
                c.copy(intent = c.intent.copy(owner = c.intent.owner.copy(accountId = f.id(880)))),
                c.copy(intent = c.intent.copy(owner = c.intent.owner.copy(companyId = f.id(880)))),
                c.copy(
                    intent =
                        c.intent.copy(
                            owner = c.intent.owner.copy(audience = "https://other.test/api")
                        )
                ),
                c.copy(intent = c.intent.copy(conversationId = f.id(880))),
                c.copy(intent = c.intent.copy(text = "Changed reply")),
                c.copy(revision = -1),
                c.copy(state = CollaborationMessageState.Sent(f.device(), "transaction", "invalid")),
            )) {
            f.messageResult = AppResult.Success(changed)
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.complete()).failure.kind,
            )
            assertEquals(f.expected, f.saved)
        }
        assertEquals(0, f.removals)
    }

    @Test
    fun changedReviewOrRecreatedInputCannotBeRemovedByAnOlderCallback() = runTest {
        for (duringRemove in listOf(false, true)) for (recreated in listOf(false, true)) {
            val f = CollaborationNotificationReplyCompletionFixture()
            f.seed()
            val changed =
                f.expected.copy(
                    version =
                        if (recreated) f.expected.version.copy(generation = f.id(880))
                        else f.expected.version.copy(revision = 1),
                    review = CollaborationNotificationReplyReview.MessageChanged,
                )
            if (duringRemove) f.beforeRemove = { f.saved = changed } else f.saved = changed
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.complete()).failure.kind,
            )
            assertEquals(changed, f.saved)
        }
    }

    @Test
    fun sameAccountReauthenticationRecognizesAnOldCommandWithoutReopeningItsToken() = runTest {
        val f = CollaborationNotificationReplyCompletionFixture()
        f.seed()
        f.native.replaceFamily()
        f.local.setup.session = f.local.setup.session!!.copy(id = "replacement-local-session")
        assertIs<CollaborationNotificationReplyCompletion.Retired>(
            assertIs<AppResult.Success<*>>(f.complete()).value
        )
        assertNull(f.saved)
        assertEquals(0, f.native.opens)
    }

    @Test
    fun invalidInputAndAbsentOrDifferentAccountAreRejectedBeforePrivateReads() = runTest {
        val f = CollaborationNotificationReplyCompletionFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.complete(f.expected.copy(id = "invalid"))).failure.kind,
        )
        assertIs<AppResult.Failed>(f.completer(AppPlatform.Web)(f.expected))
        val original = f.local.setup.session!!
        f.local.setup.session = original.copy(user = original.user.copy(id = f.id(880)))
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.complete()).failure.kind,
        )
        f.local.setup.session = null
        assertIs<AppResult.Failed>(f.complete())
        assertEquals(0, f.gets)
        assertEquals(0, f.removals)
    }

    @Test
    fun retainedDenialDuringEachPendingReadWinsIncludingStorageFailure() = runTest {
        for (stage in 0..2) {
            val f = CollaborationNotificationReplyCompletionFixture()
            f.seed()
            val deny: suspend () -> Unit = {
                f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            }
            when (stage) {
                0 -> {
                    f.afterGet = deny
                    f.inputFailure = f.local.storage
                }
                1 -> f.local.afterConversation = deny
                else -> {
                    f.afterMessage = deny
                    f.messageResult = AppResult.Failed(f.local.storage)
                }
            }
            assertEquals(AppResult.Failed(f.local.denied), f.complete())
            assertEquals(f.expected, f.saved)
            assertEquals(0, f.removals)
        }
    }

    @Test
    fun familyReplacementDuringEachPendingReadPreventsRemoval() = runTest {
        for (stage in 0..2) {
            val f = CollaborationNotificationReplyCompletionFixture()
            f.seed()
            when (stage) {
                0 -> f.afterGet = { f.native.replaceFamily() }
                1 -> f.local.afterConversation = { f.native.replaceFamily() }
                else -> f.afterMessage = { f.native.replaceFamily() }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.complete()).failure.kind,
            )
            assertEquals(f.expected, f.saved)
            assertEquals(0, f.removals)
        }
    }

    @Test
    fun unavailableConversationOrStorageNeverDeletesOrPermanentlyReviewsInput() = runTest {
        val f = CollaborationNotificationReplyCompletionFixture()
        f.seed()
        val summary = f.local.summary
        f.local.summary = null
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.complete()).failure.kind)
        f.local.summary = summary
        f.local.gateFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.complete())
        f.local.gateFailure = null
        f.messageResult = AppResult.Failed(f.local.storage)
        assertEquals(AppResult.Failed(f.local.storage), f.complete())
        assertEquals(f.expected, f.saved)
        assertEquals(0, f.removals)
    }

    @Test
    fun ownerLossOrDenialAfterRemovalWithholdsAcknowledgmentButKeepsTheMessage() = runTest {
        for (denied in listOf(false, true)) {
            val f = CollaborationNotificationReplyCompletionFixture()
            f.seed()
            f.afterRemove = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                else f.native.replaceFamily()
            }
            val result = assertIs<AppResult.Failed>(f.complete())
            if (denied) assertEquals(f.local.denied, result.failure)
            else assertEquals(FailureKind.Unauthorized, result.failure.kind)
            assertNull(f.saved)
            assertEquals(f.command(), f.local.records.value.single())
        }
    }

    @Test
    fun lostRemovalReplyIsNotReportedAsSuccessAndRetryOnlyReportsAbsence() = runTest {
        val f = CollaborationNotificationReplyCompletionFixture()
        f.seed()
        f.removeResult = AppResult.Failed(f.local.storage)
        assertEquals(AppResult.Failed(f.local.storage), f.complete())
        assertNull(f.saved)
        assertEquals(
            AppResult.Success(CollaborationNotificationReplyCompletion.Absent),
            f.complete(),
        )
        assertEquals(f.command(), f.local.records.value.single())
        assertEquals(1, f.removals)
    }

    @Test
    fun cancellationBeforeAndAfterCommitJoinsAndReleasesCompletionOwnership() = runTest {
        for (committed in listOf(false, true)) {
            val f = CollaborationNotificationReplyCompletionFixture()
            f.seed()
            val entered = CompletableDeferred<Unit>()
            var released = false
            val block: suspend () -> Unit = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
            if (committed) f.afterRemove = block else f.afterMessage = block
            val task = async { f.complete() }
            entered.await()
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.complete()).failure.kind,
            )
            task.cancelAndJoin()
            assertTrue(released)
            assertEquals(if (committed) null else f.expected, f.saved)
            f.afterRemove = {}
            f.afterMessage = {}
            val next =
                assertIs<AppResult.Success<CollaborationNotificationReplyCompletion>>(f.complete())
                    .value
            if (committed) assertEquals(CollaborationNotificationReplyCompletion.Absent, next)
            else assertIs<CollaborationNotificationReplyCompletion.Retired>(next)
        }
    }

    @Test
    fun deadlineAfterCommitKeepsDurableCommandAndReleasesOwnerBeforeRetry() = runTest {
        val f = CollaborationNotificationReplyCompletionFixture()
        f.seed()
        var released = false
        f.afterRemove = {
            try {
                delay(10_001)
            } finally {
                released = true
            }
        }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.complete()).failure.kind)
        assertTrue(released)
        assertNull(f.saved)
        assertEquals(f.command(), f.local.records.value.single())
        assertEquals(
            AppResult.Success(CollaborationNotificationReplyCompletion.Absent),
            f.complete(),
        )
    }
}
