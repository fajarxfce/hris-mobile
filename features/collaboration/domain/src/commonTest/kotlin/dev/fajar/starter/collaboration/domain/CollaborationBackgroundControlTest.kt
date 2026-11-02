package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationBackgroundControlTest {
    @Test
    fun backgroundSetupReadmitsTheMatchingActiveDeviceWithoutSelection() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        f.setup.admission.deny(companyDenial())
        val read = assertIs<AppResult.Success<CollaborationSetup>>(f.setup.loader()(scope)).value
        assertEquals(scope.admission.owner, read.owner)
        assertEquals(COMMAND_DEVICE, read.device?.id)
        assertNull(f.setup.admission.gate.denial)
        assertEquals(1, f.setup.calls)
        assertNull(f.setup.workspace)
    }

    @Test
    fun mismatchedOrInactiveSetupCannotReopenAFormerlyDeniedProjection() = runTest {
        for (kind in 0..4) {
            val f = ConversationCommandFixture()
            val scope = f.controlBackground(TestTimeSource())
            val denied = companyDenial()
            f.setup.admission.deny(denied)
            val device = f.setup.device.copy(id = COMMAND_DEVICE)
            f.setup.onRead = {
                AppResult.Success(
                    when (kind) {
                        0 -> device.copy(id = deliveryId(99))
                        1 -> device.copy(version = 0)
                        2 -> device.copy(state = CollaborationDeviceState.REVOKED)
                        3 -> device.copy(platform = CollaborationNativePlatform.IOS)
                        else -> null
                    }
                )
            }
            assertEquals(AppResult.Failed(denied), f.setup.loader()(scope))
            assertEquals(denied, f.setup.admission.gate.denial)
        }
    }

    @Test
    fun expiredOrConcurrentlyDeniedSetupCannotClearTheGate() = runTest {
        for (expire in listOf(false, true)) {
            val f = ConversationCommandFixture()
            val elapsed = TestTimeSource()
            val scope = f.controlBackground(elapsed)
            val denied = companyDenial()
            if (expire) f.setup.admission.deny(denied)
            f.setup.onRead = {
                if (expire) elapsed += 60.seconds else f.setup.admission.deny(denied)
                AppResult.Success(f.setup.device.copy(id = COMMAND_DEVICE))
            }
            assertEquals(AppResult.Failed(denied), f.setup.loader()(scope))
            assertEquals(denied, f.setup.admission.gate.denial)
        }
    }

    @Test
    fun cancelledSetupResponseStillRetainsItsNewMfaDenial() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        val denied = Failure(FailureKind.AccessDenied, "Verify sign in.", code = "mfa_required")
        f.setup.admission.restrictFailure = Failure(FailureKind.Storage, "Unavailable")
        f.setup.onRead = {
            currentCoroutineContext().cancel()
            AppResult.Failed(denied)
        }
        val task = launch { f.setup.loader()(scope) }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(denied, f.setup.admission.gate.denial)
    }

    @Test
    fun backgroundOpenAndRoomCommandsUseTheSameReceiptAndRetirementWithoutSelection() = runTest {
        for (room in listOf(false, true)) {
            val f = ConversationCommandFixture()
            val scope = f.controlBackground(TestTimeSource())
            val action =
                if (room) roomAction() else CollaborationConversationAction.Open(COMMAND_PEER)
            val record = f.prepared(scope, action)
            assertTrue(f.sent.isEmpty())
            assertIs<AppResult.Success<*>>(f.replay()(scope, record.command.id))
            if (room) f.room = f.room.copy(version = 0, state = CollaborationDirectRoomState.QUEUED)
            assertEquals(AppResult.Success(true), f.reconcile()(scope, record.command.id))
            assertEquals(listOf(record.command.id to action), f.sent)
            assertTrue(f.values.isEmpty())
            assertNull(f.setup.workspace)
        }
    }

    @Test
    fun anotherDeviceCannotPrepareACommandWithThisAdmission() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        assertIs<AppResult.Failed>(f.prepare()(scope, roomAction().copy(deviceId = deliveryId(99))))
        assertEquals(0, f.setup.calls)
        assertTrue(f.values.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun retainedOtherDeviceCommandCannotReplayOrRetire() = runTest {
        for (retire in listOf(false, true)) {
            val f = ConversationCommandFixture()
            val scope = f.controlBackground(TestTimeSource())
            val record = f.prepared(scope, roomAction())
            val retained =
                record.copy(
                    command =
                        record.command.copy(action = roomAction().copy(deviceId = deliveryId(99))),
                    outcome =
                        if (retire)
                            CollaborationConversationOutcome.Confirmed(
                                CollaborationSetupReceipt(COMMAND_ROOM, 0)
                            )
                        else record.outcome,
                )
            f.values[record.command.id] = retained
            val reads = mutableListOf<String>()
            f.beforeRemote = { reads += it }
            val result =
                if (retire) f.reconcile()(scope, record.command.id)
                else f.replay()(scope, record.command.id)
            assertIs<AppResult.Failed>(result)
            assertEquals(retained, f.values[record.command.id])
            assertTrue(reads.isEmpty())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun expiredPreparationKeepsTheAtomicIntentButDoesNotPublishOrSendIt() = runTest {
        val f = ConversationCommandFixture()
        val elapsed = TestTimeSource()
        val scope = f.controlBackground(elapsed)
        f.beforeStorage = { if (it == "insert") elapsed += 60.seconds }
        assertIs<AppResult.Failed>(
            f.prepare()(scope, CollaborationConversationAction.Open(COMMAND_PEER))
        )
        assertEquals(1, f.values.size)
        assertTrue(f.sent.isEmpty())
        assertNull(f.setup.admission.gate.denial)
    }

    @Test
    fun replacedNativeParentCannotPublishALateCommandResponse() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        val record = f.prepared(scope)
        f.beforeRemote = {
            val session = requireNotNull(f.setup.session)
            f.setup.session =
                session.copy(binding = session.binding.copy(serverId = deliveryId(99)))
        }
        assertIs<AppResult.Failed>(f.replay()(scope, record.command.id))
        assertIs<CollaborationConversationOutcome.Unconfirmed>(
            f.values.getValue(record.command.id).outcome
        )
        assertEquals(1, f.sent.size)
    }

    @Test
    fun cancelledNewMfaDenialRetainsTheGateDespiteFailedCleanup() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        val record = f.prepared(scope)
        val denied = Failure(FailureKind.AccessDenied, "Verify sign in.", code = "mfa_required")
        f.remoteFailure = denied
        f.setup.admission.restrictFailure = Failure(FailureKind.Storage, "Unavailable")
        f.beforeRemote = { currentCoroutineContext().cancel() }
        val task = launch { f.replay()(scope, record.command.id) }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(denied, f.setup.admission.gate.denial)
        assertNotNull(f.values[record.command.id])
        assertEquals(1, f.sent.size)
    }

    @Test
    fun explicitlyReadmittedRetryDoesNotRecloseTheGateFromAHistoricalDenial() = runTest {
        val f = ConversationCommandFixture()
        val elapsed = TestTimeSource()
        var scope = f.controlBackground(elapsed)
        val record = f.prepared(scope)
        val denied = companyDenial()
        f.values[record.command.id] =
            record.copy(
                revision = 1,
                attempts = 1,
                outcome = CollaborationConversationOutcome.Unconfirmed(denied),
            )
        f.setup.admission.deny(denied)
        assertEquals(AppResult.Failed(denied), f.replay()(scope, record.command.id))
        assertTrue(f.sent.isEmpty())
        assertIs<AppResult.Success<*>>(f.setup.loader()(scope))
        scope = f.controlBackground(elapsed)
        val recovered =
            assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(
                    f.replay()(scope, record.command.id)
                )
                .value
        assertIs<CollaborationConversationOutcome.Confirmed>(recovered.outcome)
        assertEquals(2, recovered.attempts)
        assertEquals(record.command.id, f.sent.single().first)
        assertNull(f.setup.admission.gate.denial)
    }

    @Test
    fun aHistoricalDenialStillHasFailurePriorityButIsNotANewServerDenial() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        val record = f.prepared(scope)
        val denied = companyDenial()
        f.values[record.command.id] =
            record.copy(
                revision = 1,
                attempts = 1,
                outcome = CollaborationConversationOutcome.Unconfirmed(denied),
            )
        f.remoteFailure = Failure(FailureKind.Network, "Offline")
        assertEquals(AppResult.Failed(denied), f.replay()(scope, record.command.id))
        assertEquals(
            denied,
            assertIs<CollaborationConversationOutcome.Unconfirmed>(
                    f.values.getValue(record.command.id).outcome
                )
                .failure,
        )
        assertNull(f.setup.admission.gate.denial)
        assertEquals(1, f.sent.size)
    }

    @Test
    fun newlyReceivedCompanyDenialSurvivesFailedOwnerReadDuringPrepareAndRetirement() = runTest {
        for (retire in listOf(false, true)) {
            val f = ConversationCommandFixture()
            val scope = f.controlBackground(TestTimeSource())
            val record =
                if (retire)
                    f.prepared(scope).also {
                        assertIs<AppResult.Success<*>>(f.replay()(scope, it.command.id))
                    }
                else null
            val denied = companyDenial()
            val storage = Failure(FailureKind.Storage, "Unavailable")
            f.setup.admission.restrictFailure = storage
            if (retire) {
                f.remoteFailure = denied
                f.beforeRemote = { f.setup.sessionFailure = storage }
            } else
                f.setup.onRead = {
                    f.setup.sessionFailure = storage
                    AppResult.Failed(denied)
                }
            val result =
                if (retire) f.reconcile()(scope, requireNotNull(record).command.id)
                else f.prepare()(scope, roomAction())
            assertEquals(AppResult.Failed(denied), result)
            assertEquals(denied, f.setup.admission.gate.denial)
            if (retire) assertNotNull(f.values[record!!.command.id])
            else assertTrue(f.values.isEmpty())
        }
    }

    @Test
    fun retainedCompanyDenialStopsAllThreePathsBeforeReadingCommands() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        val record = f.prepared(scope)
        val denied = companyDenial()
        f.setup.admission.deny(denied)
        f.beforeStorage = { error("Denied control cannot read commands") }
        assertEquals(
            AppResult.Failed(denied),
            f.prepare()(scope, CollaborationConversationAction.Open(COMMAND_PEER)),
        )
        assertEquals(AppResult.Failed(denied), f.replay()(scope, record.command.id))
        assertEquals(AppResult.Failed(denied), f.reconcile()(scope, record.command.id))
        assertTrue(f.sent.isEmpty())
        assertEquals(1, f.values.size)
    }

    @Test
    fun recipientFailureDoesNotCloseTheCompanyAndRetryKeepsItsOriginalOperation() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        val record = f.prepared(scope)
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Unavailable",
                code = "collaboration_recipient_unavailable",
            )
        f.remoteFailure = denied
        assertEquals(AppResult.Failed(denied), f.replay()(scope, record.command.id))
        assertNull(f.setup.admission.gate.denial)
        f.remoteFailure = null
        assertIs<AppResult.Success<*>>(f.replay()(scope, record.command.id))
        assertTrue(f.sent.all { it.first == record.command.id })
        assertEquals(2, f.sent.size)
    }

    @Test
    fun cancellationDuringBackgroundTransportRetainsTheOriginalForAnExplicitRetry() = runTest {
        val f = ConversationCommandFixture()
        val scope = f.controlBackground(TestTimeSource())
        val record = f.prepared(scope)
        val entered = CompletableDeferred<Unit>()
        f.beforeRemote = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.replay()(scope, record.command.id) }
        entered.await()
        task.cancelAndJoin()
        assertEquals(1, f.values.getValue(record.command.id).attempts)
        f.beforeRemote = {}
        assertIs<AppResult.Success<*>>(f.replay()(scope, record.command.id))
        assertEquals(f.sent[0], f.sent[1])
    }
}

