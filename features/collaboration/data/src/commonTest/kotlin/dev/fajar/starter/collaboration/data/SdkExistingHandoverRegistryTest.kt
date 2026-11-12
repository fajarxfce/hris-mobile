package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SdkExistingHandoverRegistryTest {
    @Test
    fun closedOriginalStoreExcludesEveryOpenerButNeverGrantsAReplacementClient() {
        val f = ExistingHandoverFixture()
        f.select(f.current)
        val gate = f.begin()
        assertTrue(f.registry.handoverDrained(gate))
        assertTrue(f.registry.existingHandoverCurrent(gate, f.current))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.original, "device"))
        assertIs<AppResult.Failed>(
            f.registry.reserveForeground("store", f.current.owner("company"), "device")
        )
        assertIs<AppResult.Failed>(
            f.registry.reserveBackground("store", f.current.owner("company"), "device")
        )
        assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(gate))
        assertIs<AppResult.Success<*>>(
            f.registry.reserveForeground("independent", f.current.owner("company"), "other-device")
        )
        f.registry.endHandover(gate)
        assertFalse(f.registry.existingHandoverCurrent(gate, f.current))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.original, "device"))
        assertIs<AppResult.Success<*>>(
            f.registry.reserveForeground("store", f.current.owner("company"), "device")
        )
    }

    @Test
    fun pendingOriginalConstructorAndLateNativeResourceRemainOwnedUntilFullRelease() {
        val f = ExistingHandoverFixture()
        f.select(f.oldParent)
        val pending = f.reserve()
        f.select(f.current)
        val gate = f.begin()
        assertNull(f.registry.handoverResource(gate))
        assertFalse(f.registry.handoverDrained(gate))
        val late = ExistingHandoverResource()
        assertFalse(f.registry.attach(pending, late))
        assertEquals(1, late.stops)
        assertSame(late, f.registry.handoverResource(gate))
        assertFalse(f.registry.handoverDrained(gate))
        f.registry.released(pending)
        assertTrue(f.registry.handoverDrained(gate))
        assertIs<AppResult.Failed>(
            f.registry.reserveForeground("store", f.current.owner("company"), "device")
        )
        f.registry.endHandover(gate)
    }

    @Test
    fun localLoginAliasDrainsOnlyTheOriginalAccountCompanyNativeDeviceNamespace() {
        val f = ExistingHandoverFixture()
        f.select(f.oldParent)
        val entry = f.reserve()
        val resource = ExistingHandoverResource()
        f.registry.attach(entry, resource)
        f.select(f.current)
        val alias = f.original.copy(sessionId = f.current.sessionId)
        for (foreign in
            listOf(
                alias.copy(companyId = "other-company"),
                alias.copy(nativeSessionId = "other-native"),
            )) {
            assertIs<AppResult.Failed>(
                f.registry.beginExistingHandover("store", foreign, "device", f.current)
            )
        }
        assertIs<AppResult.Failed>(
            f.registry.beginExistingHandover("store", alias, "other-device", f.current)
        )
        val gate =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginExistingHandover("store", alias, "device", f.current)
                )
                .value
        assertEquals(1, resource.stops)
        assertSame(resource, f.registry.handoverResource(gate))
        assertFalse(f.registry.handoverDrained(gate))
        f.registry.released(entry)
        assertTrue(f.registry.handoverDrained(gate))
        f.registry.endHandover(gate)
    }

    @Test
    fun foreignAccountAudienceOrStaleCurrentParentCannotStartLocalMutation() {
        val f = ExistingHandoverFixture()
        assertIs<AppResult.Failed>(
            f.registry.beginExistingHandover("store", f.original, "device", f.current)
        )
        f.select(f.current)
        for (parent in
            listOf(
                f.current.copy(sessionId = "superseded"),
                f.current.copy(nativeSessionId = "superseded"),
                f.current.copy(accountId = "other"),
                f.current.copy(audience = "https://other.test/"),
            )) {
            assertIs<AppResult.Failed>(
                f.registry.beginExistingHandover("store", f.original, "device", parent)
            )
        }
        for (owner in
            listOf(
                f.original.copy(accountId = "other"),
                f.original.copy(audience = "https://other.test/"),
            )) {
            assertIs<AppResult.Failed>(
                f.registry.beginExistingHandover("store", owner, "device", f.current)
            )
        }
        // Existing live transfer cannot inherit this local-only permission after parent
        // replacement.
        assertIs<AppResult.Failed>(f.registry.beginHandover("store", f.original, "device", null))
        val gate = f.begin()
        assertTrue(f.registry.existingHandoverCurrent(gate, f.current))
        f.registry.endHandover(gate)
    }

    @Test
    fun accountReplacementInvalidatesWriteChecksWhileTheOriginalGateStillOwnsCleanup() {
        val f = ExistingHandoverFixture()
        f.select(f.current)
        val gate = f.begin()
        val next =
            f.current.copy(
                sessionId = "next-login",
                accountId = "other-account",
                nativeSessionId = "next-parent",
            )
        f.select(next)
        assertFalse(f.registry.existingHandoverCurrent(gate, f.current))
        assertFalse(f.registry.existingHandoverCurrent(gate, next))
        assertTrue(f.registry.handoverDrained(gate))
        assertIs<AppResult.Failed>(
            f.registry.reserveForeground("store", next.owner("company"), "device")
        )
        assertIs<AppResult.Failed>(
            f.registry.beginExistingHandover("store", f.original, "device", next)
        )
        f.registry.endHandover(gate)
        assertIs<AppResult.Success<*>>(
            f.registry.reserveForeground("store", next.owner("company"), "device")
        )
    }

    @Test
    fun abandonedGateCapacityIsBoundedAndLateCleanupCannotReleaseANewerAttempt() {
        val f = ExistingHandoverFixture()
        f.select(f.current)
        val gates = (1..5).map { f.begin("store-$it") }
        assertIs<AppResult.Failed>(
            f.registry.beginExistingHandover("extra", f.original, "device", f.current)
        )
        f.registry.endHandover(gates.first())
        val replacement = f.begin("store-1")
        f.registry.endHandover(gates.first())
        assertTrue(f.registry.existingHandoverCurrent(replacement, f.current))
        assertIs<AppResult.Failed>(
            f.registry.reserveForeground("store-1", f.current.owner("company"), "device")
        )
        assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(replacement))
        f.registry.endHandover(replacement)
        gates.drop(1).forEach(f.registry::endHandover)
    }
}

private class ExistingHandoverResource {
    var stops = 0
}

private class ExistingHandoverFixture {
    val oldParent =
        CollaborationNativeParent("old-login", "https://fixture.test/", "account", "old-parent")
    val original = oldParent.owner("company")
    val current = oldParent.copy(sessionId = "current-login", nativeSessionId = "current-parent")
    val registry = SdkRuntimeRegistry<ExistingHandoverResource>({ it.stops == 0 }, { it.stops++ })

    fun select(parent: CollaborationNativeParent) {
        registry.update(CollaborationRuntimeRetention(parent, parent.owner("company")))
    }

    fun reserve() =
        assertIs<AppResult.Success<SdkRuntimeReservation<ExistingHandoverResource>>>(
                registry.reserveForeground("store", original, "device")
            )
            .value

    fun begin(store: String = "store") =
        assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                registry.beginExistingHandover(store, original, "device", current)
            )
            .value
}
