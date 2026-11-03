package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/** Real Koin/encrypted SQLite with fictional acquisition and a fake native surface. */
class CollaborationNotificationReconciliationIntegrationTest {
    @Test
    fun aCompetingNativeTurnReturnsBusyWithoutSemanticCleanup() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            val journal = f.publication()
            val card = f.native.cards.values.single()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val work = launch {
                f.owner.withOwner(f.parent) {
                    entered.complete(Unit)
                    release.await()
                    AppResult.Success(Unit)
                }
            }
            try {
                withTimeout(2_000) { entered.await() }
                val attempts = f.native.attempts
                val removals = f.native.removed.size
                val result = assertIs<AppResult.Failed>(reconcile(f, before))
                assertEquals(NOTIFICATION_SURFACE_BUSY, result.failure.code)
                assertEquals(attempts, f.native.attempts)
                assertEquals(removals, f.native.removed.size)
                assertEquals(journal, f.publication())
                assertEquals(card, f.native.cards.values.single())
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { work.cancelAndJoin() }
            }
        }
    }

    @Test
    fun privacyChangeSilentlyReplacesTheExactCurrentRender() = runBlocking {
        withFixture { f ->
            f.preferences(CollaborationNotificationPreview.Message)
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            f.preferences(CollaborationNotificationPreview.Hidden)
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Submitted),
                reconcile(f, before),
            )
            val after = reference(f)
            assertEquals(before.id, after.id)
            assertNotEquals(before.target.renderId, after.target.renderId)
            val card = f.native.cards.values.single()
            assertEquals(after.target.renderId, card.reference.renderId)
            assertEquals("New message", card.body)
            assertTrue(card.silent)
            assertNull(f.publication()!!.body.message!!.body)
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun muteAndPrivateReadRetireTheExactWatermarkAcrossReopen() = runBlocking {
        for (mute in listOf(false, true)) withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            if (mute)
                f.preferences(
                    CollaborationNotificationPreview.Hidden,
                    CollaborationNotificationMute.Forever,
                )
            else {
                val c = f.candidate
                assertIs<AppResult.Success<*>>(
                    f.app.koin.get<MarkCollaborationMessagesRead>()(
                        f.scope,
                        c.conversationId,
                        c.roomId,
                        listOf(c.eventId),
                        CollaborationReadReceiptIntent(
                            UUID.randomUUID().toString(),
                            c.eventId,
                            CollaborationReadReceiptVisibility.Private,
                        ),
                    )
                )
            }
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Retired),
                reconcile(f, before),
            )
            assertTrue(f.native.cards.isEmpty())
            val retired = f.publication()!!
            assertEquals(CollaborationNotificationPublicationState.Cancelled, retired.body.state)
            assertNull(retired.body.message)
            assertNull(retired.body.actions)
            f.reopen()
            assertEquals(retired, f.publication())
            assertTrue(references(f).references.isEmpty())
            if (mute) {
                f.preferences(CollaborationNotificationPreview.Hidden)
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                    f.publish(),
                )
                assertTrue(f.native.cards.isEmpty())
            }
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun staleAcknowledgmentVersionAndPrivacyReferenceLeaveTheCurrentCardUntouched() = runBlocking {
        for (privacy in listOf(false, true)) withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            var old = reference(f)
            if (privacy) {
                f.preferences(CollaborationNotificationPreview.Message)
                assertIs<AppResult.Success<*>>(f.publish())
            } else
                old =
                    old.copy(
                        version = old.version.copy(revision = old.version.revision - 1),
                        state = CollaborationNotificationPublicationState.Prepared,
                    )
            val current = f.publication()
            val card = f.native.cards.values.single()
            val submissions = f.native.attempts
            val removals = f.native.removed.size
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Contended),
                reconcile(f, old),
            )
            assertEquals(current, f.publication())
            assertEquals(card, f.native.cards.values.single())
            assertEquals(submissions, f.native.attempts)
            assertEquals(removals, f.native.removed.size)
        }
    }

    @Test
    fun failedObservationHidesWithoutRepublishingAndExplicitRecoveryStaysSilent() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            val journal = f.publication()
            val failed = Failure(FailureKind.Storage, "Fictional unavailable observer")
            val attempts = f.native.attempts
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Hidden),
                reconcile(f, before, failed),
            )
            assertEquals(attempts, f.native.attempts)
            assertTrue(f.native.cards.isEmpty())
            assertEquals(journal, f.publication())
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Submitted),
                reconcile(f, before),
            )
            assertEquals(before.target.renderId, f.native.cards.values.single().reference.renderId)
            assertTrue(f.native.cards.values.single().silent)
        }
    }

    @Test
    fun denialDuringSubmissionCompensatesThenAnExactRescanRetiresTheNewRecord() = runBlocking {
        withFixture { f ->
            f.preferences(CollaborationNotificationPreview.Message)
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            f.preferences(CollaborationNotificationPreview.Hidden)
            f.native.onShow = { restrict(f) }
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Contended),
                reconcile(f, before),
            )
            assertTrue(f.native.cards.isEmpty())
            val next = reference(f)
            assertNotEquals(before.target.renderId, next.target.renderId)
            f.native.onShow = {}
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Retired),
                reconcile(f, next),
            )
            assertTrue(references(f).references.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun lostCommittedCancellationResponseKeepsNativeContentGoneAndWatermarkRetired() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            restrict(f)
            val actual = f.app.koin.get<CollaborationNotificationPublicationRepository>()
            val uncertain =
                object : CollaborationNotificationPublicationRepository by actual {
                    override suspend fun replace(
                        access: CollaborationRecoveryAccess,
                        expected: CollaborationNotificationPublication?,
                        body: CollaborationNotificationPublicationBody,
                    ): AppResult<CollaborationNotificationPublicationWrite> {
                        val saved = actual.replace(access, expected, body)
                        assertIs<AppResult.Success<*>>(saved)
                        return AppResult.Failed(
                            Failure(FailureKind.Storage, "Fictional lost cancellation response")
                        )
                    }
                }
            f.app.koin.loadModules(
                listOf(
                    module { single<CollaborationNotificationPublicationRepository> { uncertain } }
                )
            )
            assertIs<AppResult.Failed>(reconcile(f, before))
            assertTrue(f.native.cards.isEmpty())
            assertEquals(2, f.native.removed.size)
            assertEquals(
                CollaborationNotificationPublicationState.Cancelled,
                f.publication()!!.body.state,
            )
            f.reopen()
            assertTrue(references(f).references.isEmpty())
            assertEquals(
                CollaborationNotificationPublicationState.Cancelled,
                f.publication()!!.body.state,
            )
        }
    }

    @Test
    fun cancellationDuringJournalIoJoinsAlreadyRegisteredNativeCleanup() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            restrict(f)
            val entered = CompletableDeferred<Unit>()
            var joined = false
            val actual = f.app.koin.get<CollaborationNotificationPublicationRepository>()
            val pending =
                object : CollaborationNotificationPublicationRepository by actual {
                    override suspend fun replace(
                        access: CollaborationRecoveryAccess,
                        expected: CollaborationNotificationPublication?,
                        body: CollaborationNotificationPublicationBody,
                    ): AppResult<CollaborationNotificationPublicationWrite> {
                        assertIs<AppResult.Success<*>>(actual.replace(access, expected, body))
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            joined = true
                        }
                    }
                }
            f.app.koin.loadModules(
                listOf(
                    module { single<CollaborationNotificationPublicationRepository> { pending } }
                )
            )
            val job = launch { reconcile(f, before) }
            try {
                entered.await()
                assertTrue(f.native.cards.isEmpty())
                job.cancelAndJoin()
                assertTrue(joined)
                assertEquals(2, f.native.removed.size)
                assertEquals(
                    CollaborationNotificationPublicationState.Cancelled,
                    f.publication()!!.body.state,
                )
                assertEquals(
                    AppResult.Success(Unit),
                    f.owner.withOwner(f.parent) { AppResult.Success(Unit) },
                )
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
            }
        }
    }

    @Test
    fun nativeRemovalFailureKeepsPublicationBlockedUntilExplicitCleanupSucceeds() = runBlocking {
        withFixture { f ->
            var fail = false
            val source =
                object : ActionNotificationDisplaySource by f.native {
                    override suspend fun remove(
                        expected: ActionNotificationReference
                    ): ActionNotificationRemoval {
                        if (fail) error("Fictional unavailable native removal")
                        return f.native.remove(expected)
                    }
                }
            f.owner.closeAndJoin()
            f.owner =
                CollaborationNotificationSurfaceOwner(f.parents, source, Dispatchers.Unconfined)
            f.owner.start()
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            fail = true
            assertIs<AppResult.Failed>(
                reconcile(f, before, Failure(FailureKind.Storage, "Fictional failed observer"))
            )
            val attempts = f.native.attempts
            assertIs<AppResult.Failed>(f.publish())
            assertEquals(attempts, f.native.attempts)
            fail = false
            assertIs<AppResult.Success<*>>(f.owner.reconcile())
            assertTrue(f.native.cards.isEmpty())
            assertEquals(
                done(CollaborationNotificationReconciliationResult.Submitted),
                reconcile(f, before),
            )
            assertTrue(f.native.cards.values.single().silent)
        }
    }

    @Test
    fun foreignAndInactiveReferencesCannotChangeTheOwnedCard() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val before = reference(f)
            val card = f.native.cards.values.single()
            for (invalid in
                listOf(
                    before.copy(
                        parent = before.parent.copy(accountId = UUID.randomUUID().toString())
                    ),
                    before.copy(state = CollaborationNotificationPublicationState.Cancelled),
                    before.copy(id = "invalid"),
                )) {
                assertIs<AppResult.Failed>(reconcile(f, invalid))
                assertEquals(card, f.native.cards.values.single())
            }
            assertEquals(1, f.native.attempts)
            assertTrue(f.native.removed.isEmpty())
        }
    }

    private suspend fun reconcile(
        f: NotificationPublisherFixture,
        reference: CollaborationNotificationPublicationReference,
        failure: Failure? = null,
    ) =
        f.app.koin
            .get<CollaborationNotificationReconciler> { parametersOf(f.owner) }
            .reconcile(
                f.parent,
                reference,
                CollaborationNotificationCopy("HRIS", "New message"),
                failure,
            )

    private suspend fun restrict(f: NotificationPublisherFixture) {
        assertIs<AppResult.Success<*>>(
            f.app.koin
                .get<CollaborationListRepository>()
                .restrict(
                    f.scope.access,
                    Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied"),
                )
        )
    }

    private fun done(value: CollaborationNotificationReconciliationResult) =
        AppResult.Success(value)

    private suspend fun reference(f: NotificationPublisherFixture) =
        references(f).references.single()

    private suspend fun references(f: NotificationPublisherFixture) =
        assertIs<AppResult.Success<CollaborationNotificationPublicationReferences>>(
                f.app.koin.get<ReadCollaborationNotificationPublications>()(f.parent)
            )
            .value

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
