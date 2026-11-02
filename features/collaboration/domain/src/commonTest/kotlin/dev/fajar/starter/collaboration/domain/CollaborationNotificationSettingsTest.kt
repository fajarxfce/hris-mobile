package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationSettingsTest {
    @Test
    fun privateDefaultsAndOfflineChangesNeedNoFreshTokenOrSelectedCompany() = runTest {
        val f = CollaborationNotificationSettingsFixture()
        f.local.setup.now += 365.days
        f.local.setup.workspace = null
        f.local.setup.beforeWorkspace = { error("No selected company") }
        assertEquals(AppResult.Success(CollaborationNotificationSettingsState()), f.read())
        val changed =
            assertIs<AppResult.Success<CollaborationNotificationSettingsState>>(f.update()).value
        assertEquals(CollaborationNotificationMute.Forever, changed.settings.mute)
        assertEquals(CollaborationNotificationPreview.Hidden, changed.settings.preview)
        assertEquals(0, f.local.setup.calls)
        assertTrue(f.local.scheduledKeys.isEmpty())
    }

    @Test
    fun muteExpiryUsesExactBoundaryWithoutDiscardingStoredPreference() = runTest {
        val f = CollaborationNotificationSettingsFixture()
        val until = f.local.setup.now + 1.days
        val value =
            CollaborationNotificationSettings(
                CollaborationNotificationMute.Until(until),
                CollaborationNotificationPreview.SenderOnly,
            )
        val change = f.change(value = value)
        assertIs<AppResult.Success<*>>(f.update(change))
        assertTrue(value.isMutedAt(until - 1.milliseconds))
        assertFalse(value.isMutedAt(until))
        f.local.setup.now = until + 365.days
        assertEquals(
            value,
            assertIs<AppResult.Success<CollaborationNotificationSettingsState>>(f.read())
                .value
                .settings,
        )
        assertIs<AppResult.Success<*>>(f.update(change)) // Lost-reply retry after mute expires.
        assertEquals(1, f.writes)
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.update(f.change(2, value))).failure.kind,
        )
        assertTrue(
            CollaborationNotificationSettings(CollaborationNotificationMute.Forever)
                .isMutedAt(until + 365.days)
        )
    }

    @Test
    fun lostCommitReplyReusesOperationButOldActionCannotOverwriteNewPreference() = runTest {
        val f = CollaborationNotificationSettingsFixture()
        val first = f.change()
        f.writeResult = AppResult.Failed(f.local.storage)
        f.commitOnFailure = true
        assertIs<AppResult.Failed>(f.update(first))
        f.writeResult = AppResult.Success(true)
        assertIs<AppResult.Success<*>>(f.update(first))
        assertEquals(1, f.writes)
        val next =
            f.change(
                2,
                CollaborationNotificationSettings(
                    preview = CollaborationNotificationPreview.Message
                ),
            )
        assertIs<AppResult.Success<*>>(f.update(next))
        val saved = f.state
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.update(first)).failure.kind)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.update(next.copy(settings = first.settings))).failure.kind,
        )
        assertEquals(2, f.writes)
        assertEquals(saved, f.state)
    }

    @Test
    fun confirmedDenialWinsOverPendingStorageFailuresAndRetainsCommittedSettings() = runTest {
        for (phase in listOf("initial", "conversation", "read", "write")) {
            val f = CollaborationNotificationSettingsFixture()
            val deny: suspend () -> Unit = {
                f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                f.local.setup.sessionFailure = f.local.storage
                f.readFailure = f.local.storage
            }
            when (phase) {
                "initial" ->
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                "conversation" -> f.local.afterConversation = deny
                "read" -> f.afterRead = deny
                "write" -> f.afterWrite = deny
            }
            assertEquals(AppResult.Failed(f.local.denied), f.update(), phase)
            assertEquals(if (phase == "write") 1 else 0, f.writes, phase)
            if (phase == "write")
                assertEquals(CollaborationNotificationMute.Forever, f.state.settings.mute)
        }
    }

    @Test
    fun reauthenticationPreservesPreferenceButInvalidatesEarlierOsActionOwner() = runTest {
        val f = CollaborationNotificationSettingsFixture()
        val old = f.scope()
        assertIs<AppResult.Success<*>>(f.update())
        f.local.setup.session = f.local.setup.session!!.copy(id = "new-family")
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.read(old)).failure.kind)
        assertIs<AppResult.Success<*>>(f.read())
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(
                    f.updater(f.scope(), f.base.conversationId, f.change(2), old.access)
                )
                .failure
                .kind,
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun lateOwnerReplacementWithholdsPublicationAndStopsFurtherWrites() = runTest {
        for (phase in listOf("conversation", "read", "write")) {
            val f = CollaborationNotificationSettingsFixture()
            val scope = f.scope()
            val replace: suspend () -> Unit = {
                f.local.setup.session = f.local.setup.session!!.copy(id = "replacement")
            }
            when (phase) {
                "conversation" -> f.local.afterConversation = replace
                "read" -> f.afterRead = replace
                "write" -> f.afterWrite = replace
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.update(scope = scope)).failure.kind,
            )
            assertEquals(if (phase == "write") 1 else 0, f.writes)
        }
    }

    @Test
    fun malformedConversationOrStateCannotFallBackToUnmutedDefaults() = runTest {
        val f = CollaborationNotificationSettingsFixture()
        val captured = f.scope()
        f.local.summary = null
        assertIs<AppResult.Failed>(f.read(captured))
        assertIs<AppResult.Failed>(f.update(scope = captured))
        assertEquals(0, f.reads)
        val g = CollaborationNotificationSettingsFixture()
        g.state =
            CollaborationNotificationSettingsState(
                settings = CollaborationNotificationSettings(CollaborationNotificationMute.Forever)
            )
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(g.read()).failure.kind)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(g.update()).failure.kind)
        assertEquals(0, g.writes)
        g.state = CollaborationNotificationSettingsState()
        g.readFailure = g.local.storage
        assertEquals(AppResult.Failed(g.local.storage), g.read())
    }

    @Test
    fun invalidChangeRejectedBeforeStorageAndCasLossNeverRetries() = runTest {
        val f = CollaborationNotificationSettingsFixture()
        for (change in
            listOf(
                f.change().copy(operationId = "bad"),
                f.change().copy(expected = CollaborationNotificationSettingsRevision("bad", 0)),
                f.change(
                    value =
                        CollaborationNotificationSettings(
                            CollaborationNotificationMute.Until(f.local.setup.now + 1.nanoseconds)
                        )
                ),
            )) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.update(change)).failure.kind,
        )
        assertEquals(0, f.reads)
        f.writeResult = AppResult.Success(false)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.update()).failure.kind)
        assertEquals(1, f.writes)
        assertEquals(CollaborationNotificationSettingsState(), f.state)
    }

    @Test
    fun cancellationJoinsPendingWorkAndReleasesExclusionWithoutLatePublication() = runTest {
        for (phase in listOf("read", "write")) {
            val f = CollaborationNotificationSettingsFixture()
            val entered = CompletableDeferred<Unit>()
            var joined = false
            val pending: suspend () -> Unit = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            if (phase == "read") f.afterRead = pending else f.afterWrite = pending
            var published = false
            val change = f.change()
            val job = launch {
                f.update(change)
                published = true
            }
            entered.await()
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.update(change)).failure.kind,
            )
            job.cancelAndJoin()
            assertTrue(joined)
            assertFalse(published)
            f.afterRead = {}
            f.afterWrite = {}
            assertIs<AppResult.Success<*>>(f.update(change))
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun operationTimeoutCancelsStorageWithoutHiddenRetry() = runTest {
        val f = CollaborationNotificationSettingsFixture()
        var joined = false
        f.afterRead = {
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.update()).failure.kind)
        assertTrue(joined)
        assertEquals(0, f.writes)
        assertEquals(1, f.reads)
    }
}
