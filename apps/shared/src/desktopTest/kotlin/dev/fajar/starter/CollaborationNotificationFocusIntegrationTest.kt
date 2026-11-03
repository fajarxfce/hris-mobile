package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.koin.dsl.module

/** Actual app graph/SQLite and fake OS; no production notification receiver is activated. */
class CollaborationNotificationFocusIntegrationTest {
    @Test
    fun focusedOriginalIsDurablyInactiveAndCannotAlertAfterBlurOrReopen() = runBlocking {
        withFixture { f ->
            val registration = f.focus()
            assertEquals(recorded(), f.publish(allowAlert = true))
            val inactive = assertNotNull(f.publication())
            assertEquals(CollaborationNotificationPublicationState.Cancelled, inactive.body.state)
            assertNull(inactive.body.message)
            assertNull(inactive.body.actions)
            assertEquals(0, f.native.attempts)
            registration.close()
            f.reopen()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                f.publish(allowAlert = true),
            )
            assertEquals(inactive, f.publication())
            assertEquals(0, f.native.attempts)
        }
    }

    @Test
    fun unrelatedFocusCannotSuppressAnotherParentAccountCompanyOrConversation() = runBlocking {
        for (change in 0..5) withFixture { f ->
            val actual = f.app.incomingGrant().owner
            val other =
                when (change) {
                    0 -> actual.copy(sessionId = "other-login")
                    1 -> actual.copy(audience = "https://other.example.test")
                    2 -> actual.copy(accountId = id())
                    3 -> actual.copy(nativeSessionId = id())
                    4 -> actual.copy(companyId = id())
                    else -> actual
                }
            val conversation = if (change == 5) id() else f.candidate.conversationId
            assertNotNull(
                f.app.koin.get<CollaborationNotificationFocus>().register(other, conversation)
            )
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                f.publish(allowAlert = true),
            )
            assertFalse(f.native.cards.values.single().silent)
        }
    }

    @Test
    fun privacyRerenderRemovesTheExactPreviousNativeCardBeforeInactiveSettlement() = runBlocking {
        withFixture { f ->
            f.publish(allowAlert = true)
            val old = f.native.cards.values.single()
            f.preferences(CollaborationNotificationPreview.Message)
            f.focus()
            f.native.onRemove = { reference ->
                assertEquals(old.reference, reference)
                val prepared = assertNotNull(f.publication())
                assertEquals(
                    CollaborationNotificationPublicationState.Prepared,
                    prepared.body.state,
                )
                assertNotEquals(reference.renderId, prepared.body.target.renderId)
            }
            assertEquals(recorded(), f.publish(allowAlert = true))
            assertEquals(listOf(old.reference), f.native.removed)
            assertTrue(f.native.cards.isEmpty())
            assertEquals(1, f.native.attempts)
        }
    }

    @Test
    fun focusEnteringDuringNativeShowWithholdsSuccessAndCompensatesTheExactCard() = runBlocking {
        withFixture { f ->
            f.native.onShow = { f.focus() }
            assertIs<AppResult.Failed>(f.publish(allowAlert = true))
            assertTrue(f.native.cards.isEmpty())
            assertEquals(
                CollaborationNotificationPublicationState.Prepared,
                f.publication()!!.body.state,
            )
            f.native.onShow = {}
            assertEquals(recorded(), f.publish(allowAlert = true))
            assertEquals(1, f.native.attempts)
        }
    }

    @Test
    fun focusLeavingDuringRemovalRetainsTheActiveJournalWithoutAcknowledgingSuppression() =
        runBlocking {
            withFixture { f ->
                f.publish(allowAlert = true)
                val active = f.publication()
                val registration = f.focus()
                f.native.onRemove = { registration.close() }
                assertIs<AppResult.Failed>(f.publish())
                assertEquals(active, f.publication())
                f.native.onRemove = {}
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                    f.publish(allowAlert = true),
                )
                assertTrue(f.native.cards.values.single().silent)
            }
        }

    @Test
    fun lostInactiveWriteResponseRemainsRecoverableWithoutRevivingTheOriginal() = runBlocking {
        withFixture { f ->
            val actual = f.app.koin.get<CollaborationNotificationPublicationRepository>()
            val uncertain =
                object : CollaborationNotificationPublicationRepository by actual {
                    override suspend fun replace(
                        access: CollaborationRecoveryAccess,
                        expected: CollaborationNotificationPublication?,
                        body: CollaborationNotificationPublicationBody,
                    ): AppResult<CollaborationNotificationPublicationWrite> {
                        val result = actual.replace(access, expected, body)
                        if (body.state == CollaborationNotificationPublicationState.Cancelled) {
                            assertIs<AppResult.Success<*>>(result)
                            return AppResult.Failed(
                                Failure(FailureKind.Storage, "Fictional lost response")
                            )
                        }
                        return result
                    }
                }
            f.app.koin.loadModules(
                listOf(
                    module { single<CollaborationNotificationPublicationRepository> { uncertain } }
                )
            )
            f.focus()
            assertIs<AppResult.Failed>(f.publish())
            assertEquals(
                CollaborationNotificationPublicationState.Cancelled,
                f.publication()!!.body.state,
            )
            f.reopen()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                f.publish(allowAlert = true),
            )
            assertEquals(0, f.native.attempts)
        }
    }

    @Test
    fun replacedNativeRenderIsPreservedWithoutCommittingFocusedSuppression() = runBlocking {
        withFixture { f ->
            f.publish()
            val active = f.publication()
            val old = f.native.cards.values.single()
            val newer = old.copy(reference = old.reference.copy(renderId = id()))
            f.native.cards[old.reference.id] = newer
            f.focus()
            assertIs<AppResult.Failed>(f.publish())
            assertEquals(active, f.publication())
            assertEquals(newer, f.native.cards.values.single())
        }
    }

    @Test
    fun cancellationJoinsNativeRemovalAndLeavesTheJournalForAnOwnedRetry() = runBlocking {
        withFixture { f ->
            f.publish()
            val active = f.publication()
            f.focus()
            val entered = CompletableDeferred<Unit>()
            var joined = false
            f.native.onRemove = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val job = launch { f.publish() }
            select {
                entered.onAwait {}
                job.onJoin { fail("Native removal did not start") }
            }
            job.cancelAndJoin()
            assertTrue(joined)
            assertEquals(active, f.publication())
            f.native.onRemove = {}
            assertEquals(recorded(), f.publish())
        }
    }

    private fun NotificationPublisherFixture.focus() =
        assertNotNull(
            app.koin
                .get<CollaborationNotificationFocus>()
                .register(app.incomingGrant().owner, candidate.conversationId)
        )

    private fun recorded() =
        AppResult.Success(CollaborationNotificationPublicationResult.SuppressionRecorded)

    private fun id() = UUID.randomUUID().toString()

    private suspend fun withFixture(block: suspend (NotificationPublisherFixture) -> Unit) {
        val f = NotificationPublisherFixture()
        try {
            f.initialize()
            block(f)
        } finally {
            f.close()
        }
    }
}