private fun ConversationCommandFixture.controlBackground(
    elapsed: TestTimeSource
): CollaborationOperationScope.Background {
    val session = requireNotNull(setup.session)
    setup.session =
        session.copy(
            user = session.user.copy(id = deliveryId(90)),
            binding = session.binding.copy(serverId = deliveryId(92)),
        )
    conversation =
        conversation.copy(
            companyId = deliveryId(91),
            firstAccountId = deliveryId(90),
            createdBy = deliveryId(90),
        )
    setup.workspace = null
    setup.beforeWorkspace = { error("Background control cannot read selection") }
    val owner =
        CollaborationOwner(
            session.id,
            session.binding.audience,
            deliveryId(90),
            deliveryId(91),
            deliveryId(92),
        )
    val now = setup.now
    return CollaborationOperationScope.Background(
        CollaborationBackgroundAdmission(
            CollaborationAdmissionObservation(
                owner,
                COMMAND_DEVICE,
                1,
                1,
                1,
                now,
                now + 60.seconds,
            ),
            now,
            now + 60.seconds,
            elapsed.markNow() + 60.seconds,
        )
    )
}

private suspend fun ConversationCommandFixture.prepared(
    scope: CollaborationOperationScope,
    action: CollaborationConversationAction = CollaborationConversationAction.Open(COMMAND_PEER),
) =
    assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(prepare()(scope, action))
        .value

private fun roomAction() =
    CollaborationConversationAction.RequestRoom(COMMAND_ROOM, COMMAND_DEVICE, 1, null)

private fun companyDenial() =
    Failure(FailureKind.AccessDenied, "Unavailable", code = "company_access_denied")
