package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.koin.dsl.module

class CollaborationNotificationPublisherIntegrationTest {
    @Test
    fun exactEncryptedPublicationPrecedesNativeSubmissionAndReplayIsSilentAfterReopen() =
        runBlocking {
            withFixture { f ->
                f.preferences(CollaborationNotificationPreview.Message)
                f.native.onShow = { payload ->
                    val stored = f.publication()!!
                    assertEquals(
                        CollaborationNotificationPublicationState.Prepared,
                        stored.body.state,
                    )
                    assertEquals(stored.body.target.renderId, payload.reference.renderId)
                    assertEquals(
                        stored.body.actions!!.reply.encoded,
                        payload.envelopes[ActionNotificationKind.Reply],
                    )
                    assertEquals(
                        payload.envelopes[ActionNotificationKind.Reply],
                        payload.envelopes[ActionNotificationKind.Open],
                    )
                    assertTrue(f.app.sdk.enqueued.isEmpty())
                }
                complete(f.publish(allowAlert = true))
                val first = f.native.cards.values.single()
                assertEquals("Fictional original for notification", first.body)
                assertFalse(first.silent)
                assertEquals(
                    CollaborationNotificationPublicationState.Published,
                    f.publication()!!.body.state,
                )
                f.native.onShow = {}
                f.reopen()
                complete(f.publish(allowAlert = true))
                val repeated = f.native.cards.values.single()
                assertEquals(first.copy(silent = true), repeated)
                assertTrue(f.app.sdk.enqueued.isEmpty())
            }
        }

    @Test
    fun privacyRerenderDropsNamesAndTextAndNeverRepeatsTheOriginalAlert() = runBlocking {
        withFixture { f ->
            f.preferences(CollaborationNotificationPreview.Message)
            complete(f.publish(allowAlert = true))
            val first = f.native.cards.values.single()
            f.preferences(CollaborationNotificationPreview.Hidden)
            complete(f.publish(allowAlert = true))
            val hidden = f.native.cards.values.single()
            assertEquals(first.reference.id, hidden.reference.id)
            assertNotEquals(first.reference.renderId, hidden.reference.renderId)
            assertEquals("HRIS", hidden.title)
            assertEquals("New message", hidden.body)
            assertTrue(hidden.silent)
            f.preferences(CollaborationNotificationPreview.SenderOnly)
            complete(f.publish(allowAlert = true))
            val sender = f.native.cards.values.single()
            assertEquals(first.title, sender.title)
            assertEquals("New message", sender.body)
            assertTrue(sender.silent)
        }
    }

    @Test
    fun recoveryDefaultsSilentEvenForANewOriginalAndPermissionFailureKeepsPrepared() = runBlocking {
        withFixture { f ->
            for (outcome in
                listOf(
                    ActionNotificationSubmission.PermissionRequired,
                    ActionNotificationSubmission.ChannelDisabled,
                    ActionNotificationSubmission.CapacityReached,
                )) {
                f.native.submission = outcome
                assertEquals(
                    outcome.name,
                    assertIs<AppResult.Success<CollaborationNotificationPublicationResult>>(
                            f.publish()
                        )
                        .value
                        .name,
                )
                assertEquals(
                    CollaborationNotificationPublicationState.Prepared,
                    f.publication()!!.body.state,
                )
                assertTrue(f.native.cards.isEmpty())
            }
            f.native.submission = ActionNotificationSubmission.Submitted
            complete(f.publish())
            assertTrue(f.native.cards.values.single().silent)
        }
    }

    @Test
    fun uncertainNativeAcknowledgmentCompensatesAndKeepsOriginalEnvelopesForSilentRetry() =
        runBlocking {
            withFixture { f ->
                f.native.onShow = { error("Fictional lost Binder response") }
                assertIs<AppResult.Failed>(f.publish(allowAlert = true))
                val prepared = f.publication()!!
                assertEquals(
                    CollaborationNotificationPublicationState.Prepared,
                    prepared.body.state,
                )
                assertTrue(f.native.cards.isEmpty())
                assertEquals(1, f.native.removed.size)
                f.native.onShow = {}
                complete(f.publish(allowAlert = true))
                val repeated = f.native.cards.values.single()
                assertEquals(prepared.body.target.renderId, repeated.reference.renderId)
                assertEquals(
                    prepared.body.actions!!.reply.encoded,
                    repeated.envelopes[ActionNotificationKind.Reply],
                )
                assertTrue(repeated.silent)
            }
        }

    @Test
    fun confirmedDenialDuringNativeSubmissionWithdrawsTheCardAndWithholdsAcknowledgment() =
        runBlocking {
            withFixture { f ->
                val denied =
                    Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
                f.native.onShow = {
                    assertIs<AppResult.Success<*>>(
                        f.app.koin
                            .get<CollaborationListRepository>()
                            .restrict(f.scope.access, denied)
                    )
                }
                val failure = assertIs<AppResult.Failed>(f.publish()).failure
                assertEquals(denied.kind, failure.kind)
                assertEquals(denied.code, failure.code)
                assertTrue(f.native.cards.isEmpty())
                assertEquals(
                    CollaborationNotificationPublicationState.Prepared,
                    f.publication()!!.body.state,
                )
                val attempts = f.native.attempts
                f.native.onShow = {}
                assertIs<AppResult.Failed>(f.publish())
                assertEquals(attempts, f.native.attempts)
            }
        }

