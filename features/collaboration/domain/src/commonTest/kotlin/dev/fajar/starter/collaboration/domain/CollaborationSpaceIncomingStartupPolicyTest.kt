package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingStartupPolicyTest {
    @Test
    fun completedBootstrapPublishesOnlyStableLocalCoordinates() {
        val f = SpaceIncomingStartupFixture()
        val value =
            assertIs<AppResult.Success<CollaborationSpaceIncomingStartup?>>(f.candidate()).value
        assertEquals(
            CollaborationSpaceIncomingStartup(
                f.owner,
                f.setup.registrationId,
                f.adoption.operation,
                f.setup.registration.receipt.id,
            ),
            value,
        )
        assertFalse(value.toString().contains(f.adoption.gateway.accessToken))
        assertFalse(value.toString().contains(f.owner.parent.accountId))
    }

    @Test
    fun absentRegistrationOrSetupAndUnfinishedBootstrapDoNotPublish() {
        val f = SpaceIncomingStartupFixture()
        assertNull(assertIs<AppResult.Success<*>>(f.candidate(registration = null)).value)
        assertNull(assertIs<AppResult.Success<*>>(f.candidate(records = emptyList())).value)
        for (outcome in
            listOf(
                CollaborationSpaceSetupOutcome.Pending,
                CollaborationSpaceSetupOutcome.Unconfirmed(f.setup.base.unavailable),
            )) {
            assertNull(
                assertIs<AppResult.Success<*>>(
                        f.candidate(
                            records =
                                listOf(
                                    f.adoption.record.copy(
                                        outcome = outcome,
                                        revision =
                                            if (outcome is CollaborationSpaceSetupOutcome.Pending) 0
                                            else 2,
                                        attempts =
                                            if (outcome is CollaborationSpaceSetupOutcome.Pending) 0
                                            else 1,
                                    )
                                )
                        )
                    )
                    .value
            )
        }
    }

    @Test
    fun unfinishedRegistrationDoesNotTreatAStoredBootstrapAsCurrent() {
        val f = SpaceIncomingStartupFixture()
        val pending =
            f.setup.registered.copy(
                outcome = CollaborationSpaceRegistrationOutcome.Pending,
                revision = 0,
                attempts = 0,
            )
        assertNull(assertIs<AppResult.Success<*>>(f.candidate(registration = pending)).value)
    }

    @Test
    fun supersededBootstrapCannotWakeItsOldOperation() {
        val f = SpaceIncomingStartupFixture()
        val old =
            f.adoption.record.copy(
                successorId = f.setup.owner.spaceId,
                outcome = CollaborationSpaceSetupOutcome.Unconfirmed(),
            )
        assertNull(assertIs<AppResult.Success<*>>(f.candidate(records = listOf(old))).value)
    }

    @Test
    fun wrongRegistrationBindingDeviceAndPlatformCannotPublish() {
        val f = SpaceIncomingStartupFixture()
        val original = f.adoption.record
        val request = original.command.request as CollaborationSpaceBootstrap
        for (record in
            listOf(
                original.copy(command = original.command.copy(registrationId = f.owner.spaceId)),
                original.copy(
                    command = original.command.copy(platform = CollaborationNativePlatform.IOS)
                ),
                original.copy(
                    command =
                        original.command.copy(request = request.copy(deviceId = f.owner.spaceId))
                ),
                original.copy(
                    command =
                        original.command.copy(
                            request =
                                request.copy(
                                    binding = request.binding.copy(accountId = f.owner.spaceId)
                                )
                        )
                ),
            )) assertIs<AppResult.Failed>(f.candidate(records = listOf(record)))
        assertIs<AppResult.Failed>(
            f.candidate(build = f.setup.build.copy(platform = AppPlatform.Ios))
        )
    }

    @Test
    fun duplicateOversizedAndMalformedInventoriesFailAsAWhole() {
        val f = SpaceIncomingStartupFixture()
        val original = f.adoption.record
        for (records in
            listOf(
                listOf(original, original),
                List(129) { original },
                listOf(original.copy(attempts = -1)),
                listOf(original.copy(revision = -1)),
            )) assertIs<AppResult.Failed>(f.candidate(records = records))
    }

    @Test
    fun denialTakesPrecedenceAndReadmissionRestoresTheSameLocalCoordinates() {
        val f = SpaceIncomingStartupFixture()
        val denied = CollaborationSpaceDirectoryAdmission(f.owner.spaceId, f.setup.base.denied)
        assertEquals(AppResult.Failed(f.setup.base.denied), f.candidate(admission = denied))
        val before = assertIs<AppResult.Success<*>>(f.candidate()).value
        val after = assertIs<AppResult.Success<*>>(f.candidate(admission = f.setup.base.open)).value
        assertEquals(before, after)
    }

    @Test
    fun ordinaryJournalRevisionAndAttemptChangesDoNotChangeTheSignal() {
        val f = SpaceIncomingStartupFixture()
        assertEquals(
            f.candidate(),
            f.candidate(
                registration = f.setup.registered.copy(revision = 19, attempts = 8),
                records = listOf(f.adoption.record.copy(revision = 20, attempts = 9)),
            ),
        )
    }

    @Test
    fun unsupportedPlatformsAndMalformedAdmissionDoNotPublish() {
        val f = SpaceIncomingStartupFixture()
        for (platform in listOf(AppPlatform.Desktop, AppPlatform.Web)) {
            assertIs<AppResult.Failed>(f.candidate(build = f.setup.build.copy(platform = platform)))
        }
        assertIs<AppResult.Failed>(
            f.candidate(admission = CollaborationSpaceDirectoryAdmission("bad", null))
        )
    }
}
