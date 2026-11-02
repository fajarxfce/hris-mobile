package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationNotificationReplyTest {
    @Test
    fun sameAccountOldFamilyInputRemainsReadableWithMissingContextAndExpiredCredentials() =
        runTest {
            val f = CollaborationNotificationReplyReviewFixture()
            f.native.replaceFamily()
            f.local.setup.now += 365.days
            f.local.setup.workspace = null
            f.local.setup.beforeWorkspace = { error("No selection") }
            f.local.summary = null
            f.native.read.base.facts.clear()
            assertEquals(AppResult.Success(f.expected), f.read())
            assertEquals(0, f.local.messageReads)
            assertEquals(0, f.local.setup.calls)
            assertEquals(0, f.native.opens)
            assertEquals(0, f.holds)
        }

    @Test
    fun invalidIdentityOrUnsupportedPlatformNeverReadsPrivateInput() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.reader()("bad")).failure.kind,
        )
        assertIs<AppResult.Failed>(f.reader(AppPlatform.Web)(f.expected.id))
        f.local.setup.session = null
        assertIs<AppResult.Failed>(f.read())
        assertEquals(0, f.base.gets)
    }

    @Test
    fun wrongStoredIdentityOrOtherAccountPayloadIsNeverExposed() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        for (wrong in
            listOf(
                f.expected.copy(id = "bad"),
                f.expected.copy(
                    candidate =
                        f.expected.candidate.copy(
                            parent = f.expected.candidate.parent.copy(accountId = f.base.id(899))
                        )
                ),
            )) {
            f.base.saved = wrong
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
        }
    }

    @Test
    fun familyChangeDuringReadRejectsItsLatePayload() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        f.base.afterGet = { f.native.replaceFamily() }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.read()).failure.kind)
        assertEquals(f.expected, f.base.saved)
    }

    @Test
    fun retainedCompanyDenialAndFailedAdmissionReadWithholdTextWithoutDeletingIt() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        f.base.afterGet = {
            f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        }
        assertEquals(AppResult.Failed(f.local.denied), f.read())
        f.base.afterGet = {}
        f.local.gateFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.read())
        assertEquals(f.expected, f.base.saved)
        assertEquals(0, f.base.removals)
        assertEquals(0, f.holds)
    }

    @Test
    fun absenceStillRequiresStableCurrentOwnership() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        f.base.saved = null
        assertEquals(AppResult.Success(null), f.read())
        f.base.afterGet = { f.native.replaceFamily() }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.read()).failure.kind)
    }

    @Test
    fun timeoutAndCancellationJoinPendingReadsAndLeaveInputUntouched() = runTest {
        val f = CollaborationNotificationReplyReviewFixture()
        var released = false
        f.base.afterGet = {
            try {
                delay(10_001)
            } finally {
                released = true
            }
        }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read()).failure.kind)
        assertTrue(released)
        val entered = CompletableDeferred<Unit>()
        released = false
        f.base.afterGet = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val pending = async { f.read() }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(released)
        assertEquals(f.expected, f.base.saved)
        f.base.afterGet = {}
        assertEquals(AppResult.Success(f.expected), f.read())
    }
}
