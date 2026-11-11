package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationSpaceOriginalIncomingRegistrationPolicyTest {
    @Test
    fun registeredSourcePreservesItsCompanyAndPhysicalDeviceBeforeRoomLookup() {
        val f = SpaceOriginalIncomingLoadFixture()
        val record = assertNotNull(f.registration)
        val legacy = assertNotNull(matched(f))
        assertEquals(record.command.legacy, legacy)
        assertEquals(f.position.companyId, legacy.binding.companyId)
        assertEquals(f.setup.sdkDevice, legacy.deviceId)
        assertNotEquals(f.runtime.deviceId, legacy.deviceId)
        assertEquals(f.source.matrixUserId, legacy.identity.matrixUserId)
        assertEquals(f.source.matrixDeviceId, legacy.identity.matrixDeviceId)
    }

    @Test
    fun roomValidationStillRequiresTheExactCompanyAndCapturedSource() {
        val f = SpaceOriginalIncomingLoadFixture()
        val record = assertNotNull(f.registration)
        assertTrue(
            validCollaborationSpaceOriginalIncomingRegistration(
                f.runtime,
                f.captured,
                f.source,
                record,
                CollaborationNativePlatform.ANDROID,
            )
        )
        val foreign =
            f.captured.copy(
                target =
                    CollaborationSpaceIncomingTarget.Original(
                        f.position.copy(companyId = f.id(999))
                    )
            )
        assertFalse(
            validCollaborationSpaceOriginalIncomingRegistration(
                f.runtime,
                foreign,
                f.source,
                record,
                CollaborationNativePlatform.ANDROID,
            )
        )
        assertFalse(
            validCollaborationSpaceOriginalIncomingRegistration(
                f.runtime,
                f.captured.copy(sourceId = "b".repeat(64)),
                f.source,
                record,
                CollaborationNativePlatform.ANDROID,
            )
        )
        assertFalse(
            validCollaborationSpaceOriginalIncomingRegistration(
                f.runtime,
                f.captured.copy(
                    target = CollaborationSpaceIncomingTarget.Published(f.position.conversationId)
                ),
                f.source,
                record,
                CollaborationNativePlatform.ANDROID,
            )
        )
    }

    @Test
    fun unsupportedSpaceRegistrationNeverInventsAnOriginalCompany() {
        val f = SpaceOriginalIncomingLoadFixture()
        val record = assertNotNull(f.registration)
        f.registration =
            record.copy(
                command =
                    record.command.copy(
                        origin = CollaborationSpaceDeviceOrigin.SPACE,
                        legacy = null,
                    )
            )
        assertNull(matched(f))
    }

    @Test
    fun differentMatrixUserDeviceAndMalformedSourceAreRejected() {
        val f = SpaceOriginalIncomingLoadFixture()
        val record = assertNotNull(f.registration)
        for (source in
            listOf(
                f.source.copy(matrixUserId = "@foreign:matrix.hris.internal"),
                f.source.copy(matrixDeviceId = "FOREIGN_DEVICE"),
                f.source.copy(storeId = "bad"),
            )) assertNull(
            registeredCollaborationSpaceOriginalIncomingSdk(
                f.runtime,
                source,
                record,
                CollaborationNativePlatform.ANDROID,
            )
        )
    }

    @Test
    fun aDifferentGroupReceiptCannotSelectTheOriginalPhysicalSlot() {
        val f = SpaceOriginalIncomingLoadFixture()
        assertNull(
            registeredCollaborationSpaceOriginalIncomingSdk(
                f.runtime.copy(deviceId = f.id(999)),
                f.source,
                assertNotNull(f.registration),
                CollaborationNativePlatform.ANDROID,
            )
        )
    }

    @Test
    fun oldLegacyNativeFamilyCannotBecomeCurrentInstallationAuthority() {
        val f = SpaceOriginalIncomingLoadFixture()
        val record = assertNotNull(f.registration)
        val legacy = assertNotNull(record.command.legacy)
        f.registration =
            record.copy(
                command =
                    record.command.copy(
                        legacy =
                            legacy.copy(binding = legacy.binding.copy(nativeSessionId = f.id(999)))
                    )
            )
        assertNull(matched(f))
    }

    @Test
    fun differentLegacyAccountAudienceAndCompanyCannotBeSelected() {
        val f = SpaceOriginalIncomingLoadFixture()
        val record = assertNotNull(f.registration)
        val legacy = assertNotNull(record.command.legacy)
        for (binding in
            listOf(
                legacy.binding.copy(accountId = f.id(999)),
                legacy.binding.copy(audience = "https://foreign.example"),
                legacy.binding.copy(companyId = f.id(999)),
            )) {
            f.registration =
                record.copy(command = record.command.copy(legacy = legacy.copy(binding = binding)))
            assertNull(matched(f))
        }
    }

    @Test
    fun malformedPhysicalDeviceAndRuntimeOperationAreRejected() {
        val f = SpaceOriginalIncomingLoadFixture()
        val record = assertNotNull(f.registration)
        val legacy = assertNotNull(record.command.legacy)
        f.registration =
            record.copy(command = record.command.copy(legacy = legacy.copy(deviceId = "invalid")))
        assertNull(matched(f))
        assertNull(
            registeredCollaborationSpaceOriginalIncomingSdk(
                f.runtime.copy(operationId = "invalid"),
                f.source,
                record,
                CollaborationNativePlatform.ANDROID,
            )
        )
    }

    @Test
    fun foreignGroupScopeDoesNotInheritTheOriginalRegistration() {
        val f = SpaceOriginalIncomingLoadFixture()
        assertNull(
            registeredCollaborationSpaceOriginalIncomingSdk(
                f.runtime.copy(owner = f.runtime.owner.copy(spaceId = f.id(999))),
                f.source,
                assertNotNull(f.registration),
                CollaborationNativePlatform.ANDROID,
            )
        )
    }

    @Test
    fun anotherPlatformCannotUseThisPhysicalRegistration() {
        val f = SpaceOriginalIncomingLoadFixture()
        assertNull(
            registeredCollaborationSpaceOriginalIncomingSdk(
                f.runtime,
                f.source,
                assertNotNull(f.registration),
                CollaborationNativePlatform.IOS,
            )
        )
    }

    private fun matched(f: SpaceOriginalIncomingLoadFixture) =
        registeredCollaborationSpaceOriginalIncomingSdk(
            f.runtime,
            f.source,
            assertNotNull(f.registration),
            CollaborationNativePlatform.ANDROID,
        )
}
