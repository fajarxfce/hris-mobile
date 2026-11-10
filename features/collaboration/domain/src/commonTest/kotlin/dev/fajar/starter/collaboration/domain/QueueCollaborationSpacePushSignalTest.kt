package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationSpacePushSignalTest {
    @Test
    fun nativeHintsMatchRegisteredLogicalDeviceThenPersistBeforeScheduling() = runTest {
        for (platform in listOf(AppPlatform.Android, AppPlatform.Ios)) {
            val f = SpacePushFixture()
            f.registration =
                f.registered.copy(
                    command =
                        f.command.copy(
                            platform =
                                if (platform == AppPlatform.Android)
                                    CollaborationNativePlatform.ANDROID
                                else CollaborationNativePlatform.IOS
                        )
                )
            assertEquals(AppResult.Success(true), f.receiver(platform)(f.fields(true)))
            assertEquals(
                listOf("root", "registration", "wake", "schedule"),
                f.calls.filter { it != "session" },
            )
            assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduled)
            assertNotNull(f.retained)
            assertNotEquals(f.command.id, f.base.receipt.id)
            assertNotEquals(f.base.sdkDevice, f.base.receipt.id)
        }
    }

    @Test
    fun malformedAndUnsupportedPacketsCannotReadStorage() = runTest {
        for (platform in listOf(AppPlatform.Web, AppPlatform.Desktop)) {
            val f = SpacePushFixture()
            assertEquals(AppResult.Success(false), f.receiver(platform)(f.fields()))
            assertTrue(f.calls.isEmpty())
        }
        val f = SpacePushFixture()
        assertEquals(
            AppResult.Success(false),
            f.receiver()(f.fields() + ("companyId" to f.base.company)),
        )
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun wrongAccountOrNativeFamilyCannotReadGroupStorage() = runTest {
        for (key in listOf("accountId", "nativeSessionId")) {
            val f = SpacePushFixture()
            assertEquals(
                AppResult.Success(false),
                f.receiver()(f.fields() + (key to f.base.company)),
            )
            assertEquals(listOf("session"), f.calls)
        }
        for (absent in listOf(false, true)) {
            val f = SpacePushFixture()
            f.session =
                if (absent) null
                else
                    f.initial.copy(
                        binding = f.initial.binding.copy(audience = "https://elsewhere.test/")
                    )
            assertIs<AppResult.Failed>(f.receiver()(f.fields()))
            assertEquals(listOf("session"), f.calls)
        }
    }

    @Test
    fun unknownRootCannotTriggerDiscoveryOrRecovery() = runTest {
        for (root in listOf(null, CollaborationSpaceRoot(SpacePushFixture().base.company))) {
            val f = SpacePushFixture()
            f.root = root
            assertEquals(AppResult.Success(false), f.receiver()(f.fields()))
            assertFalse("registration" in f.calls)
            assertNull(f.retained)
        }
    }

    @Test
    fun absentOrUnconfirmedRegistrationNeverInventsADevice() = runTest {
        val sample = SpacePushFixture()
        for (record in
            listOf(
                null,
                sample.registered.copy(
                    revision = 0,
                    attempts = 0,
                    outcome = CollaborationSpaceRegistrationOutcome.Pending,
                ),
                sample.registered.copy(
                    revision = 1,
                    outcome = CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                ),
            )) {
            val f = SpacePushFixture()
            f.registration = record
            assertEquals(AppResult.Success(false), f.receiver()(f.fields()))
            assertNull(f.retained)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun operationPhysicalDevicePlatformAndFamilyCannotImpersonateLogicalDevice() = runTest {
        val f = SpacePushFixture()
        for (device in listOf(f.command.id, f.base.sdkDevice)) assertEquals(
            AppResult.Success(false),
            f.receiver()(f.fields() + ("deviceId" to device)),
        )
        f.registration =
            f.registered.copy(command = f.command.copy(platform = CollaborationNativePlatform.IOS))
        assertEquals(AppResult.Success(false), f.receiver()(f.fields()))
        f.registration =
            f.registered.copy(
                command =
                    f.command.copy(
                        binding = f.command.binding.copy(nativeSessionId = f.base.company)
                    )
            )
        assertEquals(AppResult.Success(false), f.receiver()(f.fields()))
        assertNull(f.retained)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun invalidStoredRegistrationIsAClassifiedFailure() = runTest {
        val f = SpacePushFixture()
        f.registration = f.registered.copy(revision = -1)
        val failed = assertIs<AppResult.Failed>(f.receiver()(f.fields()))
        assertEquals(FailureKind.Unexpected, failed.failure.kind)
        assertNull(f.retained)
    }

    @Test
    fun expiredCredentialsAndOldHintDoNotEraseLocalOwnedWork() = runTest {
        val f = SpacePushFixture()
        f.session =
            f.initial.copy(
                tokens = f.initial.tokens.copy(expiresAtEpochMillis = 0),
                binding = f.initial.binding.copy(expiresAtEpochMillis = 0),
            )
        f.after = {
            if (it == "wake")
                f.session = f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "renewed"))
        }
        assertEquals(AppResult.Success(true), f.receiver()(f.fields()))
        assertNotNull(f.retained)
    }

    @Test
    fun changedOwnerAfterEveryBoundaryCannotScheduleOrPublishLateSuccess() = runTest {
        for (stage in listOf("root", "registration", "wake", "schedule")) {
            for (logout in listOf(false, true)) {
                val f = SpacePushFixture()
                f.after = {
                    if (it == stage)
                        f.session = if (logout) null else f.initial.copy(id = "replacement")
                }
                val failed = assertIs<AppResult.Failed>(f.receiver()(f.fields()))
                assertEquals(FailureKind.Unauthorized, failed.failure.kind)
                assertEquals(stage in listOf("wake", "schedule"), f.retained != null)
                assertEquals(if (stage == "schedule") 1 else 0, f.scheduled.size)
            }
        }
    }

    @Test
    fun eachLocalFailurePreservesClassificationAndPreviouslyCommittedWake() = runTest {
        for (stage in listOf("session", "root", "registration", "wake", "schedule")) {
            val f = SpacePushFixture()
            f.failedStage = stage
            assertEquals(AppResult.Failed(f.storage), f.receiver()(f.fields()))
            assertEquals(stage == "schedule", f.retained != null)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun cancelledEntryNeverReadsOrWrites() = runTest {
        val f = SpacePushFixture()
        val job = launch {
            currentCoroutineContext().cancel()
            f.receiver()(f.fields())
            error("Cancelled result")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun pendingReadsCommitAndSchedulerAreJoinedOnCancellation() = runTest {
        for (stage in listOf("session", "root", "registration", "beforeWake", "beforeSchedule")) {
            val f = SpacePushFixture()
            val entered = CompletableDeferred<Unit>()
            var released = false
            f.after = {
                if (it == stage) {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                }
            }
            val job = launch {
                f.receiver()(f.fields())
                error("Cancelled result")
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(released)
            assertEquals(stage == "beforeSchedule", f.retained != null)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun cancellationAfterCommitAndSchedulingCannotEraseOriginalRecovery() = runTest {
        for (stage in listOf("wake", "schedule")) {
            val f = SpacePushFixture()
            f.after = { if (it == stage) currentCoroutineContext().cancel() }
            val job = launch {
                f.receiver()(f.fields())
                error("Cancelled result")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertNotNull(f.retained)
            assertEquals(if (stage == "schedule") 1 else 0, f.scheduled.size)
        }
    }

    @Test
    fun localAndPlatformTimeoutsStopOwnedWorkAndRetainCommittedWake() = runTest {
        for (stage in listOf("root", "beforeSchedule")) {
            val before = testScheduler.currentTime
            val f = SpacePushFixture()
            var released = false
            f.after = {
                if (it == stage)
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
            }
            val failed = assertIs<AppResult.Failed>(f.receiver()(f.fields()))
            assertEquals(FailureKind.Timeout, failed.failure.kind)
            assertEquals(
                if (stage == "root") 3_000 else 1_500,
                (testScheduler.currentTime - before).toInt(),
            )
            assertTrue(released)
            assertEquals(stage == "beforeSchedule", f.retained != null)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun repeatedHintsRotateTheWakeWithoutAnyReceiverOwnedRetry() = runTest {
        val f = SpacePushFixture()
        assertEquals(AppResult.Success(true), f.receiver()(f.fields()))
        val first = f.retained
        assertEquals(AppResult.Success(true), f.receiver()(f.fields()))
        assertNotEquals(first, f.retained)
        assertEquals(2, f.writes)
        assertEquals(List(2) { CollaborationSpaceRecoveryWake.WORK_KEY }, f.scheduled)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun newSpaceOriginUsesItsRegisteredDeviceWithoutRequiringALegacySdk() = runTest {
        val f = SpacePushFixture()
        f.registration =
            f.registered.copy(
                command =
                    f.command.copy(origin = CollaborationSpaceDeviceOrigin.SPACE, legacy = null)
            )
        assertEquals(AppResult.Success(true), f.receiver()(f.fields()))
        assertNotNull(f.retained)
    }
}
