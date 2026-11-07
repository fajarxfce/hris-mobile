@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceOriginalQueue
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.*

class CollaborationOriginalQueueReadTest {
    @Test
    fun acquiredOriginalsMatchWithoutCompanySelectionPollingOrQueueMutation() = runTest {
        val f = Fixture()
        f.native.setup.workspace = null
        val result = assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(f.read()).value
        assertEquals(f.model.original, result.messages.single().original)
        assertEquals(f.record, result.messages.single().record)
        assertEquals(f.completion, result.messages.single().completion)
        assertEquals(
            listOf("conversation", "identity", "queue", "message", "completion", "open"),
            f.calls,
        )
        advanceTimeBy(60_000)
        assertEquals(6, f.calls.size)
        assertEquals(0, f.directory.admits)
        assertEquals(0, f.closed)
    }

    @Test
    fun invalidRequestsAndExistingDenialNeverReadMetadataOrPrivateQueue() = runTest {
        val f = Fixture()
        val nil = "00000000-0000-0000-0000-000000000000"
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    f.useCase()(f.model.runtime.copy(deviceId = nil), f.model.position)
                )
                .failure
                .kind,
        )
        assertIs<AppResult.Failed>(
            f.useCase()(f.model.runtime, f.model.position.copy(conversationId = nil))
        )
        assertTrue(f.calls.isEmpty())
        f.directory.deny()
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()).failure)
        assertTrue(f.calls.isEmpty())
        assertEquals(1, f.closed)
        assertEquals(0, f.directory.admits)
    }

    @Test
    fun changedMetadataOrIdentityCannotOpenPrivateQueueAndUnknownOriginalCannotReadCommands() =
        runTest {
            for (variant in 0..3) {
                val f = Fixture()
                when (variant) {
                    0 -> f.conversation = f.conversation.copy(room = null)
                    1 ->
                        f.conversation =
                            f.conversation.copy(
                                conversation =
                                    f.conversation.conversation.copy(
                                        position =
                                            f.model.position.copy(companyId = f.model.owner.spaceId)
                                    )
                            )
                    2 ->
                        f.identity =
                            f.identity.copy(owner = f.model.owner.copy(spaceId = f.model.id(32)))
                    else ->
                        f.identity =
                            f.identity.copy(
                                identity =
                                    f.model.identity.copy(legacyCompanyId = f.model.owner.spaceId)
                            )
                }
                assertIs<AppResult.Failed>(f.read())
                assertFalse("queue" in f.calls)
            }
            for (variant in 0..3) {
                val f = Fixture()
                val original = f.model.original
                val changed =
                    when (variant) {
                        0 -> listOf(original.copy(logicalId = null))
                        1 -> listOf(original, original)
                        2 -> List(129) { original }
                        else ->
                            listOf(
                                original.copy(
                                    origin = original.origin.copy(sender = "@wrong:example.test")
                                )
                            )
                    }
                f.snapshot =
                    f.snapshot.copy(original = f.snapshot.original.copy(messages = changed))
                assertIs<AppResult.Failed>(f.read())
                assertFalse("message" in f.calls)
            }
        }

    @Test
    fun accountReplacementOrConfirmedDenialAtAnySuspensionSuppressesPrivateResults() = runTest {
        for (stage in stages) for (denial in listOf(false, true)) {
            val f = Fixture()
            f.hooks[stage] = { if (denial) f.directory.deny() else f.native.setup.session = null }
            val result = assertIs<AppResult.Failed>(f.read())
            if (denial) {
                assertEquals(f.directory.denied, result.failure)
                assertEquals(1, f.closed)
            }
        }
    }

    @Test
    fun originalNativeHardExpiryWallRollbackAndMonotonicBudgetCannotBeRenewedMidRead() = runTest {
        for (variant in 0..2) {
            val f = Fixture()
            f.native.setup.session =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                        )
                )
            f.hooks["message"] = {
                when (variant) {
                    0 -> {
                        f.native.setup.now += 3.seconds
                        f.native.setup.session = f.native.session
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    else -> f.native.time += 2.seconds
                }
            }
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.read()).failure.code,
            )
            assertFalse("completion" in f.calls)
        }
    }

    @Test
    fun cancellationAndNonCooperativeLateCompletionAreDrainedAtEveryRepository() = runTest {
        for (stage in stages) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            f.hooks[stage] = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            val task = async { f.read() }
            entered.await()
            task.cancelAndJoin()
            stopped.await()
            assertTrue(task.isCancelled)
            f.hooks[stage] = { withContext(NonCancellable) { delay(20_000) } }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read()).failure.kind)
        }
    }

    @Test
    fun remoteDenialSurvivesCancellationFailedPersistenceAndFailedRuntimeCleanup() = runTest {
        for (stage in listOf("conversation", "identity")) {
            val f = Fixture()
            f.failures[stage] = f.directory.denied
            f.directory.afterRestrict = { AppResult.Failed(f.directory.storageFailure) }
            f.closeFailure = f.directory.storageFailure
            f.hooks[stage] = { currentCoroutineContext().cancel() }
            val task = async { f.read() }
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(1, f.directory.restrictions)
            assertEquals(1, f.closed)
            f.hooks.clear()
            f.failures.clear()
            assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()).failure)
            assertEquals(0, f.directory.admits)
        }
    }

    @Test
    fun nativeCredentialRejectionDoesNotPretendTheServerDeniedOrganizationAccess() = runTest {
        val f = Fixture()
        val rejected = Failure(FailureKind.Unauthorized, "Native credential rejected")
        f.failures["queue"] = rejected
        assertEquals(rejected, assertIs<AppResult.Failed>(f.read()).failure)
        assertEquals(0, f.directory.restrictions)
        assertEquals(0, f.closed)
        assertEquals(
            f.directory.open,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                    f.directory.gate.value
                )
                .value,
        )
        assertFalse("message" in f.calls)
    }

    @Test
    fun missingWrongOrUnconfirmedImmutableCommandCannotTurnQueueObservationIntoAck() = runTest {
        for (variant in 0..3) {
            val f = Fixture()
            when (variant) {
                0 -> f.record = null
                1 ->
                    f.record =
                        requireNotNull(f.record)
                            .copy(
                                intent = requireNotNull(f.record).intent.copy(id = f.model.id(102))
                            )
                2 -> f.completion = null
                else -> f.open = false
            }
            assertIs<AppResult.Failed>(f.read())
            assertEquals(0, f.closed)
        }
    }

    @Test
    fun mutableRepositoryCollectionsCannotEraseCapturedOriginalOrCompletionDuringAdmission() =
        runTest {
            val f = Fixture()
            val originals = mutableListOf(f.model.original)
            val aliases = mutableListOf("\$accepted", "\$alias")
            f.snapshot = f.snapshot.copy(original = f.snapshot.original.copy(messages = originals))
            f.completion = requireNotNull(f.completion).copy(eventIds = aliases)
            f.directory.beforeAdmission = {
                when (f.calls.lastOrNull()) {
                    "queue" -> originals.clear()
                    "completion" -> aliases.clear()
                }
            }
            val result =
                assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(f.read()).value
            assertEquals(listOf(f.model.original), result.messages.map { it.original })
            assertEquals(
                listOf("\$accepted", "\$alias"),
                result.messages.single().completion?.eventIds,
            )
        }

    private val stages =
        listOf("conversation", "identity", "queue", "message", "completion", "open")

    private class Fixture {
        val directory = SpaceDirectoryUseCaseFixture()
        val native = directory.native
        val model = OriginalQueueMatchFixture(native)
        var conversation = model.conversation
        var identity = CollaborationSpaceIdentityObservation(model.owner, model.identity)
        var snapshot = model.snapshot
        var record: CollaborationMessageRecord? = model.sent()
        var completion: CollaborationMessageCompletion? =
            CollaborationMessageCompletion(requireNotNull(record), listOf("\$accepted"))
        var open = true
        var closed = 0
        var closeFailure: Failure? = null
        val calls = mutableListOf<String>()
        val hooks = mutableMapOf<String, suspend () -> Unit>()
        val failures = mutableMapOf<String, Failure>()

        suspend fun arrive(stage: String) {
            calls += stage
            hooks[stage]?.invoke()
        }

        val conversations =
            object : CollaborationSpaceOriginalConversationRepository {
                override suspend fun list(
                    owner: CollaborationSpaceOwner,
                    after: CollaborationOriginalConversationPosition?,
                    limit: Int,
                ): AppResult<CollaborationSpaceOriginalConversationPage> = error("No list/poll")

                override suspend fun get(
                    owner: CollaborationSpaceOwner,
                    position: CollaborationOriginalConversationPosition,
                ): AppResult<CollaborationSpaceOriginalConversation> {
                    check(owner == model.owner && position == model.position)
                    arrive("conversation")
                    return failures["conversation"]?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(conversation)
                }
            }
        val identities =
            object : CollaborationSpaceIdentityRepository {
                override suspend fun read(
                    owner: CollaborationSpaceOwner
                ): AppResult<CollaborationSpaceIdentityObservation> {
                    check(owner == model.owner)
                    arrive("identity")
                    return failures["identity"]?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(identity)
                }

                override suspend fun select(
                    owner: CollaborationSpaceOwner,
                    command: CollaborationSpaceIdentityCommand,
                ): AppResult<CollaborationSetupReceipt> = error("No selection")
            }
        val runtimes =
            object : CollaborationSpaceRuntimeRepository {
                override suspend fun inspectOriginalQueue(
                    runtime: CollaborationSpaceRuntimeReady,
                    matrixRoomId: String,
                ): AppResult<CollaborationSpaceOriginalQueueSnapshot> {
                    check(runtime == model.runtime && matrixRoomId == model.device.roomId)
                    arrive("queue")
                    return failures["queue"]?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(snapshot)
                }

                override suspend fun isSpaceOpen(
                    owner: CollaborationSpaceOwner,
                    deviceId: String,
                    operationId: String,
                ): AppResult<Boolean> {
                    check(
                        owner == model.owner &&
                            deviceId == model.runtime.deviceId &&
                            operationId == model.runtime.operationId
                    )
                    arrive("open")
                    return AppResult.Success(open)
                }

                override suspend fun installOriginal(
                    owner: CollaborationSpaceOwner,
                    original: CollaborationExistingRuntimeHandover,
                    setup: CollaborationSpaceSetupRecord,
                ): AppResult<CollaborationSpaceRuntimeLease> = error("No install")

                override suspend fun closeSpace(owner: CollaborationSpaceOwner): AppResult<Unit> {
                    check(owner == model.owner)
                    closed++
                    return closeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                }
            }
        val messages =
            object : CollaborationMessageRepository {
                override fun observe(
                    access: CollaborationLocalAccess,
                    conversationId: String?,
                ): Flow<AppResult<List<CollaborationMessageRecord>>> = error("No observation")

                override suspend fun get(
                    access: CollaborationLocalAccess,
                    id: String,
                ): AppResult<CollaborationMessageRecord?> {
                    check(access == originalAccess() && id == model.original.logicalId)
                    arrive("message")
                    return failures["message"]?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(record)
                }

                override suspend fun insert(
                    access: CollaborationLocalAccess,
                    intent: CollaborationMessageIntent,
                ): AppResult<CollaborationMessageInsertion> = error("No new command")

                override suspend fun update(
                    access: CollaborationLocalAccess,
                    expected: CollaborationMessageRecord,
                    state: CollaborationMessageState,
                ): AppResult<Boolean> = error("No ACK or queue mutation")
            }
        val completed =
            object : CollaborationMessageCompletionRepository {
                override suspend fun readCompletion(
                    access: CollaborationLocalAccess,
                    id: String,
                ): AppResult<CollaborationMessageCompletion?> {
                    check(access == originalAccess() && id == model.original.logicalId)
                    arrive("completion")
                    return failures["completion"]?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(completion)
                }

                override suspend fun archiveCompletion(
                    access: CollaborationLocalAccess,
                    completion: CollaborationMessageCompletion,
                ): AppResult<Boolean> = error("No archival")
            }

        fun originalAccess() =
            CollaborationLocalAccess(
                model.owner.parent.sessionId,
                model.binding.audience,
                model.binding.accountId,
                model.binding.companyId,
            )

        fun useCase() =
            ReadCollaborationSpaceOriginalQueue(
                conversations,
                identities,
                runtimes,
                messages,
                completed,
                directory.directory,
                native.setup.sessions,
                native.server,
                AppBuild(AppPlatform.Android, 5, "test", null),
                native.clock,
                native.time,
            )

        suspend fun read() = useCase()(model.runtime, model.position)
    }
}
