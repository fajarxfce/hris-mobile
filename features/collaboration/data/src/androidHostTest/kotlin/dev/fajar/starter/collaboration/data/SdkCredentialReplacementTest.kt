package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SdkCredentialReplacementTest {
    @Test
    fun rejectedClientAndBorrowersDrainBeforeAtomicReplacementAndLateReleaseCannotStealIt() {
        val f = ReplacementFixture()
        val borrow =
            assertIs<AppResult.Success<SdkRuntimeBorrow<ReplacementResource>>>(
                    f.registry.reserveBackground("store", f.owner, "device")
                )
                .value
        f.original.available = false
        val gate = f.begin()
        assertNotNull(borrow.failures.value)
        assertEquals(1, f.original.stops)
        assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(gate))
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        f.registry.released(f.entry)
        assertTrue(f.registry.handoverDrained(gate))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.owner, "device"))
        val replacement =
            assertIs<AppResult.Success<SdkRuntimeReservation<ReplacementResource>>>(
                    f.registry.reserveCredentialReplacement(gate)
                )
                .value
        f.registry.released(f.entry)
        f.registry.endHandover(gate)
        assertTrue(f.registry.accepts(replacement))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.owner, "device"))
        assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(gate))
    }

    @Test
    fun foreignOwnerDeviceOrStaleClientCannotRetireTheOriginal() {
        val f = ReplacementFixture()
        assertIs<AppResult.Failed>(
            f.registry.beginCredentialReplacement(
                "store",
                f.owner.copy(sessionId = "foreign"),
                "device",
                f.original,
            )
        )
        assertIs<AppResult.Failed>(
            f.registry.beginCredentialReplacement("store", f.owner, "foreign", f.original)
        )
        assertIs<AppResult.Failed>(
            f.registry.beginCredentialReplacement("store", f.owner, "device", ReplacementResource())
        )
        assertEquals(0, f.original.stops)
        assertSame(f.original, f.registry.find(f.owner, "device"))
    }

    @Test
    fun originalIdentityHandoverNeverAuthorizesCredentialReplacement() {
        val f = ReplacementFixture()
        val gate =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginHandover("store", f.owner, "device", f.original)
                )
                .value
        f.registry.released(f.entry)
        assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(gate))
        assertTrue(f.registry.handoverDrained(gate))
        f.registry.endHandover(gate)
        assertIs<AppResult.Success<*>>(f.registry.reserveForeground("store", f.owner, "device"))
    }

    @Test
    fun parentOrForegroundReplacementDuringDrainPreventsInstallation() {
        for (parentChanged in listOf(false, true)) {
            val f = ReplacementFixture()
            val gate = f.begin()
            f.registry.released(f.entry)
            val nextParent =
                if (parentChanged) f.parent.copy(nativeSessionId = "new-parent") else f.parent
            val nextOwner = nextParent.owner(if (parentChanged) "company" else "other-company")
            f.registry.update(CollaborationRuntimeRetention(nextParent, nextOwner))
            assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(gate))
            f.registry.endHandover(gate)
            assertIs<AppResult.Success<*>>(
                f.registry.reserveForeground("store", nextOwner, "device")
            )
        }
    }

    @Test
    fun unfinishedDrainStaysCountedEvenIfTheReplacementWaiterStops() {
        val f = ReplacementFixture()
        val gate = f.begin()
        repeat(4) { index ->
            assertIs<AppResult.Success<*>>(
                f.registry.reserveForeground("other-$index", f.owner, "device-$index")
            )
        }
        f.registry.endHandover(gate)
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.owner, "device"))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("extra", f.owner, "extra"))
        assertSame(f.original, f.registry.resources().single())
        f.registry.released(f.entry)
        assertIs<AppResult.Success<*>>(f.registry.reserveForeground("store", f.owner, "device"))
    }

    @Test
    fun capacityIsRecheckedWithoutDroppingTheExclusiveGate() {
        val f = ReplacementFixture()
        val gate = f.begin()
        f.registry.released(f.entry)
        val others =
            (0..4).map { index ->
                assertIs<AppResult.Success<SdkRuntimeReservation<ReplacementResource>>>(
                        f.registry.reserveForeground("other-$index", f.owner, "device-$index")
                    )
                    .value
            }
        assertIs<AppResult.Failed>(f.registry.reserveCredentialReplacement(gate))
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        f.registry.released(others.first())
        assertIs<AppResult.Success<*>>(f.registry.reserveCredentialReplacement(gate))
    }

    @Test
    fun anotherForegroundCannotStartReplacingTheRetainedBackgroundClient() {
        val f = ReplacementFixture()
        val borrow =
            assertIs<AppResult.Success<SdkRuntimeBorrow<ReplacementResource>>>(
                    f.registry.reserveBackground("store", f.owner, "device")
                )
                .value
        f.registry.update(CollaborationRuntimeRetention(f.parent, f.parent.owner("other")))
        assertNull(borrow.failures.value)
        assertIs<AppResult.Failed>(
            f.registry.beginCredentialReplacement("store", f.owner, "device", f.original)
        )
        assertEquals(0, f.original.stops)
    }

    @Test
    fun competingReplacementRequestsRetireTheOriginalExactlyOnce() {
        val f = ReplacementFixture()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val requests =
                (1..2).map {
                    pool.submit<AppResult<SdkRuntimeHandoverGate>> {
                        check(start.await(2, TimeUnit.SECONDS))
                        f.registry.beginCredentialReplacement(
                            "store",
                            f.owner,
                            "device",
                            f.original,
                        )
                    }
                }
            start.countDown()
            val results = requests.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, results.filterIsInstance<AppResult.Success<*>>().size)
            assertEquals(1, results.filterIsInstance<AppResult.Failed>().size)
            assertEquals(1, f.original.stops)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}

private class ReplacementResource(var available: Boolean = true) {
    var stops = 0
}

private class ReplacementFixture {
    val parent = CollaborationNativeParent("local", "https://fixture.test/", "account", "parent")
    val owner = parent.owner("company")
    val registry =
        SdkRuntimeRegistry<ReplacementResource>({ it.available && it.stops == 0 }, { it.stops++ })
    val original = ReplacementResource()
    val entry: SdkRuntimeReservation<ReplacementResource>

    init {
        registry.update(CollaborationRuntimeRetention(parent, owner))
        entry =
            assertIs<AppResult.Success<SdkRuntimeReservation<ReplacementResource>>>(
                    registry.reserveForeground("store", owner, "device")
                )
                .value
        registry.attach(entry, original)
    }

    fun begin() =
        assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                registry.beginCredentialReplacement("store", owner, "device", original)
            )
            .value
}
