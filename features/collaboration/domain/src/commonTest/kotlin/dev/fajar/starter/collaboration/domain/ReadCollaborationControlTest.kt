package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationControl
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationControlTest {
    @Test
    fun capturedControlReadsRetainedSetupDirectAndRoomAfterExpiry() = runTest {
        for (kind in 0..2) {
            val f = Fixture()
            if (kind == 0)
                f.control.record =
                    f.control.retain().copy(outcome = CollaborationSetupOutcome.Unconfirmed())
            else
                f.retain(
                    if (kind == 1) CollaborationConversationAction.Open(f.id)
                    else CollaborationConversationAction.RequestRoom(f.id, COMMAND_DEVICE, 1, null)
                )
            val scope = f.captured()
            val reader = f.reader()
            val found =
                when (kind) {
                    0 -> reader.setup(scope)
                    1 -> reader.direct(scope, f.id)
                    else -> reader.room(scope, f.id)
                }
            assertSame(
                if (kind == 0) f.control.record else f.conversation,
                assertIs<AppResult.Success<*>>(found).value.let {
                    (it as CollaborationRead<*>).value
                },
            )
            assertEquals(0, f.control.access.calls)
            assertTrue(f.control.calls.isEmpty())
            assertEquals(0, f.control.updateCalls)
            assertNull(f.control.access.workspace)
        }
    }

    @Test
    fun capturedControlCannotReadAnEarlierNativeFamilysRetainedCommand() = runTest {
        val f = Fixture()
        f.control.record =
            f.control.retain().copy(outcome = CollaborationSetupOutcome.Unconfirmed())
        val scope = f.captured()
        val original = f.control.record
        val identity = requireNotNull(f.control.access.session)
        f.control.access.session =
            identity.copy(binding = identity.binding.copy(serverId = deliveryId(99)))
        assertIs<AppResult.Failed>(f.reader().setup(scope))
        assertSame(original, f.control.record)
        assertEquals(0, f.control.updateCalls)
    }

    @Test
    fun capturedControlRetainsCompanyDenialAndDoesNotReadPendingCommands() = runTest {
        val f = Fixture()
        f.retain(CollaborationConversationAction.Open(f.id))
        val scope = f.captured()
        f.control.access.admission.deny(f.denial)
        assertEquals(AppResult.Failed(f.denial), f.reader().direct(scope, f.id))
        assertTrue(f.resources.isEmpty())
        assertNotNull(f.conversation)
    }

    @Test
    fun capturedControlRejectsALateOwnerAndCancellationWithoutDeletingIntent() = runTest {
        for (cancel in listOf(false, true)) {
            val f = Fixture()
            f.control.record =
                f.control.retain().copy(outcome = CollaborationSetupOutcome.Unconfirmed())
            val scope = f.captured()
            val original = f.control.record
            f.control.beforeCurrent = {
                if (cancel) currentCoroutineContext().cancel() else f.control.access.session = null
            }
            if (cancel) {
                val task = launch { f.reader().setup(scope) }
                task.join()
                assertTrue(task.isCancelled)
            } else assertIs<AppResult.Failed>(f.reader().setup(scope))
            assertSame(original, f.control.record)
            assertEquals(0, f.control.updateCalls)
        }
    }

    @Test
    fun retainedSetupSurvivesExpiredCredentialsPolicyAgeAndApplicationUpgrade() = runTest {
        val f = Fixture()
        val record = f.control.retain().copy(outcome = CollaborationSetupOutcome.Unconfirmed())
        f.control.record = record
        f.control.access.now += 30.days
        assertSame(
            record,
            assertIs<AppResult.Success<CollaborationRead<CollaborationSetupCommandRecord?>>>(
                    f.reader().setup("selection")
                )
                .value
                .value,
        )
        assertEquals(0, f.control.access.calls)
        assertTrue(f.control.calls.isEmpty())
        assertEquals(0, f.control.updateCalls)
    }

    @Test
    fun missingIntentDoesNotCreateRegisterOrRoomWork() = runTest {
        val f = Fixture()
        assertNull(
            assertIs<AppResult.Success<CollaborationRead<CollaborationSetupCommandRecord?>>>(
                    f.reader().setup("selection")
                )
                .value
                .value
        )
        assertNull(
            assertIs<AppResult.Success<CollaborationRead<CollaborationConversationCommandRecord?>>>(
                    f.reader().direct("selection", f.id)
                )
                .value
                .value
        )
        assertEquals(listOf("direct/${f.id}"), f.resources)
        assertTrue(f.control.calls.isEmpty())
        assertNull(f.control.record)
    }

    @Test
    fun retainedRoomAndDirectReadOnlyTheRequestedResource() = runTest {
        for (room in listOf(false, true)) {
            val f = Fixture()
            val action =
                if (room) CollaborationConversationAction.RequestRoom(f.id, "device", 1, null)
                else CollaborationConversationAction.Open(f.id)
            val record = f.retain(action)
            val result =
                if (room) f.reader().room("selection", f.id)
                else f.reader().direct("selection", f.id)
            assertSame(
                record,
                assertIs<
                        AppResult.Success<
                            CollaborationRead<CollaborationConversationCommandRecord?>
                        >
                    >(
                        result
                    )
                    .value
                    .value,
            )
            assertEquals(listOf(action.resourceId()), f.resources)
        }
    }

    @Test
    fun wrongResourceOrFamilyCannotExposeAStoredCommand() = runTest {
        for (family in listOf(false, true)) {
            val f = Fixture()
            val record = f.retain(CollaborationConversationAction.Open(f.id))
            f.conversation =
                record.copy(
                    command =
                        if (family)
                            record.command.copy(
                                binding = record.command.binding.copy(nativeSessionId = "other")
                            )
                        else
                            record.command.copy(
                                action =
                                    CollaborationConversationAction.RequestRoom(
                                        f.id,
                                        "device",
                                        1,
                                        null,
                                    )
                            )
                )
            assertEquals(
                "client.collaboration_command_changed",
                assertIs<AppResult.Failed>(f.reader().direct("selection", f.id)).failure.code,
            )
        }
    }

    @Test
    fun invalidResourceIsRejectedBeforeLocalAcquisition() = runTest {
        val f = Fixture()
        for (id in listOf("", "../setup", f.id.uppercase(), "x".repeat(200))) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.reader().room("selection", id)).failure.kind,
            )
        }
        assertTrue(f.resources.isEmpty())
        assertEquals(0, f.control.access.admission.reads)
    }

    @Test
    fun aKnownDenialHidesIntentWithoutRemovingIt() = runTest {
        val f = Fixture()
        val record = f.control.retain()
        var reads = 0
        f.control.beforeCurrent = { reads++ }
        f.control.access.admission.deny(f.denial)
        assertEquals(f.denial, assertIs<AppResult.Failed>(f.reader().setup("selection")).failure)
        assertSame(record, f.control.record)
        assertEquals(0, reads)
    }

    @Test
    fun denialOrOwnerReplacementDuringAcquisitionCannotPublish() = runTest {
        for (stage in 0..3) {
            val f = Fixture()
            val record = f.control.retain()
            f.control.beforeCurrent = {
                when (stage) {
                    0 -> f.control.access.admission.deny(f.denial)
                    1 -> f.control.access.session = null
                    2 ->
                        f.control.access.workspace =
                            f.control.access.selected.copy(id = "new-selection")
                    else ->
                        f.control.access.session =
                            f.control.access.initial.copy(
                                binding =
                                    f.control.access.initial.binding.copy(serverId = "new-family")
                            )
                }
            }
            assertIs<AppResult.Failed>(f.reader().setup("selection"))
            assertSame(record, f.control.record)
        }
    }

    @Test
    fun accountReplacementDuringFinalGateReadCannotPublish() = runTest {
        val f = Fixture()
        f.control.retain()
        f.control.access.admission.beforeRead = {
            if (f.control.access.admission.reads == 2) f.control.access.session = null
        }
        assertIs<AppResult.Failed>(f.reader().setup("selection"))
    }

    @Test
    fun cancelledLateLocalReadCannotPublishOrRetire() = runTest {
        val f = Fixture()
        val record = f.control.retain()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.control.beforeCurrent = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val job = async { f.reader().setup("selection") }
        try {
            entered.await()
            job.cancel()
        } finally {
            release.complete(Unit)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertSame(record, f.control.record)
        assertEquals(0, f.control.updateCalls)
    }

    @Test
    fun malformedBootstrapCannotBecomeRecoverableControl() = runTest {
        val f = Fixture()
        val record = f.control.retain(CollaborationSetupAction.Bootstrap("device", 1, null))
        fun gateway(owner: CollaborationOwner = f.control.owner, device: String = "device") =
            CollaborationGateway(
                owner,
                device,
                "gateway",
                0,
                "user",
                "matrix-device",
                "access",
                "refresh",
                1,
                2,
            )
        for ((attempts, value) in
            listOf(
                0 to gateway(),
                1 to gateway(device = "wrong"),
                1 to gateway(owner = f.control.owner.copy(nativeSessionId = "other")),
            )) {
            f.control.record =
                record.copy(
                    attempts = attempts,
                    outcome = CollaborationSetupOutcome.Bootstrapped(value),
                )
            assertEquals(
                "client.collaboration_command_changed",
                assertIs<AppResult.Failed>(f.reader().setup("selection")).failure.code,
            )
        }
        f.control.record =
            record.copy(attempts = 1, outcome = CollaborationSetupOutcome.Bootstrapped(gateway()))
        assertIs<AppResult.Success<*>>(f.reader().setup("selection"))
    }

    private class Fixture {
        val control = CollaborationCommandFixture()
        val id = "aaaaaaaa-0000-0000-0000-000000000001"
        val denial =
            Failure(
                FailureKind.AccessDenied,
                "Fixture denial",
                code = "collaboration_access_denied",
            )
        var conversation: CollaborationConversationCommandRecord? = null
        val resources = mutableListOf<String>()
        val commands =
            object : CollaborationConversationCommandRepository {
                override suspend fun current(
                    owner: CollaborationOwner,
                    resourceId: String,
                ): AppResult<CollaborationConversationCommandRecord?> {
                    resources += resourceId
                    return AppResult.Success(conversation)
                }

                override suspend fun get(
                    owner: CollaborationOwner,
                    commandId: String,
                ): AppResult<CollaborationConversationCommandRecord?> =
                    error("No lookup by operation")

                override suspend fun insert(
                    owner: CollaborationOwner,
                    command: CollaborationConversationCommand,
                ): AppResult<CollaborationSetupInsertion> = error("No preparation")

                override suspend fun update(
                    owner: CollaborationOwner,
                    expected: CollaborationConversationCommandRecord,
                    outcome: CollaborationConversationOutcome,
                    attempts: Int,
                ): AppResult<Boolean> = error("No replay")

                override suspend fun remove(
                    owner: CollaborationOwner,
                    expected: CollaborationConversationCommandRecord,
                ): AppResult<Boolean> = error("No retirement")
            }

        fun retain(
            action: CollaborationConversationAction
        ): CollaborationConversationCommandRecord =
            CollaborationConversationCommandRecord(
                    CollaborationConversationCommand(
                        "operation",
                        control.owner.setupBinding(),
                        control.access.now,
                        action,
                    ),
                    2,
                    1,
                    CollaborationConversationOutcome.Unconfirmed(),
                )
                .also { conversation = it }

        fun captured(): CollaborationLocalScope.Captured {
            val owner =
                control.owner.copy(
                    accountId = deliveryId(90),
                    companyId = deliveryId(91),
                    nativeSessionId = deliveryId(92),
                )
            val previous = requireNotNull(control.access.session)
            control.access.session =
                previous.copy(
                    user = previous.user.copy(id = owner.accountId),
                    binding = previous.binding.copy(serverId = owner.nativeSessionId),
                )
            control.access.workspace = null
            control.access.beforeWorkspace = { error("Captured control cannot read selection") }
            control.access.now += 365.days
            control.record =
                control.record?.let {
                    it.copy(command = it.command.copy(binding = owner.setupBinding()))
                }
            conversation =
                conversation?.let {
                    it.copy(command = it.command.copy(binding = owner.setupBinding()))
                }
            return CollaborationLocalScope.Captured(
                CollaborationLocalAccess(
                    owner.sessionId,
                    owner.audience,
                    owner.accountId,
                    owner.companyId,
                )
            )
        }

        fun reader() =
            ReadCollaborationControl(
                control.commands,
                commands,
                control.access.admission.repository,
                control.access.sessions,
                control.access.workspaces,
                control.server,
                control.build.copy(number = 6),
                control.clock,
            )
    }
}
