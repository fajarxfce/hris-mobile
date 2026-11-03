package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

class TransferCollaborationNotificationInputTest {
    @Test
    fun actualAuthenticatedAccountCopyIsReadBeforeDeviceInputIsRemovedWithoutCompanyAdmission() =
        runTest {
            val f = NotificationInputTransferFixture()
            f.existing.native.local.beforeAdmission = {
                error("Copying input does not grant company access")
            }
            f.existing.native.local.setup.beforeWorkspace = { error("No selected workspace") }
            f.beforeRemove = {
                assertEquals(1, f.copiesRead)
                assertNotNull(f.existing.saved)
            }
            val result =
                assertIs<AppResult.Success<CollaborationNotificationInputTransfer>>(
                        f.transfer()(f.reference)
                    )
                    .value
            assertEquals(
                CollaborationNotificationInputTransfer.Transferred(f.existing.saved!!.id),
                result,
            )
            assertNull(f.protected)
            assertEquals(f.original.reply.text, f.existing.saved!!.candidate.text)
            assertEquals(1, f.removals)
            assertEquals(0, f.existing.native.local.insertions)
            assertEquals(0, f.existing.native.local.setup.calls)
        }

    @Test
    fun absentOrNonNativeSessionDoesNotDecryptUnverifiedInput() = runTest {
        val f = NotificationInputTransferFixture()
        assertIs<AppResult.Failed>(f.transfer(AppPlatform.Web)(f.reference))
        f.existing.native.local.setup.session = null
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.transfer()(f.reference)).failure.kind,
        )
        assertEquals(0, f.reads)
        assertEquals(0, f.existing.native.opens)
        assertEquals(f.original, f.protected)
    }

    @Test
    fun missingInputIsDistinctFromAProvenTransferAndMalformedSnapshotsCannotOpenTokens() = runTest {
        val f = NotificationInputTransferFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.transfer()(f.reference.copy(id = "bad"))).failure.kind,
        )
        f.protected = null
        assertEquals(
            AppResult.Success(CollaborationNotificationInputTransfer.Absent),
            f.transfer()(f.reference),
        )
        for (input in
            listOf(
                f.original.copy(reference = f.reference.copy(generation = "invalid")),
                f.original.copy(reply = f.original.reply.copy(text = "")),
            )) {
            f.protected = input
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.transfer()(f.reference)).failure.kind,
            )
        }
        assertEquals(0, f.existing.native.opens)
        assertEquals(0, f.removals)
    }

    @Test
    fun wrongAccountAudienceKindOrFailedAuthenticationNeverCopiesOrDeletesInput() = runTest {
        for (change in 0..3) {
            val f = NotificationInputTransferFixture()
            when (change) {
                0 ->
                    f.existing.native.issuingParent =
                        f.existing.native.parent.copy(
                            accountId = CollaborationReadStateFixture.id(1230)
                        )
                1 ->
                    f.existing.native.issuingParent =
                        f.existing.native.parent.copy(audience = "https://foreign.example.test/")
                2 ->
                    f.existing.native.action =
                        CollaborationNotificationAction.Dismiss(
                            f.existing.native.action.operationId,
                            f.existing.native.target,
                        )
                else -> f.existing.native.openFailure = f.existing.native.local.storage
            }
            assertIs<AppResult.Failed>(f.transfer()(f.reference))
            assertEquals(0, f.existing.stores)
            assertEquals(0, f.removals)
            assertEquals(f.original, f.protected)
        }
    }

    @Test
    fun sameAccountFamilyReplacementPreservesTheIssuingFamilyForIndependentReview() = runTest {
        for (native in listOf(false, true)) {
            val f = NotificationInputTransferFixture()
            if (native) f.existing.native.replaceFamily()
            else
                f.existing.native.local.setup.session =
                    f.existing.native.local.setup.session!!.copy(id = "replacement")
            assertIs<AppResult.Success<CollaborationNotificationInputTransfer>>(
                f.transfer()(f.reference)
            )
            assertEquals(f.existing.native.parent, f.existing.saved!!.candidate.parent)
            assertEquals(0, f.existing.native.local.insertions)
            assertNull(f.protected)
        }
    }

    @Test
    fun ownerReplacementAfterEachPendingBoundaryWithholdsSuccessAndPreservesAtLeastOneCopy() =
        runTest {
            for (boundary in 0..4) {
                val f = NotificationInputTransferFixture()
                val changed: suspend () -> Unit = { f.existing.native.replaceFamily() }
                when (boundary) {
                    0 -> f.afterRead = changed
                    1 -> f.existing.native.afterOpen = changed
                    2 -> f.existing.afterStore = changed
                    3 -> f.afterCopyRead = changed
                    else -> f.afterRemove = changed
                }
                assertEquals(
                    FailureKind.Unauthorized,
                    assertIs<AppResult.Failed>(f.transfer()(f.reference)).failure.kind,
                )
                if (boundary < 4) assertEquals(f.original, f.protected)
                else {
                    assertNull(f.protected)
                    assertNotNull(f.existing.saved)
                }
                if (boundary < 2) assertNull(f.existing.saved)
                assertEquals(0, f.existing.native.local.insertions)
            }
        }

    @Test
    fun missingChangedOrUnreadableAccountCopyCannotAuthorizeDeviceCleanup() = runTest {
        for (variant in 0..2) {
            val f = NotificationInputTransferFixture()
            f.existing.afterStore = {
                f.forcedCopy =
                    when (variant) {
                        0 -> AppResult.Success(null)
                        1 ->
                            AppResult.Success(
                                f.existing.saved!!.copy(
                                    review = CollaborationNotificationReplyReview.SessionChanged
                                )
                            )
                        else -> AppResult.Failed(f.existing.native.local.storage)
                    }
            }
            assertIs<AppResult.Failed>(f.transfer()(f.reference))
            assertEquals(0, f.removals)
            assertEquals(f.original, f.protected)
            assertNotNull(f.existing.saved)
        }
    }

    @Test
    fun changedDeviceInputOrUncertainRemovalNeverDeletesAReplacement() = runTest {
        val f = NotificationInputTransferFixture()
        val replacement =
            f.original.copy(
                reference = f.reference.copy(generation = CollaborationReadStateFixture.id(1240))
            )
        f.beforeRemove = { f.protected = replacement }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.transfer()(f.reference)).failure.kind,
        )
        assertEquals(replacement, f.protected)
        assertNotNull(f.existing.saved)
        val unavailable = NotificationInputTransferFixture()
        unavailable.forcedRemove = AppResult.Failed(unavailable.existing.native.local.storage)
        assertIs<AppResult.Failed>(unavailable.transfer()(unavailable.reference))
        assertEquals(unavailable.original, unavailable.protected)
    }

    @Test
    fun lostCopyResponseCanReplayAndLostCleanupResponseLeavesTheAccountCopy() = runTest {
        val f = NotificationInputTransferFixture()
        f.copyOutcomeFailure = f.existing.native.local.storage
        assertIs<AppResult.Failed>(f.transfer()(f.reference))
        assertEquals(f.original, f.protected)
        val copy = f.existing.saved
        assertNotNull(copy)
        f.copyOutcomeFailure = null
        f.afterRemovalFailure = f.existing.native.local.storage
        assertIs<AppResult.Failed>(f.transfer()(f.reference))
        assertNull(f.protected)
        assertEquals(copy, f.existing.saved)
        f.afterRemovalFailure = null
        assertEquals(
            AppResult.Success(CollaborationNotificationInputTransfer.Absent),
            f.transfer()(f.reference),
        )
        assertEquals(0, f.existing.native.local.insertions)
    }

    @Test
    fun cancellationJoinsPendingCopyVerificationAndBusyCallNeverStartsASecondTransfer() = runTest {
        val f = NotificationInputTransferFixture()
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.afterCopyRead = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val transfer = f.transfer()
        coroutineScope {
            val job = launch { transfer(f.reference) }
            select {
                entered.onAwait {}
                job.onJoin { error("Transfer exited before verification barrier") }
            }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(transfer(f.reference)).failure.kind,
            )
            assertEquals(1, f.reads)
            job.cancelAndJoin()
        }
        assertTrue(joined)
        assertEquals(f.original, f.protected)
        assertNotNull(f.existing.saved)
        assertEquals(0, f.removals)
        f.afterCopyRead = {}
        assertIs<AppResult.Success<CollaborationNotificationInputTransfer>>(transfer(f.reference))
    }

    @Test
    fun finiteTransferDeadlineJoinsThePendingReadAndAllowsExplicitRetry() = runTest {
        val f = NotificationInputTransferFixture()
        var joined = false
        f.afterRead = {
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val transfer = f.transfer()
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(transfer(f.reference)).failure.kind,
        )
        assertTrue(joined)
        assertEquals(0, f.existing.native.opens)
        assertEquals(f.original, f.protected)
        f.afterRead = {}
        assertIs<AppResult.Success<CollaborationNotificationInputTransfer>>(transfer(f.reference))
    }
}
