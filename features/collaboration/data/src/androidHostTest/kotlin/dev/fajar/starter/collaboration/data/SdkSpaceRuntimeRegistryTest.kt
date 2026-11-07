package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SdkSpaceRuntimeRegistryTest {
    @Test
    fun duplicateActivationCannotRestartTheSameLiveOrStillConstructingBootstrap() {
        for (alreadyAttached in listOf(false, true)) {
            val f = SpaceRegistryFixture()
            val entry = f.reserve(f.begin())
            val resource = SpaceRegistryResource()
            if (alreadyAttached) f.registry.attach(entry, resource)
            assertIs<AppResult.Failed>(
                f.registry.beginSpaceAdoption("store", f.originalOwner, "original-device", f.space)
            )
            if (!alreadyAttached) assertTrue(f.registry.attach(entry, resource))
            assertEquals(0, resource.stops)
            assertTrue(f.registry.accepts(entry))
            assertSame(resource, f.registry.findSpace(f.space))
        }
    }

    @Test
    fun staleOrConflictingBootstrapCannotRetireTheCurrentOrganizationRuntime() {
        for (next in
            listOf(
                { v: SdkSpaceRuntimeIdentity -> v.copy(operationId = "older", gatewayVersion = 1) },
                { v: SdkSpaceRuntimeIdentity -> v.copy(operationId = "conflicting") },
                { v: SdkSpaceRuntimeIdentity -> v.copy(gatewayVersion = 3) },
                { v: SdkSpaceRuntimeIdentity ->
                    v.copy(operationId = "foreign", gatewaySessionId = "other", gatewayVersion = 3)
                },
            )) {
            val f = SpaceRegistryFixture()
            val entry = f.reserve(f.begin())
            val resource = SpaceRegistryResource()
            f.registry.attach(entry, resource)
            assertIs<AppResult.Failed>(
                f.registry.beginSpaceAdoption(
                    "store",
                    f.originalOwner,
                    "original-device",
                    next(f.space),
                )
            )
            assertEquals(0, resource.stops)
            assertTrue(f.registry.accepts(entry))
            assertSame(resource, f.registry.findSpace(f.space))
            assertSame(
                resource,
                f.registry.findSpace(f.space.owner, f.space.deviceId, f.space.operationId),
            )
        }
    }

    @Test
    fun staleAttemptCannotRetireANewerReservationBeforeItsNativeClientAttaches() {
        val f = SpaceRegistryFixture()
        val entry = f.reserve(f.begin())
        assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption(
                "store",
                f.originalOwner,
                "original-device",
                f.space.copy(operationId = "older", gatewayVersion = 1),
            )
        )
        val late = SpaceRegistryResource()
        assertTrue(f.registry.attach(entry, late))
        assertEquals(0, late.stops)
        assertTrue(f.registry.accepts(entry))
    }

    @Test
    fun aHigherGatewayGenerationForTheSameSessionMayDrainTheExactOriginalSlot() {
        val f = SpaceRegistryFixture()
        val entry = f.reserve(f.begin())
        val resource = SpaceRegistryResource()
        f.registry.attach(entry, resource)
        val next = f.space.copy(operationId = "successor", gatewayVersion = 3)
        val gate =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginSpaceAdoption("store", f.originalOwner, "original-device", next)
                )
                .value
        assertEquals(1, resource.stops)
        f.registry.released(entry)
        val adopted = f.reserve(gate)
        assertEquals(next, adopted.space)
        assertTrue(f.registry.accepts(adopted))
    }

    @Test
    fun adoptionDrainsTheOriginalAndTransfersTheSamePhysicalSlotAtomically() {
        val f = SpaceRegistryFixture()
        val original = f.original()
        val resource = SpaceRegistryResource()
        f.registry.attach(original, resource)
        val gate = f.begin()
        assertEquals(1, resource.stops)
        assertFalse(f.registry.handoverDrained(gate))
        assertIs<AppResult.Failed>(f.registry.reserveSpaceAdoption(gate))
        assertIs<AppResult.Failed>(
            f.registry.reserveBackground("store", f.originalOwner, "original-device")
        )
        f.registry.released(original)
        assertTrue(f.registry.handoverDrained(gate))
        val adopted = f.reserve(gate)
        assertEquals(original.storeId, adopted.storeId)
        assertEquals(original.owner, adopted.owner)
        assertEquals(original.deviceId, adopted.deviceId)
        assertEquals(f.space, adopted.space)
        assertFalse(f.registry.spaceHandoverCurrent(gate))
        f.registry.endHandover(gate)
        assertTrue(f.registry.accepts(adopted))
        assertIs<AppResult.Failed>(
            f.registry.reserveForeground("store", f.originalOwner, "original-device")
        )
    }

    @Test
    fun aSpaceResourceNeverEscapesThroughLegacyLookupsOrBorrowers() {
        val f = SpaceRegistryFixture()
        val entry = f.reserve(f.begin())
        val resource = SpaceRegistryResource()
        assertTrue(f.registry.attach(entry, resource))
        assertSame(resource, f.registry.findSpace(f.space))
        assertEquals(listOf(resource), f.registry.resources())
        assertTrue(f.registry.legacyResources().isEmpty())
        assertNull(f.registry.find(f.originalOwner, "original-device"))
        assertNull(f.registry.retainForeground(f.originalOwner, "original-device"))
        assertNull(
            assertIs<AppResult.Success<*>>(
                    f.registry.beginHistoryReopen(f.originalOwner, "original-device")
                )
                .value
        )
        assertIs<AppResult.Failed>(
            f.registry.reserveBackground("store", f.originalOwner, "original-device")
        )
        assertIs<AppResult.Failed>(
            f.registry.beginHandover("store", f.originalOwner, "original-device", resource)
        )
        assertIs<AppResult.Failed>(
            f.registry.beginExistingHandover("store", f.originalOwner, "original-device", f.parent)
        )
        assertIs<AppResult.Failed>(
            f.registry.beginCredentialReplacement(
                "store",
                f.originalOwner,
                "original-device",
                resource,
            )
        )
        assertNull(f.registry.findSpace(f.space.copy(operationId = "old-operation")))
        assertNull(f.registry.findSpace(f.space.copy(deviceId = "other-device")))
        assertEquals(0, resource.stops)
    }

    @Test
    fun switchingOrDenyingAnHrCompanyKeepsTheSameSpaceResource() {
        val f = SpaceRegistryFixture()
        val entry = f.reserve(f.begin())
        val resource = SpaceRegistryResource()
        f.registry.attach(entry, resource)
        for (decision in
            listOf(
                CollaborationRuntimeRetention(f.parent, f.parent.owner("other-company")),
                CollaborationRuntimeRetention(f.parent, null, "original-company"),
                CollaborationRuntimeRetention(f.parent, f.originalOwner),
            )) {
            assertTrue(f.registry.update(decision).isEmpty())
            assertTrue(f.registry.closeOtherOwners(decision.foreground).isEmpty())
            assertSame(resource, f.registry.findSpace(f.space))
        }
        assertEquals(0, resource.stops)
        assertTrue(f.registry.accepts(entry))
    }

    @Test
    fun parentChangeInvalidatesPendingAdoptionPermanentlyWhileKeepingCleanupOwned() {
        val f = SpaceRegistryFixture()
        val gate = f.begin()
        f.registry.update(CollaborationRuntimeRetention(null, null))
        f.select()
        assertFalse(f.registry.spaceHandoverCurrent(gate))
        assertTrue(f.registry.handoverDrained(gate))
        assertIs<AppResult.Failed>(f.registry.reserveSpaceAdoption(gate))
        assertIs<AppResult.Failed>(
            f.registry.reserveForeground("store", f.originalOwner, "original-device")
        )
        f.registry.endHandover(gate)
        val next = f.begin()
        f.registry.endHandover(gate)
        assertTrue(f.registry.spaceHandoverCurrent(next))
        f.reserve(next)
    }

    @Test
    fun lateNativeConstructorRetainsCapacityAndCannotPublishAfterParentReplacement() {
        val f = SpaceRegistryFixture()
        val entry = f.reserve(f.begin())
        f.registry.update(
            CollaborationRuntimeRetention(f.parent.copy(nativeSessionId = "replacement"), null)
        )
        val late = SpaceRegistryResource()
        assertFalse(f.registry.attach(entry, late))
        assertEquals(1, late.stops)
        f.select()
        assertFalse(f.registry.accepts(entry))
        assertNull(f.registry.findSpace(f.space))
        val next = f.begin()
        assertFalse(f.registry.handoverDrained(next))
        assertIs<AppResult.Failed>(f.registry.reserveSpaceAdoption(next))
        f.registry.released(entry)
        val successor = f.reserve(next)
        f.registry.released(entry)
        assertTrue(f.registry.accepts(successor))
    }

    @Test
    fun physicalAndLogicalIdentityCannotBeChangedOrDuplicated() {
        val f = SpaceRegistryFixture()
        for (owner in
            listOf(
                f.originalOwner.copy(accountId = "foreign"),
                f.originalOwner.copy(audience = "https://foreign.test/"),
                f.originalOwner.copy(nativeSessionId = "old-native"),
            )) assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption("store", owner, "original-device", f.space)
        )
        assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption(
                "store",
                f.originalOwner,
                "original-device",
                f.space.copy(
                    owner = f.space.owner.copy(parent = f.parent.copy(sessionId = "old-local"))
                ),
            )
        )
        val gate = f.begin()
        assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption("duplicate", f.originalOwner, "other-device", f.space)
        )
        f.reserve(gate)
        assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption(
                "duplicate",
                f.originalOwner,
                "other-device",
                f.space.copy(operationId = "replacement"),
            )
        )
        assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption(
                "store",
                f.originalOwner.copy(companyId = "other"),
                "original-device",
                f.space,
            )
        )
        assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption("store", f.originalOwner, "other-device", f.space)
        )
        assertIs<AppResult.Failed>(
            f.registry.beginSpaceAdoption(
                "store",
                f.originalOwner,
                "original-device",
                f.space.copy(owner = f.space.owner.copy(spaceId = "foreign")),
            )
        )
        val replacement =
            f.begin(space = f.space.copy(operationId = "replacement", gatewayVersion = 3))
        assertTrue(f.registry.spaceHandoverCurrent(replacement))
    }

    @Test
    fun spaceRemovalStopsOnlyItsExactOwnerAndInvalidatesItsPendingGate() {
        val f = SpaceRegistryFixture()
        val first = f.reserve(f.begin())
        val firstResource = SpaceRegistryResource()
        f.registry.attach(first, firstResource)
        val other = f.space.copy(owner = f.space.owner.copy(spaceId = "other"))
        val second = f.reserve(f.begin("other-store", other))
        val secondResource = SpaceRegistryResource()
        f.registry.attach(second, secondResource)
        val pending = f.begin(space = f.space.copy(operationId = "replacement", gatewayVersion = 3))
        f.registry.closeSpace(f.space.owner)
        assertFalse(f.registry.spaceHandoverCurrent(pending))
        assertNull(f.registry.findSpace(f.space))
        assertSame(secondResource, f.registry.findSpace(other))
        assertEquals(1, firstResource.stops)
        assertEquals(0, secondResource.stops)
        f.registry.released(first)
        assertIs<AppResult.Failed>(f.registry.reserveSpaceAdoption(pending))
    }

    @Test
    fun legacyHandoverAlsoCannotRegainAuthorityAfterItsParentReturns() {
        val f = SpaceRegistryFixture()
        val existing =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginExistingHandover(
                        "store",
                        f.originalOwner,
                        "original-device",
                        f.parent,
                    )
                )
                .value
        f.registry.update(CollaborationRuntimeRetention(null, null))
        f.select()
        assertFalse(f.registry.existingHandoverCurrent(existing, f.parent))
        assertIs<AppResult.Failed>(f.registry.reserveSpaceAdoption(existing))
        f.registry.endHandover(existing)
        val original = f.original()
        val resource = SpaceRegistryResource()
        f.registry.attach(original, resource)
        val replacement =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginCredentialReplacement(
                        "store",
                        f.originalOwner,
                        "original-device",
                        resource,
                    )
                )
                .value
        f.registry.released(original)
        f.registry.update(CollaborationRuntimeRetention(null, null))
        f.select()
        assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(replacement))
        assertTrue(f.registry.handoverDrained(replacement))
    }
}