    @Test
    fun privacyChangeDuringNativeSubmissionRemovesStalePreviewBeforeReturning() = runBlocking {
        withFixture { f ->
            f.preferences(CollaborationNotificationPreview.Message)
            f.native.onShow = { f.preferences(CollaborationNotificationPreview.Hidden) }
            assertIs<AppResult.Failed>(f.publish())
            assertTrue(f.native.cards.isEmpty())
            f.native.onShow = {}
            complete(f.publish())
            assertEquals("New message", f.native.cards.values.single().body)
        }
    }

    @Test
    fun callerCancellationAfterExternalSubmissionIsJoinedAndCompensated() = runBlocking {
        withFixture { f ->
            val submitted = CompletableDeferred<Unit>()
            f.native.onShow = {
                submitted.complete(Unit)
                awaitCancellation()
            }
            val request = launch { f.publish() }
            submitted.await()
            request.cancelAndJoin()
            assertTrue(f.native.cards.isEmpty())
            assertEquals(1, f.native.removed.size)
            assertEquals(
                CollaborationNotificationPublicationState.Prepared,
                f.publication()!!.body.state,
            )
        }
    }

    @Test
    fun parentReplacementWhileSubmissionIsPendingCannotPublishIntoNewOwner() = runBlocking {
        withFixture { f ->
            val submitted = CompletableDeferred<Unit>()
            f.native.onShow = {
                submitted.complete(Unit)
                awaitCancellation()
            }
            val request = async { f.publish() }
            submitted.await()
            f.parents.value = f.parent.copy(nativeSessionId = "replacement-family")
            assertIs<AppResult.Failed>(request.await())
            assertTrue(f.native.cards.isEmpty())
            assertEquals(
                CollaborationNotificationPublicationState.Prepared,
                f.publication()!!.body.state,
            )
        }
    }

    @Test
    fun lostJournalAcknowledgmentAfterCommitRemovesCardThenReusesPublishedRenderSilently() =
        runBlocking {
            withFixture { f ->
                val actual = f.app.koin.get<CollaborationNotificationPublicationRepository>()
                val uncertain =
                    object : CollaborationNotificationPublicationRepository by actual {
                        override suspend fun replace(
                            access: CollaborationRecoveryAccess,
                            expected: CollaborationNotificationPublication?,
                            body: CollaborationNotificationPublicationBody,
                        ): AppResult<CollaborationNotificationPublicationWrite> {
                            val stored = actual.replace(access, expected, body)
                            if (body.state == CollaborationNotificationPublicationState.Published) {
                                assertIs<AppResult.Success<*>>(stored)
                                return AppResult.Failed(
                                    Failure(FailureKind.Storage, "Lost committed response")
                                )
                            }
                            return stored
                        }
                    }
                f.app.koin.loadModules(
                    listOf(
                        module {
                            single<CollaborationNotificationPublicationRepository> { uncertain }
                        }
                    )
                )
                assertIs<AppResult.Failed>(f.publish(allowAlert = true))
                val retained = f.publication()!!
                assertEquals(
                    CollaborationNotificationPublicationState.Published,
                    retained.body.state,
                )
                assertTrue(f.native.cards.isEmpty())
                f.reopen()
                complete(f.publish(allowAlert = true))
                assertEquals(
                    retained.body.target.renderId,
                    f.native.cards.values.single().reference.renderId,
                )
                assertTrue(f.native.cards.values.single().silent)
            }
        }

    @Test
    fun suppressedOrMissingCandidateDoesNotCancelAnotherCardOrSendAnImplicitCommand() =
        runBlocking {
            withFixture { f ->
                complete(f.publish())
                val first = f.native.cards.values.single()
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.PendingHistory),
                    f.publish(selected = f.candidate.copy(eventId = "\$fictional-missing-event")),
                )
                f.preferences(
                    CollaborationNotificationPreview.Hidden,
                    CollaborationNotificationMute.Forever,
                )
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.Suppressed),
                    f.publish(),
                )
                assertEquals(first, f.native.cards.values.single())
                assertEquals(1, f.native.attempts)
                assertTrue(f.app.sdk.enqueued.isEmpty())
                // Ongoing active-card invalidation is a separate required host responsibility.
            }
        }

    private fun complete(result: AppResult<CollaborationNotificationPublicationResult>) {
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
            result,
        )
    }

    private suspend fun withFixture(block: suspend (NotificationPublisherFixture) -> Unit) {
        val f = NotificationPublisherFixture()
        try {
            f.initialize()
            withTimeout(30_000) { block(f) }
        } finally {
            f.close()
        }
    }
}
