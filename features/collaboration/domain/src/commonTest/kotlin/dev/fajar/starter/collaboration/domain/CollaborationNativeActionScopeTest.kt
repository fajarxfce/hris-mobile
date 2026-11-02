@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationMessages
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationNativeActionScopeTest {
    @Test
    fun queuedReplyKeepsTheCapturedNativeFamilyBeforeAndAfterConversationRead() = runTest {
        for (pending in listOf(false, true)) {
            val f = CollaborationNotificationInvocationFixture()
            val scope = f.scope()
            if (pending) f.local.afterConversation = { f.replaceFamily() } else f.replaceFamily()
            val result =
                f.queue()(scope, f.target.conversationId, f.action.operationId, "Offline reply")
            assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(result).failure.kind)
            assertEquals(0, f.local.insertions)
            assertTrue(f.local.scheduledKeys.isEmpty())
        }
    }

    @Test
    fun familyChangeAfterReplyCommitKeepsTheOriginalCommandWithoutLateAcknowledgmentOrWake() =
        runTest {
            val f = CollaborationNotificationInvocationFixture()
            val scope = f.scope()
            f.local.afterInsert = { f.replaceFamily() }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(
                        f.queue()(
                            scope,
                            f.target.conversationId,
                            f.action.operationId,
                            "Retained reply",
                        )
                    )
                    .failure
                    .kind,
            )
            assertEquals(1, f.local.records.value.size)
            assertEquals("Retained reply", f.local.records.value.single().intent.text)
            assertTrue(f.local.scheduledKeys.isEmpty())
        }

    @Test
    fun replyReplaysTheSameOwnedCommandAfterUncertaintyAndRejectsChangedText() = runTest {
        val f = CollaborationNotificationInvocationFixture()
        val scope = f.scope()
        val queue = f.queue()
        assertIs<AppResult.Success<*>>(
            queue(scope, f.target.conversationId, f.action.operationId, "One reply")
        )
        assertIs<AppResult.Success<*>>(
            queue(scope, f.target.conversationId, f.action.operationId, "One reply")
        )
        assertIs<AppResult.Failed>(
            queue(scope, f.target.conversationId, f.action.operationId, "Different reply")
        )
        assertEquals(1, f.local.insertions)
        assertEquals("One reply", f.local.records.value.single().intent.text)
    }

    @Test
    fun readAndMuteRecheckNativeFamilyAfterPendingLocalSources() = runTest {
        val f = CollaborationNotificationInvocationFixture()
        val scope = f.scope()
        f.read.base.afterFact = { f.replaceFamily() }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.read.mark(scope)).failure.kind,
        )
        assertEquals(0, f.read.writes)
        val settings = CollaborationNotificationSettingsFixture()
        val session = settings.local.setup.session!!
        val family = CollaborationNotificationInvocationFixture.id(900)
        settings.local.setup.session =
            session.copy(binding = session.binding.copy(serverId = family))
        val pinned = settings.scope().copy(nativeSessionId = family)
        settings.afterRead = {
            settings.local.setup.session =
                session.copy(
                    binding =
                        session.binding.copy(
                            serverId = CollaborationNotificationInvocationFixture.id(901)
                        )
                )
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(settings.update(scope = pinned)).failure.kind,
        )
        assertEquals(0, settings.writes)
    }

    @Test
    fun pinnedObserverClosesOnNativeFamilyReplacementWhileTokenRotationRetainsIt() = runTest {
        val f = CollaborationNotificationInvocationFixture()
        val scope = f.scope()
        val initial = f.local.setup.session!!
        val identities = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))
        val sessions =
            object : SessionRepository by f.local.setup.sessions {
                override fun observe() = identities

                override suspend fun current() = identities.value
            }
        val observer =
            ObserveCollaborationMessages(
                f.local.messages,
                f.local.lists,
                sessions,
                f.local.setup.workspaces,
                f.local.server,
                f.local.build,
                f.local.clock,
            )
        val values = mutableListOf<AppResult<List<CollaborationMessageRecord>>>()
        val job =
            backgroundScope.launch { observer(scope, f.target.conversationId).collect(values::add) }
        runCurrent()
        assertEquals(1, f.local.activeMessageObservers)
        identities.value =
            AppResult.Success(initial.copy(tokens = initial.tokens.copy(accessToken = "rotated")))
        runCurrent()
        assertEquals(1, f.local.messageObservations)
        identities.value =
            AppResult.Success(
                initial.copy(
                    binding =
                        initial.binding.copy(
                            serverId = CollaborationNotificationInvocationFixture.id(901)
                        )
                )
            )
        runCurrent()
        assertEquals(0, f.local.activeMessageObservers)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        job.cancelAndJoin()
    }

    @Test
    fun ordinaryCapturedReadsRemainAvailableAfterFamilyChangesButInvalidPinsReject() = runTest {
        val f = CollaborationNotificationInvocationFixture()
        val ordinary = f.read.scope()
        f.replaceFamily()
        assertIs<AppResult.Success<*>>(f.read.read(ordinary))
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.read.read(ordinary.copy(nativeSessionId = "invalid")))
                .failure
                .kind,
        )
    }
}
