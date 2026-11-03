package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationNotificationPublications
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CollaborationNotificationPointReadTest {
    @Test
    fun exactPointReadExposesOnlyOwnedReferenceAndKeepsInactiveWatermark() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = issue(f)
        val reference =
            assertIs<AppResult.Success<CollaborationNotificationPublicationReference?>>(
                    reader(f)(f.parent, original.body.target)
                )
                .value!!
        assertEquals(original.id, reference.id)
        assertEquals(original.version, reference.version)
        assertEquals(original.body.target, reference.target)
        assertEquals(1, f.writes)
        assertIs<AppResult.Success<*>>(
            f.finisher(original, CollaborationNotificationPublicationState.Cancelled)
        )
        val inactive =
            assertIs<AppResult.Success<CollaborationNotificationPublicationReference?>>(
                    reader(f)(f.parent, original.body.target)
                )
                .value!!
        assertEquals(CollaborationNotificationPublicationState.Cancelled, inactive.state)
        assertEquals(2, f.writes)
    }

    @Test
    fun absentAndSupersededTargetsDoNotReturnAnotherRender() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = issue(f)
        val changed =
            original.body.target.copy(renderId = CollaborationNotificationIssuanceFixture.id(9876))
        assertEquals(AppResult.Success(null), reader(f)(f.parent, changed))
        f.stored = null
        assertEquals(AppResult.Success(null), reader(f)(f.parent, original.body.target))
        assertEquals(1, f.writes)
    }

    @Test
    fun wrongOwnerOrMalformedJournalNeverReturnsAReference() = runTest {
        for (foreign in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = issue(f)
            f.stored =
                if (foreign)
                    original.copy(
                        body =
                            original.body.copy(
                                parent =
                                    original.body.parent.copy(
                                        accountId =
                                            CollaborationNotificationIssuanceFixture.id(9876)
                                    )
                            )
                    )
                else original.copy(id = "invalid")
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(reader(f)(f.parent, original.body.target)).failure.kind,
            )
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun replacedParentWinsOverLateStorageFailure() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = issue(f)
        val parent = f.parent
        val readsBefore = f.reads
        f.afterRead = { f.replaceFamily() }
        f.readFailure = Failure(FailureKind.Storage, "Storage unavailable")
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(reader(f)(parent, original.body.target)).failure.kind,
        )
        assertEquals(readsBefore + 1, f.reads)
        assertEquals(1, f.writes)
    }

    @Test
    fun cancellationJoinsPendingReadAndTimeoutNeverReturnsALateReference() = runTest {
        for (timeout in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = issue(f)
            val entered = CompletableDeferred<Unit>()
            var joined = false
            f.afterRead = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val pending = async { reader(f)(f.parent, original.body.target) }
            entered.await()
            if (timeout) {
                advanceTimeBy(8_001)
                assertEquals(
                    FailureKind.Timeout,
                    assertIs<AppResult.Failed>(pending.await()).failure.kind,
                )
            } else pending.cancelAndJoin()
            assertTrue(joined)
            assertEquals(1, f.writes)
        }
    }

    private fun reader(f: CollaborationNotificationIssuanceFixture) =
        ReadCollaborationNotificationPublications(
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
