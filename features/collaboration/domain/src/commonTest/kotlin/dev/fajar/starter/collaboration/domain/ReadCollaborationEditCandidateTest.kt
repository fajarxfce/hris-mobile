package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationEditCandidate
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationEditCandidateTest {
    @Test
    fun expiredCredentialsDoNotHideAnOwnedRecentOfflineCandidateOrTouchTransport() = runTest {
        val f = EditCandidateFixture()
        f.owner.setup.now += 365.days
        val recent =
            f.candidate.target.copy(
                targetAcceptedAt = f.owner.clock.now().toEpochMilliseconds() - 1,
                firstAcceptedAt = f.owner.clock.now().toEpochMilliseconds() - 1,
            )
        f.candidate = f.candidate.copy(target = recent)
        assertEquals(AppResult.Success(f.candidate), f.read())
        assertEquals(0, f.owner.setup.calls)
        assertEquals(0, f.owner.insertions)
        assertTrue(f.owner.scheduledKeys.isEmpty())
    }

    @Test
    fun foreignMissingAndChangedCandidatesCannotAuthorizeAnEditor() = runTest {
        for (kind in 0..7) {
            val f = EditCandidateFixture()
            when (kind) {
                0 -> f.identity = null
                1 -> f.identity = CollaborationSelfIdentity("@other:example.test")
                2 -> f.owner.summary = null
                3 -> f.missing = true
                4 ->
                    f.candidate =
                        f.candidate.copy(target = f.candidate.target.copy(roomId = "!other:test"))
                5 ->
                    f.candidate =
                        f.candidate.copy(target = f.candidate.target.copy(eventId = "\$other"))
                6 ->
                    f.candidate =
                        f.candidate.copy(target = f.candidate.target.copy(originalLogicalId = null))
                7 -> f.candidate = f.candidate.copy(text = "")
            }
            assertIs<AppResult.Failed>(f.read())
            assertEquals(0, f.owner.insertions)
        }
    }

    @Test
    fun denialOrAccountReplacementAfterEitherStorageReadCannotPublishTheOldText() = runTest {
        for (afterIdentity in listOf(false, true)) for (deny in listOf(false, true)) {
            val f = EditCandidateFixture()
            val change: suspend () -> Unit = {
                if (deny) f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
                else f.owner.setup.session = null
            }
            if (afterIdentity) f.afterIdentity = change else f.afterTarget = change
            val failure = assertIs<AppResult.Failed>(f.read())
            if (deny) assertEquals(f.owner.denied, failure.failure)
        }
    }

    @Test
    fun cancellationDrainsTheCandidateRead() = runTest {
        val f = EditCandidateFixture()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        f.afterTarget = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                released.complete(Unit)
            }
        }
        val pending = async { f.read() }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(released.isCompleted)
    }

    @Test
    fun editTimeUsesTheFirstAliasAndRechecksTimeAfterStorage() = runTest {
        val f = EditCandidateFixture()
        val target = f.candidate.target
        assertTrue(
            canAuthorCollaborationEdit(target, target.sender, target.firstAcceptedAt + 899_999)
        )
        assertFalse(
            canAuthorCollaborationEdit(target, target.sender, target.firstAcceptedAt + 900_000)
        )
        assertFalse(canAuthorCollaborationEdit(target, target.sender, target.targetAcceptedAt - 1))
        assertFalse(
            canAuthorCollaborationEdit(
                target.copy(firstAcceptedAt = target.targetAcceptedAt + 1),
                target.sender,
                target.targetAcceptedAt,
            )
        )
        assertFalse(validCollaborationEditTarget(target.copy(eventId = "\$非ascii")))
        assertFalse(validCollaborationEditTarget(target.copy(replyToEventId = "\$非ascii")))
        f.afterTarget = { f.owner.setup.now += 1.days }
        assertIs<AppResult.Failed>(f.read())
    }
}

private class EditCandidateFixture {
    val owner = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            owner.setup.session!!.id,
            owner.server.audience,
            owner.setup.session!!.user.id,
            owner.setup.workspace!!.company.id,
        )
    val conversation = owner.summary!!.conversation.id
    val reference =
        CollaborationMessageReference(
            "@self:example.test",
            "\$original",
            "00000000-0000-0000-0000-000000000091",
        )
    var identity: CollaborationSelfIdentity? = CollaborationSelfIdentity(reference.sender)
    var candidate =
        CollaborationEditCandidate(
            CollaborationEditTarget(
                "!source:example.test",
                reference.eventId,
                reference.logicalId,
                reference.sender,
                "a".repeat(64),
                owner.clock.now().toEpochMilliseconds() - 1,
                owner.clock.now().toEpochMilliseconds() - 100,
            ),
            " Visible current text 🙂\n",
        )
    var missing = false
    var afterIdentity: suspend () -> Unit = {}
    var afterTarget: suspend () -> Unit = {}
    private val identities =
        object : CollaborationIdentityRepository {
            override suspend fun current(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationSelfIdentity?> {
                assertEquals(this@EditCandidateFixture.access, access)
                val value = identity
                afterIdentity()
                return AppResult.Success(value)
            }

            override suspend fun retain(
                access: CollaborationLocalAccess,
                identity: CollaborationSelfIdentity,
            ): AppResult<Boolean> = error("Read only")
        }
    private val targets =
        object : CollaborationEditTargetRepository {
            override suspend fun getEditCandidate(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationEditCandidate?> {
                assertEquals(this@EditCandidateFixture.access, access)
                assertEquals(conversation, conversationId)
                assertEquals(reference.eventId, eventId)
                val value = candidate.takeUnless { missing }
                afterTarget()
                return AppResult.Success(value)
            }
        }
    private val reader =
        ReadCollaborationEditCandidate(
            targets,
            identities,
            owner.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )

    suspend fun read() =
        reader("selection", access, conversation, "!source:example.test", reference)
}
