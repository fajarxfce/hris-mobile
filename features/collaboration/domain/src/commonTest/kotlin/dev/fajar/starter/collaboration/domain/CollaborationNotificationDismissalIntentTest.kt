package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.cleanupReference
import dev.fajar.starter.collaboration.domain.usecases.CancelCollaborationNotificationPublication
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationDismissalIntentTest {
    @Test
    fun dismissalIsStoredBeforeCleanupAndNeverReissuedAsAVisibleOriginal() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = issue(f)
        val cancel = canceller(f)
        assertIs<AppResult.Success<*>>(
            cancel.requestDismissal(f.parent, original.cleanupReference())
        )
        val pending = f.stored!!
        assertEquals(CollaborationNotificationPublicationState.Dismissing, pending.body.state)
        assertNull(pending.body.message)
        assertNull(pending.body.actions)
        assertEquals(AppResult.Success(CollaborationNotificationIssuance.Unchanged), f.issue())
        assertIs<AppResult.Success<*>>(cancel(f.parent, pending.cleanupReference()))
        val completed = f.stored!!
        assertEquals(CollaborationNotificationPublicationState.Dismissed, completed.body.state)
        assertIs<AppResult.Success<*>>(
            cancel.requestDismissal(f.parent, completed.cleanupReference())
        )
        assertEquals(completed, f.stored)
        assertEquals(3, f.writes)
    }

    @Test
    fun responseLossRetainsCleanupIntentAcrossNativeFamilyReplacement() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = issue(f)
        val cancel = canceller(f)
        f.writeFailure = Failure(FailureKind.Storage, "Response lost")
        assertIs<AppResult.Failed>(cancel.requestDismissal(f.parent, original.cleanupReference()))
        val pending = f.stored!!
        assertEquals(CollaborationNotificationPublicationState.Dismissing, pending.body.state)
        f.writeFailure = null
        f.replaceFamily()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(
                    cancel.requestDismissal(f.parent, pending.cleanupReference())
                )
                .failure
                .kind,
        )
        assertIs<AppResult.Success<*>>(cancel(f.parent, pending.cleanupReference()))
        assertEquals(CollaborationNotificationPublicationState.Dismissed, f.stored!!.body.state)
    }

    @Test
    fun cancelledAcknowledgmentJoinsItsWriteAndLeavesRecoverableExactIntent() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = issue(f)
        val cancel = canceller(f)
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.afterWrite = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val pending = async { cancel.requestDismissal(f.parent, original.cleanupReference()) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(joined)
        val retained = f.stored!!
        assertEquals(CollaborationNotificationPublicationState.Dismissing, retained.body.state)
        f.afterWrite = {}
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
            cancel(f.parent, original.cleanupReference()),
        )
        assertIs<AppResult.Success<*>>(cancel(f.parent, retained.cleanupReference()))
        assertEquals(CollaborationNotificationPublicationState.Dismissed, f.stored!!.body.state)
    }

    private fun canceller(f: CollaborationNotificationIssuanceFixture) =
        CancelCollaborationNotificationPublication(
            f.journal,
            f.local.setup.sessions,
            f.local.server,
            f.local.build,
        )

    private suspend fun issue(f: CollaborationNotificationIssuanceFixture) =
        assertIs<CollaborationNotificationIssuance.Ready>(
                assertIs<AppResult.Success<*>>(f.issue()).value
            )
            .publication
}
