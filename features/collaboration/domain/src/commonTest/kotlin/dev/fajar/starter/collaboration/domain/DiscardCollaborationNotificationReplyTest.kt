package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DiscardCollaborationNotificationReplyTest {
    @Test
    fun absentCommandIsFencedBeforeInputRemovalAndDuplicateCallbacksCannotSend() = runTest {
        val f = Fixture()
        f.base.beforeRemove = {
            assertEquals(CollaborationMessageState.Cancelled, f.local.records.value.single().state)
        }
        assertEquals(AppResult.Success(CollaborationReplyDiscard.Discarded), f.discard())
        assertNull(f.base.saved)
        assertEquals(1, f.preventions)
        assertEquals(0, f.local.insertions)
        f.base.saved = f.base.expected // Late duplicate callback retains its original input.
        assertIs<CollaborationNotificationReplyCompletion.Retired>(
            assertIs<AppResult.Success<CollaborationNotificationReplyCompletion>>(f.base.complete())
                .value
        )
        assertNull(f.base.saved)
        assertEquals(0, f.local.insertions)
    }

    @Test
    fun queuedCommandCancelsExactlyAndAlreadyStartedWorkRemainsRetained() = runTest {
        val f = Fixture()
        f.base.seed()
        assertEquals(AppResult.Success(CollaborationReplyDiscard.Discarded), f.discard())
        assertEquals(1, f.cancellations)
        assertEquals(0, f.preventions)
        val started = Fixture()
        started.base.seed(CollaborationMessageState.Staging(started.base.device(), emptySet()))
        assertEquals(
            AppResult.Success(CollaborationReplyDiscard.AlreadyProcessing),
            started.discard(),
        )
        assertEquals(started.base.expected, started.base.saved)
        assertEquals(0, started.cancellations)
        assertEquals(0, started.preventions)
    }

    @Test
    fun lostRemovalResponseRetainsCancellationAndRetryDoesNotCreateNewWork() = runTest {
        val f = Fixture()
        f.base.afterRemove = { throw CancellationException("Lost response after removal") }
        assertFailsWith<CancellationException> { f.discard() }
        assertEquals(CollaborationMessageState.Cancelled, f.local.records.value.single().state)
        f.base.afterRemove = {}
        assertEquals(AppResult.Success(CollaborationReplyDiscard.Absent), f.discard())
        assertEquals(1, f.preventions)
    }

    @Test
    fun failedFenceResponseNeverRemovesInputAndRetryReproducesTerminalFact() = runTest {
        val f = Fixture()
        // Repository failures are represented as AppResult; the fixture models a committed error.
        f.failPrevention = true
        f.afterPrevent = {}
        assertIs<AppResult.Failed>(f.discard())
        assertEquals(f.base.expected, f.base.saved)
        f.failPrevention = false
        assertEquals(AppResult.Success(CollaborationReplyDiscard.Discarded), f.discard())
        assertEquals(1, f.preventions)
    }

    @Test
    fun changedInputCompanyAccountAndBodyNeverDiscardAnotherMessage() = runTest {
        val f = Fixture()
        f.base.saved = f.base.expected.copy(version = f.base.expected.version.copy(revision = 1))
        assertIs<AppResult.Failed>(f.discard())
        f.base.saved = f.base.expected
        assertIs<AppResult.Failed>(
            f.useCase("selection", f.access.copy(companyId = f.base.id(777)), f.base.expected)
        )
        f.base.messageResult =
            AppResult.Success(
                f.base.command().let { it.copy(intent = it.intent.copy(text = "Different")) }
            )
        assertEquals(AppResult.Success(CollaborationReplyDiscard.MessageChanged), f.discard())
        assertEquals(0, f.preventions)
        assertEquals(0, f.base.removals)
    }

    @Test
    fun familyChangeAndDenialAfterFenceKeepInputWithoutUndoingCancellation() = runTest {
        for (denied in listOf(false, true)) {
            val f = Fixture()
            f.afterPrevent = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                else f.base.native.replaceFamily()
            }
            assertIs<AppResult.Failed>(f.discard())
            assertEquals(f.base.expected, f.base.saved)
            assertEquals(0, f.base.removals)
            assertEquals(CollaborationMessageState.Cancelled, f.local.records.value.single().state)
        }
    }

    @Test
    fun timeoutAndCancellationJoinPendingFenceAndDoNotRetireInput() = runTest {
        val f = Fixture()
        var joined = false
        f.afterPrevent = {
            try {
                delay(8_001)
            } finally {
                joined = true
            }
        }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.discard()).failure.kind)
        assertTrue(joined)
        assertEquals(f.base.expected, f.base.saved)
        assertEquals(0, f.base.removals)
    }

    private class Fixture {
        val base = CollaborationNotificationReplyCompletionFixture()

        init {
            val workspace = requireNotNull(base.local.setup.workspace)
            base.local.setup.workspace =
                workspace.copy(
                    admission =
                        workspace.admission.copy(familyId = base.native.parent.nativeSessionId)
                )
        }

        val local = base.local
        val access = base.native.scope().access
        var preventions = 0
        var cancellations = 0
        var failPrevention = false
        var afterPrevent: suspend () -> Unit = {}
        val prevention =
            object : CollaborationMessagePreventionRepository {
                override suspend fun prevent(
                    access: CollaborationLocalAccess,
                    intent: CollaborationMessageIntent,
                ): AppResult<Boolean> {
                    preventions++
                    local.records.value =
                        listOf(
                            CollaborationMessageRecord(
                                intent,
                                0,
                                CollaborationMessageState.Cancelled,
                            )
                        )
                    afterPrevent()
                    return if (failPrevention) AppResult.Failed(local.storage)
                    else AppResult.Success(true)
                }
            }
        val cancellation =
            object : CollaborationMessageCancellationRepository {
                override suspend fun cancel(
                    access: CollaborationLocalAccess,
                    expected: CollaborationMessageRecord,
                ): AppResult<Boolean> {
                    cancellations++
                    if (local.records.value.singleOrNull() != expected)
                        return AppResult.Success(false)
                    local.records.value =
                        listOf(expected.copy(state = CollaborationMessageState.Cancelled))
                    return AppResult.Success(true)
                }
            }
        val useCase =
            DiscardCollaborationNotificationReply(
                base.inputs,
                base.messages,
                cancellation,
                prevention,
                local.lists,
                local.setup.sessions,
                local.setup.workspaces,
                local.server,
                local.build,
                local.clock,
            )

        suspend fun discard() = useCase("selection", access, base.expected)
    }
}
