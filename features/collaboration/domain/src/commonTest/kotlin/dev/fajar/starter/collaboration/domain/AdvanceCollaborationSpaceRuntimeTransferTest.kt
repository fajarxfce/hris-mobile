package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class AdvanceCollaborationSpaceRuntimeTransferTest {
    @Test
    fun changedNativeFamilyOrNewDenialAfterPrivateStageNeverFreezesTheOriginal() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceRuntimeTransferFixture()
            f.beforeStage = {
                f.retained = it.intent
                if (deny) f.setup.base.deny()
                else {
                    val current = f.setup.native.session
                    f.setup.registration.session(
                        current.copy(
                            binding = current.binding.copy(serverId = f.setup.owner.spaceId)
                        )
                    )
                }
                AppResult.Success(it.intent)
            }
            val failure = assertIs<AppResult.Failed>(f.run()).failure
            assertEquals(
                if (deny) FailureKind.AccessDenied else FailureKind.Unauthorized,
                failure.kind,
            )
            assertNotNull(f.retained)
            assertFalse("freeze" in f.events)
        }
    }

    @Test
    fun elapsedPublicationRequestConsumesTheWindowBeforeAnyPrivateStage() = runTest {
        val f = SpaceRuntimeTransferFixture()
        f.publication = {
            f.setup.native.time += 3.seconds
            AppResult.Success(
                f.observation(it).copy(expiresAt = f.setup.native.setup.now + 2.seconds)
            )
        }
        assertIs<AppResult.Failed>(f.run())
        assertNull(f.retained)
        assertFalse("stage" in f.events)
    }

    @Test
    fun publicationPrecedesPrivateStoreDrainAndDurableTargetBeforeOneFreezeAndObservation() =
        runTest {
            val f = SpaceRuntimeTransferFixture()
            val progress =
                assertIs<CollaborationSpaceRuntimeTransferProgress.AwaitingProvisioning>(
                    assertIs<AppResult.Success<CollaborationSpaceRuntimeTransferProgress>>(f.run())
                        .value
                )
            assertEquals(
                listOf(
                    "registration",
                    "intent",
                    "status",
                    "publication",
                    "stage",
                    "freeze",
                    "drain",
                ),
                f.events,
            )
            assertEquals(f.retained, progress.transfer.intent)
            assertEquals(0, progress.drain.activeRequests)
            assertEquals(1, f.requests.distinct().size)
            assertTrue(f.setup.registration.sent.isEmpty())
            assertTrue(f.setup.sent.isEmpty())
            assertEquals(0, f.setup.base.admits)
        }

    @Test
    fun lostFreezeResponseReusesOriginalDurableOperationOnTheNextExplicitInvocation() = runTest {
        val f = SpaceRuntimeTransferFixture()
        val failed = AppResult.Failed(Failure(FailureKind.Unavailable, "Unavailable"))
        f.freeze = { failed }
        assertEquals(failed, f.run())
        val original = assertNotNull(f.retained)
        f.freeze = { AppResult.Success(CollaborationSetupReceipt(it.legacy.deviceId, 1)) }
        val progress =
            assertIs<AppResult.Success<CollaborationSpaceRuntimeTransferProgress>>(f.run()).value
        assertEquals(original, progress.transfer.intent)
        assertEquals(1, f.requests.map { it.intent }.distinct().size)
        assertEquals(2, f.events.count { it == "freeze" })
        assertEquals(1, f.events.count { it == "drain" })
    }

    @Test
    fun publicationUsesElapsedRequestTimeAndExpiryDuringStageNeverAllowsFreeze() = runTest {
        val skew = SpaceRuntimeTransferFixture()
        skew.publication = {
            val value = skew.observation(it)
            AppResult.Success(
                value.copy(
                    observedAt = value.observedAt + 120.seconds,
                    expiresAt = value.expiresAt + 120.seconds,
                )
            )
        }
        assertIs<AppResult.Success<*>>(skew.run())
        val f = SpaceRuntimeTransferFixture()
        f.publication = {
            AppResult.Success(
                f.observation(it).copy(expiresAt = f.setup.native.setup.now + 2.seconds)
            )
        }
        f.beforeStage = {
            f.retained = it.intent
            f.setup.native.time += 3.seconds
            AppResult.Success(it.intent)
        }
        assertEquals(
            "client.collaboration_revalidation_required",
            assertIs<AppResult.Failed>(f.run()).failure.code,
        )
        assertNotNull(f.retained)
        assertFalse("freeze" in f.events)
    }

    @Test
    fun failedPrivateKeyProofOrDifferentStagedTargetCannotFreeze() = runTest {
        for (wrongTarget in listOf(false, true)) {
            val f = SpaceRuntimeTransferFixture()
            f.beforeStage = {
                if (wrongTarget) AppResult.Success(it.intent.copy(fingerprint = "b".repeat(64)))
                else AppResult.Failed(f.setup.base.storageFailure)
            }
            assertIs<AppResult.Failed>(f.run())
            assertFalse("freeze" in f.events)
        }
    }

    @Test
    fun accountReplacementAfterEveryExternalStageCannotPublishSuccessOrAdvanceAgain() = runTest {
        for (step in
            listOf("registration", "intent", "status", "publication", "stage", "freeze", "drain")) {
            val f = SpaceRuntimeTransferFixture()
            when (step) {
                "registration" -> f.setup.registration.beforeCurrent = { f.replaceSession() }
                "intent" -> f.beforeIntent = {
                        f.replaceSession()
                        AppResult.Success(null)
                    }
                "status" -> f.setup.registration.beforeStatus = {
                        f.replaceSession()
                        AppResult.Success(
                            CollaborationSpaceDeviceObservation(f.setup.owner, f.setup.status)
                        )
                    }
                "publication" -> f.publication = {
                        f.replaceSession()
                        AppResult.Success(f.observation(it))
                    }
                "stage" -> f.beforeStage = {
                        f.replaceSession()
                        AppResult.Success(it.intent)
                    }
                "freeze" -> f.freeze = {
                        f.replaceSession()
                        AppResult.Success(CollaborationSetupReceipt(it.legacy.deviceId, 1))
                    }
                "drain" -> f.drain = {
                        f.replaceSession()
                        AppResult.Success(
                            CollaborationSpaceRuntimeDrain(
                                it.intent.spaceId,
                                it.intent.deviceId,
                                1,
                                0,
                                null,
                                f.setup.native.setup.now,
                            )
                        )
                    }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.run()).failure.kind,
                step,
            )
            val forbidden =
                when (step) {
                    "registration",
                    "intent",
                    "status" -> "publication"
                    "publication" -> "stage"
                    "stage" -> "freeze"
                    "freeze" -> "drain"
                    else -> "never-another-step"
                }
            assertFalse(forbidden in f.events, step)
        }
    }

    @Test
    fun pendingCancellationClosesEachStageWithoutAnotherRequest() = runTest {
        for (step in listOf("publication", "stage", "freeze", "drain")) {
            val f = SpaceRuntimeTransferFixture()
            val entered = CompletableDeferred<Unit>()
            var closed = 0
            val hold: suspend () -> Nothing = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    closed++
                }
            }
            when (step) {
                "publication" -> f.publication = { hold() }
                "stage" -> f.beforeStage = { hold() }
                "freeze" -> f.freeze = { hold() }
                "drain" -> f.drain = { hold() }
            }
            val task = async { f.run() }
            entered.await()
            task.cancelAndJoin()
            assertEquals(1, closed)
            assertEquals(step, f.events.last())
        }
    }

    @Test
    fun lateUncancellableStageRetainsIntentButCannotFreezeAfterTimeout() = runTest {
        val f = SpaceRuntimeTransferFixture()
        f.beforeStage = {
            withContext(NonCancellable) {
                delay(16_000)
                f.retained = it.intent
            }
            AppResult.Success(it.intent)
        }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertNotNull(f.retained)
        assertFalse("freeze" in f.events)
    }

    @Test
    fun confirmedDenialSurvivesFailedPersistenceAndLaterNetworkFailure() = runTest {
        val f = SpaceRuntimeTransferFixture()
        f.setup.base.afterRestrict = {
            f.setup.base.gate.value = AppResult.Success(f.setup.base.open)
            AppResult.Failed(f.setup.base.storageFailure)
        }
        f.publication = { AppResult.Failed(f.setup.base.denied) }
        assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.setup.base.restrictions)
        assertFalse("stage" in f.events)
        f.setup.base.deny()
        f.publication = { AppResult.Failed(Failure(FailureKind.Unavailable, "Unavailable")) }
        assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertFalse("stage" in f.events)
    }

    @Test
    fun foreignPublicationAndMalformedIntervalsNeverStagePrivateStorage() = runTest {
        for (variant in 0..5) {
            val f = SpaceRuntimeTransferFixture()
            f.publication = {
                val original = f.observation(it)
                AppResult.Success(
                    when (variant) {
                        0 -> original.copy(originalDeviceId = f.setup.owner.spaceId)
                        1 -> original.copy(fingerprint = "b".repeat(64))
                        2 -> original.copy(deviceVersion = original.deviceVersion + 1)
                        3 -> original.copy(spaceId = f.setup.registration.sdkDevice)
                        4 -> original.copy(expiresAt = original.observedAt)
                        else -> original.copy(expiresAt = original.observedAt + 31.seconds)
                    }
                )
            }
            assertIs<AppResult.Failed>(f.run())
            assertFalse("stage" in f.events)
        }
    }

    @Test
    fun changedOriginalNativeParentOrRetainedTargetCannotAcquirePublication() = runTest {
        val parent = SpaceRuntimeTransferFixture()
        val command = parent.setup.registered.command
        val legacy = assertNotNull(command.legacy)
        parent.setup.registration.records.value =
            AppResult.Success(
                parent.setup.registered.copy(
                    command =
                        command.copy(
                            legacy =
                                legacy.copy(
                                    binding =
                                        legacy.binding.copy(
                                            nativeSessionId = parent.setup.owner.spaceId
                                        )
                                )
                        )
                )
            )
        assertIs<AppResult.Failed>(parent.run())
        assertEquals(listOf("registration"), parent.events)
        val target = SpaceRuntimeTransferFixture()
        assertIs<AppResult.Success<*>>(target.run())
        target.retained =
            assertNotNull(target.retained).copy(spaceId = target.setup.registration.sdkDevice)
        target.events.clear()
        assertIs<AppResult.Failed>(target.run())
        assertFalse("publication" in target.events)
    }

    @Test
    fun activeTargetRequiresOriginalIntentAndReturnsOnlyProvisioningMetadata() = runTest {
        val f = SpaceRuntimeTransferFixture()
        assertIs<AppResult.Success<*>>(f.run())
        f.events.clear()
        f.setup.status = f.setup.activeStatus()
        val progress =
            assertIs<CollaborationSpaceRuntimeTransferProgress.Provisioned>(
                assertIs<AppResult.Success<CollaborationSpaceRuntimeTransferProgress>>(f.run())
                    .value
            )
        assertEquals(f.retained, progress.transfer.intent)
        assertEquals(1L, progress.deviceVersion)
        assertEquals(listOf("registration", "intent", "status"), f.events)
        f.retained = null
        assertIs<AppResult.Failed>(f.run())
        assertFalse("stage" in f.events)
    }

    @Test
    fun wrongFreezeReceiptAndInvalidDrainCannotReportSuccessWhileNonzeroSlotsRemainFinite() =
        runTest {
            val wrong = SpaceRuntimeTransferFixture()
            wrong.freeze = { AppResult.Success(CollaborationSetupReceipt(it.intent.deviceId, 1)) }
            assertIs<AppResult.Failed>(wrong.run())
            assertFalse("drain" in wrong.events)
            for (count in listOf(8, 9)) {
                val f = SpaceRuntimeTransferFixture()
                f.drain = {
                    AppResult.Success(
                        CollaborationSpaceRuntimeDrain(
                            it.intent.spaceId,
                            it.intent.deviceId,
                            1,
                            count,
                            f.setup.native.setup.now + 60.seconds,
                            f.setup.native.setup.now,
                        )
                    )
                }
                val result = f.run()
                if (count == 8) assertIs<AppResult.Success<*>>(result)
                else assertIs<AppResult.Failed>(result)
                assertEquals(1, f.events.count { it == "drain" })
            }
        }
}
