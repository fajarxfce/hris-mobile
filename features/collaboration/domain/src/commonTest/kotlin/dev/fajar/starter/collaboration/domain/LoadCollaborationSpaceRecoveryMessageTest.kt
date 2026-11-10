@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSpaceRecoveryMessage
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class LoadCollaborationSpaceRecoveryMessageTest {
    @Test
    fun exactClaimResolvesWithoutBindingOrProviderAccess() = runTest {
        val f = Fixture()
        assertEquals(
            AppResult.Success(
                CollaborationSpaceRecoveryMessage(f.base.model.conversation, f.record)
            ),
            f.read(),
        )
        assertEquals(listOf("space-get", "metadata"), f.base.calls)
    }

    @Test
    fun absentClaimFailsBeforeCommandOrCanonicalReads() = runTest {
        val f = Fixture()
        f.snapshot =
            f.snapshot.copy(
                progress =
                    f.snapshot.progress!!.copy(
                        sweep = CollaborationSpaceRecoverySweep(f.base.queue.id(903))
                    )
            )
        assertIs<AppResult.Failed>(f.read())
        assertTrue(f.base.calls.isEmpty())
    }

    @Test
    fun missingOrValidTerminalOriginalEndsOnlyItsAttempt() = runTest {
        for (kind in 0..2) {
            val f = Fixture()
            when (kind) {
                0 -> f.base.space.records.clear()
                1 ->
                    f.base.space.records[f.record.intent.id] =
                        f.record.copy(revision = 0, state = CollaborationMessageState.Cancelled)
                else -> f.base.space.records[f.record.intent.id] = f.source.source.sent()
            }
            assertEquals(AppResult.Success(null), f.read())
            assertEquals(0, f.base.count("metadata"))
        }
    }

    @Test
    fun changedCommandIdentityCannotResolveAnotherOriginal() = runTest {
        for (kind in 0..2) {
            val f = Fixture()
            val intent =
                when (kind) {
                    0 -> f.record.intent.copy(id = f.base.queue.id(904))
                    1 -> f.record.intent.copy(conversationId = f.base.queue.id(905))
                    else ->
                        f.record.intent.copy(
                            createdAt =
                                f.record.intent.createdAt + kotlin.time.Duration.parse("1ns")
                        )
                }
            f.base.space.records[f.record.intent.id] = f.record.copy(intent = intent)
            assertIs<AppResult.Failed>(f.read())
            assertEquals(0, f.base.count("metadata"))
        }
    }

    @Test
    fun malformedTerminalRowIsNotTreatedAsCompleted() = runTest {
        val f = Fixture()
        f.base.space.records[f.record.intent.id] =
            f.record.copy(state = CollaborationMessageState.Cancelled)
        assertIs<AppResult.Failed>(f.read())
        assertEquals(0, f.base.count("metadata"))
    }

    @Test
    fun missingOrForeignCanonicalFactsCannotInventAConversation() = runTest {
        for (kind in 0..2) {
            val f = Fixture()
            f.canonical =
                when (kind) {
                    0 -> null
                    1 -> f.canonical!!.copy(id = f.base.queue.id(906))
                    else -> f.canonical!!.copy(spaceId = f.base.queue.id(907))
                }
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun replacingOwnerOrDenyingAccessAtEitherReadStopsPublication() = runTest {
        for (stage in listOf("space-get", "metadata")) {
            for (denied in listOf(false, true)) {
                val f = Fixture()
                f.base.hooks[stage] = {
                    if (denied) f.base.directory.deny() else f.base.native.setup.session = null
                }
                val failure = assertIs<AppResult.Failed>(f.read()).failure
                if (denied) assertEquals(f.base.directory.denied, failure)
            }
        }
    }

    @Test
    fun storageFailureDoesNotFallBackToRemoteMetadata() = runTest {
        for (stage in listOf("space-get", "metadata")) {
            val f = Fixture()
            f.base.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.read()).failure,
            )
        }
    }

    @Test
    fun queuedAndUncertainStagingRemainTheirExactOriginalStates() = runTest {
        for (queued in listOf(false, true)) {
            val f = Fixture()
            val state = f.record.state as CollaborationMessageState.Staged
            val before = mutableSetOf("old-transaction")
            val expected =
                f.record.copy(
                    revision = if (queued) 0 else 1,
                    state =
                        if (queued) CollaborationMessageState.Queued
                        else CollaborationMessageState.Staging(state.device, before, state.origin),
                )
            f.base.space.records[f.record.intent.id] = expected
            f.base.directory.beforeAdmission = {
                if (f.base.count("space-get") == 1) before += "later-transaction"
            }
            val read =
                assertIs<AppResult.Success<CollaborationSpaceRecoveryMessage?>>(f.read()).value!!
            if (queued) assertEquals(expected, read.record)
            else
                assertEquals(
                    setOf("old-transaction"),
                    (read.record.state as CollaborationMessageState.Staging).before,
                )
        }
    }

    @Test
    fun cancellationAndTimeoutReleaseTheOwningRead() = runTest {
        for (cancelled in listOf(false, true)) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            var released = false
            f.base.hooks["metadata"] = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
            if (cancelled) {
                val task = async { f.read() }
                entered.await()
                task.cancelAndJoin()
                assertTrue(task.isCancelled)
            } else
                assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read()).failure.kind)
            assertTrue(released)
        }
    }

    private class Fixture {
        val source = MixedSpaceDeliveryFixture()
        val base = source.base
        val record = source.record
        var canonical: CollaborationSpaceConversationMetadata? = base.model.binding.conversation
        var snapshot =
            CollaborationSpaceRecoverySnapshot(
                base.queue.owner,
                CollaborationSpaceRecoveryProgress(
                    base.queue.id(900),
                    CollaborationSpaceRecoverySweep(
                        base.queue.id(901),
                        messages =
                            CollaborationSpaceMessageRecoverySweep(
                                pending =
                                    CollaborationSpaceMessageRecoveryClaim(
                                        CollaborationSpaceMessageRecoveryPosition(
                                            record.intent.createdAt,
                                            record.intent.id,
                                        ),
                                        record.intent.conversationId,
                                    )
                            ),
                    ),
                ),
            )
        val metadata =
            object : CollaborationSpaceConversationMetadataRepository {
                override suspend fun getById(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ): AppResult<CollaborationSpaceConversationMetadata?> {
                    assertEquals(base.queue.owner.localDirectoryAccess(), access)
                    assertEquals(record.intent.conversationId, conversationId)
                    return base.read("metadata") { canonical }
                }

                override suspend fun findByPeer(
                    access: CollaborationSpaceLocalAccess,
                    peerAccountId: String,
                ): AppResult<CollaborationSpaceConversationMetadata?> =
                    error("Exact canonical read only")

                override suspend fun retain(
                    access: CollaborationSpaceLocalAccess,
                    conversation: CollaborationSpaceConversation,
                ): AppResult<Boolean> = error("No metadata write")
            }
        private val load =
            LoadCollaborationSpaceRecoveryMessage(
                base.space.messages,
                metadata,
                base.directory.directory,
                base.native.setup.sessions,
                base.native.server,
            )

        suspend fun read() =
            load(snapshot).also {
                for (name in
                    listOf(
                        "binding",
                        "transport",
                        "acquire",
                        "resume",
                        "update",
                        "append",
                        "space-update",
                        "space-append",
                    )) assertEquals(0, base.count(name), name)
            }
    }
}
