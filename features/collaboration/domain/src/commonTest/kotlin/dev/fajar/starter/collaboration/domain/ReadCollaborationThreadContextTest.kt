package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationIdentityRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationThreadContext
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationThreadContextTest {
    @Test
    fun ownedCachedContextRemainsReadableAfterLiveCredentialsExpire() = runTest {
        val f = Fixture()
        f.base.setup.now += 30.days
        val result =
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadContext>>>(f.read())
                .value
        assertEquals(f.base.summary, result.value.summary)
        assertEquals(f.self, result.value.self)
        assertEquals(0, f.base.setup.calls)
    }

    @Test
    fun missingCachedConversationDoesNotGuessARecipientOrStartAcquisition() = runTest {
        val f = Fixture()
        f.base.summary = null
        assertIs<AppResult.Failed>(f.read())
        assertEquals(0, f.identityReads)
        assertEquals(0, f.base.setup.calls)
    }

    @Test
    fun wrongCompanyConversationPeerOrMembershipCannotReachPresentation() = runTest {
        for (change in 0..3) {
            val f = Fixture()
            val previous = f.base.summary!!
            f.base.summary =
                when (change) {
                    0 ->
                        previous.copy(
                            conversation = previous.conversation.copy(companyId = "other")
                        )
                    1 -> previous.copy(conversation = previous.conversation.copy(id = "other"))
                    2 -> previous.copy(peer = previous.peer.copy(accountId = "other"))
                    else ->
                        previous.copy(
                            conversation = previous.conversation.copy(firstAccountId = "other")
                        )
                }
            assertIs<AppResult.Failed>(f.read())
            assertEquals(0, f.identityReads)
        }
    }

    @Test
    fun newDenialOrAccountReplacementDuringAReadCannotPublishCachedLabels() = runTest {
        for (phase in 0..2) {
            val f = Fixture()
            when (phase) {
                0 -> f.base.afterConversation = {
                        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
                    }
                1 -> f.base.afterConversation = { f.base.setup.session = null }
                else -> f.beforeIdentity = { f.base.setup.session = null }
            }
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun cancelledUncooperativeContextReadCannotContinueToIdentity() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.base.afterConversation = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val job = async { f.read() }
        try {
            entered.await()
            job.cancel()
        } finally {
            release.complete(Unit)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, f.identityReads)
    }

    @Test
    fun absentIdentityStaysUnknownAndInvalidIdentityIsRejected() = runTest {
        val f = Fixture()
        f.identity = null
        assertNull(
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadContext>>>(f.read())
                .value
                .value
                .self
        )
        f.identity = CollaborationSelfIdentity("not-a-matrix-user")
        assertIs<AppResult.Failed>(f.read())
    }

    private class Fixture {
        val base = MessageIntentFixture()
        val conversationId = base.summary!!.conversation.id
        val self = CollaborationSelfIdentity("@fictional:example.test")
        var identity: CollaborationSelfIdentity? = self
        var identityReads = 0
        var beforeIdentity: suspend () -> Unit = {}
        private val identities =
            object : CollaborationIdentityRepository {
                override suspend fun current(
                    access: CollaborationLocalAccess
                ): AppResult<CollaborationSelfIdentity?> {
                    identityReads++
                    beforeIdentity()
                    return AppResult.Success(identity)
                }

                override suspend fun retain(
                    access: CollaborationLocalAccess,
                    identity: CollaborationSelfIdentity,
                ): AppResult<Boolean> = error("No context mutation")
            }
        private val reader =
            ReadCollaborationThreadContext(
                base.lists,
                identities,
                base.setup.sessions,
                base.setup.workspaces,
                base.server,
                base.build,
                base.clock,
            )

        suspend fun read() = reader("selection", conversationId)
    }
}
