package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationNotificationPreviewText
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationProjectionTest {
    @Test
    fun hiddenSenderAndMessagePreferencesExposeOnlySelectedBoundedContentOffline() = runTest {
        for (preview in CollaborationNotificationPreview.entries) {
            val f = CollaborationNotificationProjectionFixture()
            f.local.setup.now += 365.days
            f.local.setup.workspace = null
            f.local.setup.beforeWorkspace = { error("No selected company") }
            f.preference(CollaborationNotificationSettings(preview = preview))
            f.original = f.original!!.copy(text = "x".repeat(399) + "🙂" + "remaining")
            val projected =
                assertIs<CollaborationNotificationProjection.Ready>(
                        assertIs<AppResult.Success<CollaborationNotificationProjection>>(
                                f.project()
                            )
                            .value
                    )
                    .message
            assertEquals(preview, projected.preview)
            assertEquals(
                if (preview == CollaborationNotificationPreview.Hidden) null
                else f.local.summary!!.peer.displayName,
                projected.senderName,
            )
            assertEquals(
                if (preview == CollaborationNotificationPreview.Message) "x".repeat(399) else null,
                projected.body,
            )
            assertEquals(f.event, projected.eventId)
            assertEquals(f.settings.scope().access, projected.access)
            assertFalse(projected.toString().contains("remaining"))
            assertEquals(0, f.local.setup.calls)
            assertTrue(f.local.scheduledKeys.isEmpty())
            assertEquals(0, f.settings.writes)
        }
    }

    @Test
    fun mutedOwnRedactedAndAnyReadAliasNeverBecomeAlerts() = runTest {
        for (reason in CollaborationNotificationSuppression.entries) {
            val f = CollaborationNotificationProjectionFixture()
            when (reason) {
                CollaborationNotificationSuppression.Muted ->
                    f.preference(
                        CollaborationNotificationSettings(CollaborationNotificationMute.Forever)
                    )
                CollaborationNotificationSuppression.OwnMessage ->
                    f.original = f.original!!.copy(sender = f.self!!.matrixUserId)
                CollaborationNotificationSuppression.Redacted ->
                    f.original = f.original!!.copy(redacted = true, text = null)
                CollaborationNotificationSuppression.Read ->
                    f.read.state =
                        CollaborationReadState(
                            CollaborationReadStateRevision(
                                CollaborationReadStateFixture.id(600),
                                0,
                            ),
                            setOf(f.read.ids.last()),
                            emptyList(),
                            null,
                        )
            }
            assertEquals(
                AppResult.Success(CollaborationNotificationProjection.Suppressed(reason)),
                f.project(),
            )
            if (reason == CollaborationNotificationSuppression.Muted) {
                assertEquals(0, f.identityCalls)
                assertEquals(0, f.historyCalls)
            }
            assertEquals(0, f.read.writes)
            assertEquals(0, f.settings.writes)
        }
    }

    @Test
    fun missingHistoryOrSelfIdentityStaysPendingAndStorageFailureNeverShowsFallback() = runTest {
        for (missingSelf in listOf(true, false)) {
            val f = CollaborationNotificationProjectionFixture()
            if (missingSelf) f.self = null else f.original = null
            assertEquals(
                AppResult.Success(CollaborationNotificationProjection.PendingHistory),
                f.project(),
            )
        }
        val f = CollaborationNotificationProjectionFixture()
        f.historyFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.project())
        f.historyFailure = null
        f.settings.readFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.project())
    }

    @Test
    fun confirmedTombstoneBeforeOriginalSuppressesWithoutWaitingForUnrecoverableContent() =
        runTest {
            val f = CollaborationNotificationProjectionFixture()
            f.original = null
            f.read.base.facts[f.event] =
                CollaborationHistoryEventFact(f.event, "@peer:test", null, true)
            assertEquals(
                AppResult.Success(
                    CollaborationNotificationProjection.Suppressed(
                        CollaborationNotificationSuppression.Redacted
                    )
                ),
                f.project(),
            )
            f.read.base.facts.clear()
            assertEquals(
                AppResult.Success(CollaborationNotificationProjection.PendingHistory),
                f.project(),
            )
            f.read.base.facts[f.event] =
                CollaborationHistoryEventFact("\$wrong", "@peer:test", null, true)
            assertIs<AppResult.Failed>(f.project())
        }

    @Test
    fun privacyChangedDuringReadStateOrFinalOriginalReadWithholdsPreview() = runTest {
        for (phase in listOf("read-state", "original")) {
            val f = CollaborationNotificationProjectionFixture()
            f.preference(
                CollaborationNotificationSettings(
                    preview = CollaborationNotificationPreview.Message
                )
            )
            val hide: suspend () -> Unit = { f.preference(CollaborationNotificationSettings()) }
            if (phase == "read-state") f.read.afterRead = hide
            else f.afterHistory = { if (f.historyCalls == 2) hide() }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.project()).failure.kind)
        }
    }

    @Test
    fun readMarkArrivingDuringHistoryLookupSuppressesTheLogicalMessage() = runTest {
        val f = CollaborationNotificationProjectionFixture()
        f.afterHistory = {
            if (f.historyCalls == 1)
                f.read.state =
                    CollaborationReadState(
                        CollaborationReadStateRevision(CollaborationReadStateFixture.id(700), 0),
                        setOf(f.read.ids.last()),
                        emptyList(),
                        null,
                    )
        }
        assertEquals(
            AppResult.Success(
                CollaborationNotificationProjection.Suppressed(
                    CollaborationNotificationSuppression.Read
                )
            ),
            f.project(),
        )
        assertEquals(0, f.read.writes)
    }

    @Test
    fun pendingPrivacyAndRedactionChangesWithholdPreparedContent() = runTest {
        for (phase in listOf("privacy", "redaction")) {
            val f = CollaborationNotificationProjectionFixture()
            f.preference(
                CollaborationNotificationSettings(
                    preview = CollaborationNotificationPreview.Message
                )
            )
            if (phase == "privacy")
                f.afterHistory = {
                    if (f.historyCalls == 1) f.preference(CollaborationNotificationSettings())
                }
            else f.read.afterRead = { f.original = f.original!!.copy(redacted = true, text = null) }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.project()).failure.kind)
        }
    }

    @Test
    fun lateDenialOrOwnerReplacementWinsAtEveryPendingSource() = runTest {
        for (denied in listOf(true, false)) for (phase in
            listOf("settings", "identity", "history", "read", "fact")) {
            val f = CollaborationNotificationProjectionFixture()
            val scope = f.settings.scope()
            val change: suspend () -> Unit = {
                if (denied) {
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                    f.local.setup.sessionFailure = f.local.storage
                } else f.local.setup.session = f.local.setup.session!!.copy(id = "replacement")
            }
            when (phase) {
                "settings" -> f.settings.afterRead = change
                "identity" -> f.afterIdentity = change
                "history" -> f.afterHistory = change
                "read" -> f.read.afterRead = change
                "fact" -> {
                    f.original = null
                    f.read.base.afterFact = change
                }
            }
            val failure = assertIs<AppResult.Failed>(f.project(scope)).failure
            if (denied) assertEquals(f.local.denied, failure)
            else assertEquals(FailureKind.Unauthorized, failure.kind)
        }
    }

    @Test
    fun cancellationAndDeadlineJoinPendingOriginalReadWithoutLateProjection() = runTest {
        for (timeout in listOf(false, true)) {
            val f = CollaborationNotificationProjectionFixture()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.afterHistory = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            var published = false
            if (timeout)
                assertEquals(
                    FailureKind.Unavailable,
                    assertIs<AppResult.Failed>(f.project()).failure.kind,
                )
            else {
                val job = launch {
                    f.project()
                    published = true
                }
                entered.await()
                job.cancelAndJoin()
            }
            assertTrue(stopped)
            assertFalse(published)
            assertEquals(1, f.historyCalls)
            assertEquals(0, f.read.writes)
        }
    }

    @Test
    fun unownedConversationForgedAliasAndMalformedOriginalNeverDisplay() = runTest {
        val f = CollaborationNotificationProjectionFixture()
        assertIs<AppResult.Failed>(f.prepare(f.settings.scope(), "bad", f.room, f.event))
        assertEquals(0, f.settings.reads)
        assertIs<AppResult.Failed>(
            f.prepare(f.settings.scope(), f.conversation, f.room, "\$unacquired")
        )
        f.original = f.original!!.copy(eventIds = emptyList())
        assertIs<AppResult.Failed>(f.project())
        val g = CollaborationNotificationProjectionFixture()
        val captured = g.settings.scope()
        g.local.summary = null
        assertIs<AppResult.Failed>(g.project(captured))
        assertEquals(0, g.historyCalls)
    }

    @Test
    fun textPreviewBoundsScanAndPreservesCompleteUnicodePairs() {
        assertEquals(
            "Hello\nWorld",
            collaborationNotificationPreviewText("\u0000Hello\r\nWorld\u0001", 400),
        )
        assertEquals("🙂", collaborationNotificationPreviewText("🙂x", 2))
        assertEquals("", collaborationNotificationPreviewText("🙂x", 1))
        assertEquals("", collaborationNotificationPreviewText("\u0000".repeat(4096) + "later", 400))
    }
}