private class SpaceRegistryResource {
    var stops = 0
}

private class SpaceRegistryFixture {
    val parent = CollaborationNativeParent("local", "https://fixture.test/", "account", "native")
    val originalOwner = parent.owner("original-company")
    val space =
        SdkSpaceRuntimeIdentity(
            CollaborationSpaceOwner(parent, "space"),
            "space-device",
            "bootstrap",
            "gateway",
            2,
        )
    val registry = SdkRuntimeRegistry<SpaceRegistryResource>({ it.stops == 0 }, { it.stops++ })

    init {
        select()
    }

    fun select() {
        registry.update(CollaborationRuntimeRetention(parent, originalOwner))
    }

    fun original() =
        assertIs<AppResult.Success<SdkRuntimeReservation<SpaceRegistryResource>>>(
                registry.reserveForeground("store", originalOwner, "original-device")
            )
            .value

    fun begin(store: String = "store", space: SdkSpaceRuntimeIdentity = this.space) =
        assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                registry.beginSpaceAdoption(store, originalOwner, "original-device", space)
            )
            .value

    fun reserve(gate: SdkRuntimeHandoverGate) =
        assertIs<AppResult.Success<SdkRuntimeReservation<SpaceRegistryResource>>>(
                registry.reserveSpaceAdoption(gate)
            )
            .value
}
