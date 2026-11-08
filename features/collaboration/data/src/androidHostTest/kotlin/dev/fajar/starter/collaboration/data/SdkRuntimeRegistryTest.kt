package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SdkRuntimeRegistryTest {
    @Test
    fun inspectionExcludesSdkAcquisitionUntilItsOwnerReleasesEvenAfterInvalidation() {
        val f = RegistryFixture()
        f.select()
        val inspection =
            assertNotNull(
                assertIs<AppResult.Success<SdkRuntimeReservation<RegistryResource>?>>(
                        f.registry.reserveInspection("store", f.owner, "device")
                    )
                    .value
            )
        assertTrue(f.registry.accepts(inspection))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.owner, "device"))
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        assertIs<AppResult.Failed>(f.registry.reserveInspection("store", f.owner, "device"))
        f.registry.update(CollaborationRuntimeRetention(null, null))
        assertFalse(f.registry.accepts(inspection))
        f.select()
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        f.registry.released(inspection)
        val replacement = f.background()
        f.registry.released(inspection)
        assertTrue(f.registry.accepts(replacement.reservation))
    }

    @Test
    fun inspectionPromotesThePublishedBackgroundOwnerWithoutReopeningItsPrivateStore() {
        val f = RegistryFixture()
        f.select()
        val borrow = f.background()
        assertIs<AppResult.Failed>(f.registry.reserveInspection("store", f.owner, "device"))
        val resource = RegistryResource(ready = true)
        f.registry.attach(borrow.reservation, resource)
        assertEquals(
            AppResult.Success(null),
            f.registry.reserveInspection("store", f.owner, "device"),
        )
        assertNull(f.registry.releaseBorrow(borrow, f.finished))
        assertSame(resource, f.registry.find(f.owner, "device"))
        assertEquals(0, resource.stops)
        assertIs<AppResult.Failed>(
            f.registry.reserveInspection("store", f.owner.copy(accountId = "foreign"), "device")
        )
    }

    @Test
    fun competingInspectionAndRuntimeCanOwnOnlyOnePhysicalNamespace() {
        val f = RegistryFixture()
        f.select()
        val threads = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val start = CountDownLatch(1)
                val inspection =
                    threads.submit<AppResult<SdkRuntimeReservation<RegistryResource>?>> {
                        check(start.await(2, TimeUnit.SECONDS))
                        f.registry.reserveInspection("store", f.owner, "device")
                    }
                val runtime =
                    threads.submit<AppResult<SdkRuntimeReservation<RegistryResource>>> {
                        check(start.await(2, TimeUnit.SECONDS))
                        f.registry.reserveForeground("store", f.owner, "device")
                    }
                start.countDown()
                val results =
                    listOf(inspection.get(3, TimeUnit.SECONDS), runtime.get(3, TimeUnit.SECONDS))
                val winning =
                    assertIs<AppResult.Success<SdkRuntimeReservation<RegistryResource>?>>(
                        results.single { it is AppResult.Success }
                    )
                assertEquals(1, results.count { it is AppResult.Failed })
                f.registry.released(assertNotNull(winning.value))
            }
        } finally {
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun admissionRenewalRetainsOnlyThePublishedClientWithoutOpeningOrPromotingAnother() {
        val f = RegistryFixture()
        f.select()
        val original = f.foreground()
        val resource = RegistryResource(ready = true)
        f.registry.attach(original, resource)
        repeat(3) {
            assertTrue(
                f.registry
                    .update(
                        CollaborationRuntimeRetention(f.parent, null, retainedForeground = f.owner)
                    )
                    .isEmpty()
            )
            assertSame(resource, f.registry.find(f.owner, "device"))
            assertNull(f.registry.retainForeground(f.owner, "device"))
            assertIs<AppResult.Failed>(f.registry.reserveForeground("new-store", f.owner, "device"))
            val borrow = f.background()
            assertNull(f.registry.releaseBorrow(borrow, f.finished))
            assertEquals(0, resource.stops)
            f.select()
            assertSame(resource, f.registry.retainForeground(f.owner, "device"))
        }
        assertEquals(
            listOf(resource),
            f.registry.update(CollaborationRuntimeRetention(f.parent, null)),
        )
        assertEquals(1, resource.stops)
    }

    @Test
    fun renewalCannotRetainAnUnpublishedAcquisitionOrPromoteAnIndependentBorrow() {
        val f = RegistryFixture()
        f.select()
        val acquiring = f.foreground()
        f.registry.update(
            CollaborationRuntimeRetention(f.parent, null, retainedForeground = f.owner)
        )
        val late = RegistryResource(ready = true)
        assertFalse(f.registry.attach(acquiring, late))
        assertEquals(1, late.stops)
        f.registry.released(acquiring)
        val borrow = f.background()
        val background = RegistryResource(ready = true)
        f.registry.attach(borrow.reservation, background)
        f.registry.update(
            CollaborationRuntimeRetention(f.parent, null, retainedForeground = f.owner)
        )
        assertNull(f.registry.retainForeground(f.owner, "device"))
        assertSame(background, f.registry.releaseBorrow(borrow, f.finished))
        assertEquals(1, background.stops)
    }

    @Test
    fun aConfirmedDenialAndParentLossOverrideRetainedForeground() {
        repeat(2) { scenario ->
            val f = RegistryFixture()
            f.select()
            val resource = RegistryResource(ready = true)
            f.registry.attach(f.foreground(), resource)
            f.registry.update(
                CollaborationRuntimeRetention(f.parent, null, retainedForeground = f.owner)
            )
            val next =
                if (scenario == 0)
                    CollaborationRuntimeRetention(f.parent, null, f.owner.companyId, f.owner)
                else CollaborationRuntimeRetention(null, null)
            assertEquals(listOf(resource), f.registry.update(next))
            assertEquals(1, resource.stops)
            assertNull(f.registry.find(f.owner, "device"))
            assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        }
    }

    @Test
    fun aForeignRetainedOwnerCannotChangeTheCurrentRegistry() {
        val f = RegistryFixture()
        f.select()
        val resource = RegistryResource(ready = true)
        f.registry.attach(f.foreground(), resource)
        assertFailsWith<IllegalArgumentException> {
            f.registry.update(
                CollaborationRuntimeRetention(
                    f.parent,
                    null,
                    retainedForeground = f.owner.copy(accountId = "foreign"),
                )
            )
        }
        assertSame(resource, f.registry.find(f.owner, "device"))
        assertEquals(0, resource.stops)
    }

    @Test
    fun anIdentityObservationCannotRetireAReplacementClientOrAnAlreadyStoppedOwner() {
        val f = RegistryFixture()
        f.select()
        val old = f.foreground()
        val observed = RegistryResource(ready = true)
        f.registry.attach(old, observed)
        f.registry.closeOtherOwners(null)
        assertIs<AppResult.Failed>(f.registry.beginHandover("store", f.owner, "device", observed))
        f.registry.released(old)
        val replacement = RegistryResource(ready = true)
        f.registry.attach(f.foreground(), replacement)
        assertIs<AppResult.Failed>(f.registry.beginHandover("store", f.owner, "device", observed))
        assertIs<AppResult.Failed>(f.registry.beginHandover("store", f.owner, "device", null))
        assertEquals(0, replacement.stops)
        assertSame(replacement, f.registry.find(f.owner, "device"))
        val gate =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginHandover("store", f.owner, "device", replacement)
                )
                .value
        assertEquals(1, replacement.stops)
        f.registry.endHandover(gate)
    }

    @Test
    fun handoverExcludesFutureOwnersUntilPendingConstructionHasFullyDrained() {
        val f = RegistryFixture()
        f.select()
        val original = f.foreground()
        val gate =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginHandover("store", f.owner, "device", null)
                )
                .value
        assertFalse(f.registry.handoverDrained(gate))
        assertNull(f.registry.handoverResource(gate))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.owner, "device"))
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        val late = RegistryResource(ready = true)
        assertFalse(f.registry.attach(original, late))
        assertEquals(1, late.stops)
        assertSame(late, f.registry.handoverResource(gate))
        assertFalse(f.registry.handoverDrained(gate))
        f.registry.released(original)
        assertTrue(f.registry.handoverDrained(gate))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.owner, "device"))
        f.registry.endHandover(gate)
        assertIs<AppResult.Success<*>>(f.registry.reserveForeground("store", f.owner, "device"))
    }

    @Test
    fun handoverStopsBorrowersAndALateGateReleaseCannotUnblockANewerAttempt() {
        val f = RegistryFixture()
        f.select()
        val entry = f.foreground()
        val resource = RegistryResource(ready = true)
        f.registry.attach(entry, resource)
        val borrow = f.background()
        assertIs<AppResult.Failed>(
            f.registry.beginHandover(
                "store",
                f.owner.copy(sessionId = "foreign"),
                "device",
                resource,
            )
        )
        assertEquals(0, resource.stops)
        val first =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginHandover("store", f.owner, "device", resource)
                )
                .value
        assertNotNull(borrow.failures.value)
        assertEquals(1, resource.stops)
        assertIs<AppResult.Failed>(f.registry.beginHandover("store", f.owner, "device", resource))
        f.registry.endHandover(first)
        f.registry.released(entry)
        val second =
            assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                    f.registry.beginHandover("store", f.owner, "device", null)
                )
                .value
        f.registry.endHandover(first)
        f.registry.released(entry)
        assertTrue(f.registry.handoverDrained(second))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("store", f.owner, "device"))
        f.registry.endHandover(second)
        assertIs<AppResult.Success<*>>(f.registry.reserveForeground("store", f.owner, "device"))
    }

    @Test
    fun abandonedGateCapacityIsBoundedAndParentReplacementDoesNotAuthorizeAnotherGate() {
        val f = RegistryFixture()
        f.select()
        val gates =
            (1..5).map {
                assertIs<AppResult.Success<SdkRuntimeHandoverGate>>(
                        f.registry.beginHandover("store-$it", f.owner, "device", null)
                    )
                    .value
            }
        assertIs<AppResult.Failed>(f.registry.beginHandover("extra", f.owner, "device", null))
        f.registry.endHandover(gates.first())
        assertIs<AppResult.Success<*>>(f.registry.beginHandover("extra", f.owner, "device", null))
        gates.drop(1).forEach(f.registry::endHandover)
        f.registry.update(CollaborationRuntimeRetention(null, null))
        assertIs<AppResult.Failed>(f.registry.beginHandover("new", f.owner, "device", null))
    }

    @Test
    fun historyReopeningAndBorrowingExcludeEachOtherUntilTheirHolderFinishes() {
        val f = RegistryFixture()
        f.select()
        val entry = f.foreground()
        assertIs<AppResult.Failed>(f.registry.beginHistoryReopen(f.owner, "device"))
        f.registry.attach(entry, RegistryResource(ready = true))
        val borrowed = f.background()
        assertIs<AppResult.Failed>(f.registry.beginHistoryReopen(f.owner, "device"))
        f.registry.releaseBorrow(borrowed, f.finished)
        assertSame(
            entry,
            assertIs<AppResult.Success<*>>(f.registry.beginHistoryReopen(f.owner, "device")).value,
        )
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        assertIs<AppResult.Failed>(f.registry.beginHistoryReopen(f.owner, "device"))
        f.registry.endHistoryReopen(entry)
        assertIs<AppResult.Success<*>>(f.registry.reserveBackground("store", f.owner, "device"))
    }

    @Test
    fun lateHistoryReopenCompletionCannotUnblockADifferentStoreReservation() {
        val f = RegistryFixture()
        f.select()
        val original = f.foreground()
        f.registry.attach(original, RegistryResource(ready = true))
        assertIs<AppResult.Success<*>>(f.registry.beginHistoryReopen(f.owner, "device"))
        f.registry.closeOtherOwners(null)
        f.registry.released(original)
        val replacement = f.foreground()
        f.registry.attach(replacement, RegistryResource(ready = true))
        assertIs<AppResult.Success<*>>(f.registry.beginHistoryReopen(f.owner, "device"))
        f.registry.endHistoryReopen(original)
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        f.registry.endHistoryReopen(replacement)
        assertIs<AppResult.Success<*>>(f.registry.reserveBackground("store", f.owner, "device"))
    }

    @Test
    fun pendingAcquisitionsAndUnfinishedShutdownKeepAllFiveSlotsReserved() {
        val f = RegistryFixture()
        val reservations = (1..5).map { f.foreground("store-$it") }
        assertIs<AppResult.Failed>(f.registry.reserveForeground("extra", f.owner, "device"))
        assertTrue(f.registry.closeOtherOwners(null).isEmpty())
        assertIs<AppResult.Failed>(f.registry.reserveForeground("extra", f.owner, "device"))
        val late = RegistryResource()
        assertFalse(f.registry.attach(reservations.first(), late))
        assertEquals(1, late.stops)
        assertNull(f.registry.find(f.owner, "device"))
        f.registry.released(reservations.first())
        assertIs<AppResult.Success<*>>(f.registry.reserveForeground("store-1", f.owner, "device"))
        // A late repeated completion cannot release a different reservation's capacity.
        f.registry.released(reservations.first())
        assertIs<AppResult.Failed>(f.registry.reserveForeground("another", f.owner, "device"))
    }

    @Test
    fun simultaneousAttemptsCannotReserveTheSameStoreEvenWithDifferentLocalOwners() {
        val f = RegistryFixture()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results =
                (1..2).map { number ->
                    pool.submit<AppResult<SdkRuntimeReservation<RegistryResource>>> {
                        check(start.await(2, TimeUnit.SECONDS))
                        f.registry.reserveForeground(
                            "same-store",
                            f.owner.copy(sessionId = "local-$number"),
                            "device",
                        )
                    }
                }
            start.countDown()
            val completed = results.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, completed.count { it is AppResult.Success })
            assertEquals(1, completed.count { it is AppResult.Failed })
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun parentReplacementInvalidatesALateClientBeforeItCanBecomeVisible() {
        val f = RegistryFixture()
        f.select()
        val pending = f.foreground()
        val replacement = f.parent.copy(sessionId = "replacement")
        f.registry.update(CollaborationRuntimeRetention(replacement, replacement.owner("company")))
        val late = RegistryResource(ready = true)
        assertFalse(f.registry.attach(pending, late))
        assertEquals(1, late.stops)
        assertNull(f.registry.find(f.owner, "device"))
        assertIs<AppResult.Failed>(
            f.registry.reserveForeground("store", replacement.owner("company"), "device")
        )
        f.registry.released(pending)
        assertIs<AppResult.Success<*>>(
            f.registry.reserveForeground("store", replacement.owner("company"), "device")
        )
    }

    @Test
    fun releasingABorrowPreservesTheForegroundClientAndNeverRepublishesIt() {
        val f = RegistryFixture()
        f.select()
        val resource = RegistryResource(ready = true)
        f.registry.attach(f.foreground(), resource)
        val borrowed = f.background()
        assertEquals(
            AppResult.Success(Unit),
            f.registry.publish(borrowed) { error("Already published") },
        )
        assertNull(f.registry.releaseBorrow(borrowed, f.finished))
        assertSame(resource, f.registry.find(f.owner, "device"))
        assertEquals(0, resource.stops)
        assertEquals(
            listOf(resource),
            f.registry.update(CollaborationRuntimeRetention(f.parent, null)),
        )
    }

    @Test
    fun changingForegroundCompanyKeepsItsIndependentBorrowUntilRelease() {
        val f = RegistryFixture()
        f.select()
        val resource = RegistryResource(ready = true)
        f.registry.attach(f.foreground(), resource)
        val borrowed = f.background()
        assertTrue(
            f.registry
                .update(CollaborationRuntimeRetention(f.parent, f.parent.owner("other")))
                .isEmpty()
        )
        assertNull(borrowed.failures.value)
        assertEquals(0, resource.stops)
        assertSame(resource, f.registry.releaseBorrow(borrowed, f.finished))
        assertEquals(1, resource.stops)
        assertIs<AppResult.Failed>(f.registry.publish(borrowed) { error("Released") })
    }

    @Test
    fun anExpiredUnpublishedBorrowCannotLeakAClientEvenWhenItsCompanyIsSelected() {
        val f = RegistryFixture()
        f.select()
        val borrow = f.background()
        assertNull(f.registry.releaseBorrow(borrow, f.finished))
        val late = RegistryResource()
        assertFalse(f.registry.attach(borrow.reservation, late))
        assertEquals(1, late.stops)
        assertIs<AppResult.Failed>(f.registry.publish(borrow) { error("Expired") })
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        f.registry.released(borrow.reservation)
        assertIs<AppResult.Success<*>>(f.registry.reserveBackground("store", f.owner, "device"))
    }

    @Test
    fun foregroundEntryCanRetainAPublishedBackgroundClientWithoutItsCloseHandle() {
        val f = RegistryFixture()
        f.select()
        val borrow = f.background()
        val resource = RegistryResource()
        assertTrue(f.registry.attach(borrow.reservation, resource))
        assertNull(f.registry.retainForeground(f.owner, "device"))
        var publications = 0
        repeat(2) {
            assertEquals(
                AppResult.Success(Unit),
                f.registry.publish(borrow) {
                    publications++
                    it.ready = true
                    AppResult.Success(Unit)
                },
            )
        }
        assertEquals(1, publications)
        assertSame(resource, f.registry.retainForeground(f.owner, "device"))
        assertNull(f.registry.releaseBorrow(borrow, f.finished))
        assertEquals(0, resource.stops)
    }

    @Test
    fun companyDenialInvalidatesOnlyThatParentsCompanyAndBlocksItsLatePublication() {
        val f = RegistryFixture()
        f.select()
        val first = f.background()
        val otherOwner = f.parent.owner("other")
        val second =
            assertIs<AppResult.Success<SdkRuntimeBorrow<RegistryResource>>>(
                    f.registry.reserveBackground("other-store", otherOwner, "other-device")
                )
                .value
        val firstResource = RegistryResource()
        val secondResource = RegistryResource()
        f.registry.attach(first.reservation, firstResource)
        f.registry.attach(second.reservation, secondResource)
        assertEquals(
            listOf(firstResource),
            f.registry.update(CollaborationRuntimeRetention(f.parent, null, "company")),
        )
        assertNotNull(first.failures.value)
        assertNull(second.failures.value)
        assertEquals(0, secondResource.stops)
        assertIs<AppResult.Failed>(f.registry.publish(first) { error("Denied") })
        assertEquals(
            AppResult.Success(Unit),
            f.registry.publish(second) {
                it.ready = true
                AppResult.Success(Unit)
            },
        )
    }

    @Test
    fun backgroundAcquisitionRequiresObservedParentAndHasNoWaitingQueue() {
        val f = RegistryFixture()
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        f.select()
        val first = f.background()
        assertIs<AppResult.Failed>(f.registry.reserveBackground("store", f.owner, "device"))
        assertIs<AppResult.Success<*>>(
            f.registry.reserveBackground("second", f.parent.owner("second"), "device-2")
        )
        assertIs<AppResult.Failed>(
            f.registry.reserveBackground("third", f.parent.owner("third"), "device-3")
        )
        f.registry.releaseBorrow(first, f.finished)
        assertIs<AppResult.Success<*>>(
            f.registry.reserveBackground("third", f.parent.owner("third"), "device-3")
        )
    }

    @Test
    fun repeatedParentRetentionPreservesBorrowButLogoutStopsPublishedAndPendingOwners() {
        val f = RegistryFixture()
        f.select()
        val borrow = f.background()
        val resource = RegistryResource(ready = true)
        f.registry.attach(borrow.reservation, resource)
        repeat(3) {
            assertTrue(
                f.registry.update(CollaborationRuntimeRetention(f.parent, f.owner)).isEmpty()
            )
        }
        assertNull(borrow.failures.value)
        assertEquals(listOf(resource), f.registry.update(CollaborationRuntimeRetention(null, null)))
        assertNotNull(borrow.failures.value)
        assertEquals(1, resource.stops)
        assertIs<AppResult.Failed>(f.registry.reserveBackground("new", f.owner, "device"))
        assertIs<AppResult.Failed>(f.registry.reserveForeground("new", f.owner, "device"))
    }
}

private class RegistryResource(var ready: Boolean = false) {
    var stops = 0
}

private class RegistryFixture {
    val parent = CollaborationNativeParent("local", "https://fixture.test/", "account", "parent")
    val owner = parent.owner("company")
    val finished = Failure(FailureKind.Unavailable, "Finished", code = "fixture.finished")
    val registry =
        SdkRuntimeRegistry<RegistryResource>({ it.ready && it.stops == 0 }, { it.stops++ })

    fun select() {
        registry.update(CollaborationRuntimeRetention(parent, owner))
    }

    fun foreground(store: String = "store") =
        assertIs<AppResult.Success<SdkRuntimeReservation<RegistryResource>>>(
                registry.reserveForeground(store, owner, "device")
            )
            .value

    fun background() =
        assertIs<AppResult.Success<SdkRuntimeBorrow<RegistryResource>>>(
                registry.reserveBackground("store", owner, "device")
            )
            .value
}
