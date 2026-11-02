package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RetainCollaborationNotificationReplyTest {
    @Test
    fun typedPrivateInputSurvivesMissingHistoryAndCompanyDenialWithoutGrantingASend() = runTest {
        val f = CollaborationNotificationReplyFixture()
        val local = f.native.local
        local.setup.now += 365.days
        local.setup.workspace = null
        local.summary = null
        f.native.read.base.facts.clear()
        local.gate.value = CollaborationListAdmission("denied", local.denied)
        local.beforeAdmission = { error("Private input retention is not a company read or send") }
        local.setup.beforeWorkspace = { error("No selected workspace") }
        val retained =
            assertIs<AppResult.Success<CollaborationNotificationReplyInput>>(f.retain()).value
        assertEquals(f.candidate(), retained.candidate)
        assertEquals(1, f.stores)
        assertEquals(0, f.native.read.base.factReads)
        assertEquals(0, local.insertions)
        assertEquals(0, local.setup.calls)
        assertTrue(local.scheduledKeys.isEmpty())
    }

    @Test
    fun onlyValidReplyMetadataAndBoundedNonblankInputCanBeRetained() = runTest {
        val f = CollaborationNotificationReplyFixture()
        for (text in listOf("", " ", "\u0000", "x".repeat(32_769))) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.retain(text)).failure.kind,
        )
        for (token in listOf("", "v2.invalid", "v1." + "x".repeat(4094))) assertIs<
            AppResult.Failed
        >(
            f.retain(CollaborationNotificationActionToken(token), "Reply")
        )
        assertEquals(0, f.native.opens)
        for (action in
            listOf(
                CollaborationNotificationAction.MarkRead(
                    f.native.action.operationId,
                    f.native.target,
                ),
                CollaborationNotificationAction.Dismiss(
                    f.native.action.operationId,
                    f.native.target,
                ),
                CollaborationNotificationAction.Mute(
                    f.native.action.operationId,
                    f.native.target,
                    null,
                    CollaborationNotificationPreview.Hidden,
                ),
            )) {
            f.native.action = action
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.retain()).failure.kind,
            )
        }
        assertEquals(0, f.stores)
    }

    @Test
    fun absentOrNonNativeOwnerNeverOpensTheTokenOrStoresInput() = runTest {
        val f = CollaborationNotificationReplyFixture()
        assertIs<AppResult.Failed>(f.retainer(AppPlatform.Web)(f.native.token, "Reply"))
        f.native.local.setup.session = null
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.retain()).failure.kind)
        assertEquals(0, f.native.opens)
        assertEquals(0, f.stores)
    }

    @Test
    fun familyOrAccountReplacementDuringDecodeRejectsBeforeStorage() = runTest {
        for (family in listOf(true, false)) {
            val f = CollaborationNotificationReplyFixture()
            f.native.afterOpen = {
                if (family) f.native.replaceFamily()
                else {
                    val session = f.native.local.setup.session!!
                    f.native.local.setup.session =
                        session.copy(
                            user = session.user.copy(id = CollaborationReadStateFixture.id(999))
                        )
                }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.retain()).failure.kind,
            )
            assertEquals(0, f.stores)
        }
    }

    @Test
    fun ownerLossAfterCommittedInputWithholdsAcknowledgmentWithoutErasingText() = runTest {
        val f = CollaborationNotificationReplyFixture()
        f.afterStore = { f.native.replaceFamily() }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.retain()).failure.kind)
        assertEquals(f.candidate(), f.saved!!.candidate)
        assertEquals(1, f.stores)
    }

    @Test
    fun capacityContentionAndStorageFailuresRemainExplicitAndNeverStartSend() = runTest {
        val f = CollaborationNotificationReplyFixture()
        f.forced = AppResult.Success(CollaborationNotificationReplyRetention.CapacityExceeded)
        assertEquals(
            "client.collaboration_notification_reply_capacity",
            assertIs<AppResult.Failed>(f.retain()).failure.code,
        )
        f.forced = AppResult.Success(CollaborationNotificationReplyRetention.Contended)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.retain()).failure.kind)
        f.forced = AppResult.Failed(f.native.local.storage)
        assertEquals(AppResult.Failed(f.native.local.storage), f.retain())
        assertNull(f.saved)
        assertEquals(0, f.native.local.insertions)
    }

    @Test
    fun recoveredInputPreservesTheOriginalTokenAndExistingReviewState() = runTest {
        val f = CollaborationNotificationReplyFixture()
        val original =
            f.input(
                    f.candidate()
                        .copy(token = CollaborationNotificationActionToken("v1.original-token"))
                )
                .copy(review = CollaborationNotificationReplyReview.MessageChanged)
        f.forced = AppResult.Success(CollaborationNotificationReplyRetention.Retained(original))
        assertEquals(AppResult.Success(original), f.retain())
        assertEquals(0, f.native.local.insertions)
    }

    @Test
    fun malformedOrDifferentStoredInputIsNeverAcknowledgedAsThisReply() = runTest {
        val f = CollaborationNotificationReplyFixture()
        for (wrong in
            listOf(
                f.input().copy(id = "bad"),
                f.input(f.candidate("Different text")),
                f.input(
                    f.candidate()
                        .copy(
                            parent =
                                f.native.parent.copy(
                                    nativeSessionId = CollaborationReadStateFixture.id(999)
                                )
                        )
                ),
            )) {
            f.forced = AppResult.Success(CollaborationNotificationReplyRetention.Retained(wrong))
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.retain()).failure.kind)
        }
    }

    @Test
    fun cancellationAndDeadlineJoinACommittedStorageCallbackAndLeaveRecoverableInput() = runTest {
        for (timeout in listOf(false, true)) {
            val f = CollaborationNotificationReplyFixture()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            var acknowledged = false
            f.afterStore = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            if (timeout)
                assertEquals(
                    FailureKind.Unavailable,
                    assertIs<AppResult.Failed>(f.retain()).failure.kind,
                )
            else {
                val job = launch {
                    f.retain()
                    acknowledged = true
                }
                entered.await()
                job.cancelAndJoin()
            }
            assertTrue(stopped)
            assertFalse(acknowledged)
            assertEquals(f.candidate(), f.saved!!.candidate)
            f.afterStore = {}
            assertIs<AppResult.Success<*>>(f.retain())
        }
    }

    @Test
    fun overlappingRetentionIsNotQueuedBehindAnUncertainCallback() = runTest {
        val f = CollaborationNotificationReplyFixture()
        val entered = CompletableDeferred<Unit>()
        f.afterStore = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.retain() }
        entered.await()
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.retain()).failure.kind)
        assertEquals(1, f.stores)
        job.cancelAndJoin()
        f.afterStore = {}
        assertIs<AppResult.Success<*>>(f.retain())
    }
}
