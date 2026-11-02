package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationInvocationTest {
    @Test
    fun everyActionCapturesCurrentNativeFamilyAndAcquiredCompanyWithoutWorkspaceOrFreshCredentials() =
        runTest {
            for (kind in listOf("reply", "read", "mute", "dismiss")) {
                val f = CollaborationNotificationInvocationFixture()
                f.local.setup.now += 365.days
                f.local.setup.workspace = null
                f.local.setup.beforeWorkspace = { error("No selected company required") }
                val operation = f.action.operationId
                f.action =
                    when (kind) {
                        "read" -> CollaborationNotificationAction.MarkRead(operation, f.target)
                        "mute" ->
                            CollaborationNotificationAction.Mute(
                                operation,
                                f.target,
                                null,
                                CollaborationNotificationPreview.Hidden,
                            )
                        "dismiss" -> CollaborationNotificationAction.Dismiss(operation, f.target)
                        else -> f.action
                    }
                val value =
                    assertIs<AppResult.Success<CollaborationNotificationInvocation>>(f.open()).value
                assertEquals(f.action, value.action)
                assertEquals(f.scope(), value.scope)
                assertEquals(if (kind in listOf("reply", "read")) 1 else 0, f.read.base.factReads)
                assertEquals(0, f.local.setup.calls)
                assertEquals(0, f.local.insertions)
                assertEquals(0, f.read.writes)
                assertTrue(f.local.scheduledKeys.isEmpty())
                assertFalse(value.toString().contains(f.target.conversationId))
            }
        }

    @Test
    fun malformedBoundedInputAndNonNativeHostsNeverOpenCrypto() = runTest {
        val f = CollaborationNotificationInvocationFixture()
        for (value in listOf("", "v1.", "v2.token", "v1." + "x".repeat(4094))) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.opener()(CollaborationNotificationActionToken(value)))
                .failure
                .kind,
        )
        assertIs<AppResult.Failed>(f.opener(AppPlatform.Web)(f.token))
        assertEquals(0, f.opens)
        assertEquals(0, f.read.base.factReads)
    }

    @Test
    fun absentParentAndInvalidDecodedTargetNeverReachHistory() = runTest {
        val f = CollaborationNotificationInvocationFixture()
        f.local.setup.session = null
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.open()).failure.kind)
        assertEquals(0, f.opens)
        val g = CollaborationNotificationInvocationFixture()
        g.action =
            CollaborationNotificationAction.Reply(
                g.action.operationId,
                g.target.copy(companyId = "invalid"),
            )
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(g.open()).failure.kind)
        assertEquals(0, g.read.base.factReads)
    }

    @Test
    fun missingForeignOrMalformedEventDoesNotAdmitReplyOrReadButTombstoneRemainsOwned() = runTest {
        for (isRead in listOf(false, true)) {
            val f = CollaborationNotificationInvocationFixture()
            if (isRead)
                f.action = CollaborationNotificationAction.MarkRead(f.action.operationId, f.target)
            f.read.base.facts.clear()
            assertIs<AppResult.Failed>(f.open())
            f.read.base.facts[f.target.eventId] =
                CollaborationHistoryEventFact("\$foreign", "@peer:test", 1, false)
            assertIs<AppResult.Failed>(f.open())
            f.read.base.facts[f.target.eventId] =
                CollaborationHistoryEventFact(f.target.eventId, "@peer:test", null, true)
            assertIs<AppResult.Success<*>>(f.open())
        }
    }

    @Test
    fun muteAndDismissStillValidateConversationWhenOriginalIsGone() = runTest {
        for (mute in listOf(false, true)) {
            val f = CollaborationNotificationInvocationFixture()
            f.action =
                if (mute)
                    CollaborationNotificationAction.Mute(
                        f.action.operationId,
                        f.target,
                        null,
                        CollaborationNotificationPreview.Hidden,
                    )
                else CollaborationNotificationAction.Dismiss(f.action.operationId, f.target)
            f.read.base.facts.clear()
            assertIs<AppResult.Success<*>>(f.open())
            f.local.summary = null
            assertIs<AppResult.Failed>(f.open())
            assertEquals(0, f.read.base.factReads)
        }
    }

    @Test
    fun forgedCounterpartOrCompanyBindingCannotAdmitAStoredToken() = runTest {
        for (company in listOf(false, true)) {
            val f = CollaborationNotificationInvocationFixture()
            val summary = f.local.summary!!
            f.local.summary =
                if (company)
                    summary.copy(
                        conversation =
                            summary.conversation.copy(
                                companyId = CollaborationNotificationInvocationFixture.id(950)
                            )
                    )
                else
                    summary.copy(
                        peer = summary.peer.copy(accountId = summary.conversation.firstAccountId)
                    )
            assertIs<AppResult.Failed>(f.open())
            assertEquals(0, f.read.base.factReads)
        }
    }

    @Test
    fun currentRetainedDenialWinsEvenIfSessionRefreshFailsDuringDecodedConversationOrFactReads() =
        runTest {
            for (phase in listOf("decode", "conversation", "fact")) {
                val f = CollaborationNotificationInvocationFixture()
                val deny: suspend () -> Unit = {
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                    f.local.setup.sessionFailure = f.local.storage
                }
                when (phase) {
                    "decode" -> f.afterOpen = deny
                    "conversation" -> f.local.afterConversation = deny
                    "fact" -> f.read.base.afterFact = deny
                }
                assertEquals(AppResult.Failed(f.local.denied), f.open())
            }
        }

    @Test
    fun nativeFamilyReplacementAfterEveryPendingSourceRejectsEvenWithSameLocalSessionAndAccount() =
        runTest {
            for (phase in listOf("decode", "conversation", "fact")) {
                val f = CollaborationNotificationInvocationFixture()
                val replace: suspend () -> Unit = { f.replaceFamily() }
                when (phase) {
                    "decode" -> f.afterOpen = replace
                    "conversation" -> f.local.afterConversation = replace
                    "fact" -> f.read.base.afterFact = replace
                }
                assertEquals(
                    FailureKind.Unauthorized,
                    assertIs<AppResult.Failed>(f.open()).failure.kind,
                )
            }
        }

    @Test
    fun cryptoOrFactStorageFailureNeverAdmitsAndDoesNotRetry() = runTest {
        for (crypto in listOf(false, true)) {
            val f = CollaborationNotificationInvocationFixture()
            if (crypto) f.openFailure = f.local.storage
            else f.read.base.factFailure = f.local.storage
            assertEquals(AppResult.Failed(f.local.storage), f.open())
            assertEquals(1, f.opens)
            assertEquals(if (crypto) 0 else 1, f.read.base.factReads)
        }
    }

    @Test
    fun cancellationAndDeadlineJoinPendingDecodeAndFactBeforeReturning() = runTest {
        for (phase in listOf("decode", "fact")) for (timeout in listOf(false, true)) {
            val f = CollaborationNotificationInvocationFixture()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            val suspendRead: suspend () -> Unit = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            if (phase == "decode") f.afterOpen = suspendRead
            else f.read.base.afterFact = suspendRead
            var published = false
            if (timeout)
                assertEquals(
                    FailureKind.Unavailable,
                    assertIs<AppResult.Failed>(f.open()).failure.kind,
                )
            else {
                val job = launch {
                    f.open()
                    published = true
                }
                entered.await()
                job.cancelAndJoin()
            }
            assertTrue(stopped)
            assertFalse(published)
            assertEquals(1, f.opens)
        }
    }
}
