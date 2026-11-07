package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mechanical resource ownership under one short monitor. Callbacks are synchronous and perform no
 * I/O; every returned resource must be drained outside the monitor. Reservations count before SDK
 * construction, and invalidation never releases an unfinished acquisition's capacity.
 */
internal class SdkRuntimeRegistry<T : Any>(
    private val available: (T) -> Boolean,
    private val requestStop: (T) -> Unit,
) {
    private val monitor = Any()
    private val entries = mutableMapOf<String, SdkRuntimeReservation<T>>()
    private val borrowers = mutableSetOf<SdkRuntimeBorrow<T>>()
    private val handovers = mutableMapOf<String, SdkRuntimeHandoverGate>()
    private val changes = MutableStateFlow(0L)
    val handoverChanges = changes.asStateFlow()
    private var retention: CollaborationRuntimeRetention? = null

    fun reserveForeground(
        storeId: String,
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<SdkRuntimeReservation<T>> =
        synchronized(monitor) {
            if (storeId in handovers) return@synchronized handoverPending()
            val decision = retention
            if (decision != null && (!retainsParent(owner) || decision.foreground != owner))
                return@synchronized stopped()
            if (storeId in entries) return@synchronized busy()
            if (entries.size >= CLIENT_LIMIT) return@synchronized capacity()
            AppResult.Success(
                SdkRuntimeReservation<T>(storeId, owner, deviceId, foreground = true).also {
                    entries[storeId] = it
                }
            )
        }

    /** Existing published resources are borrowed; no other call receives their parent closer. */
    fun reserveBackground(
        storeId: String,
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<SdkRuntimeBorrow<T>> =
        synchronized(monitor) {
            if (storeId in handovers) return@synchronized handoverPending()
            if (retention == null || !retainsParent(owner)) return@synchronized stopped()
            if (borrowers.size >= BORROW_LIMIT) return@synchronized capacity()
            val existing = entries[storeId]
            if (
                existing != null &&
                    (existing.space != null ||
                        existing.owner != owner ||
                        existing.deviceId != deviceId ||
                        existing.retired ||
                        existing.historyReopening ||
                        existing.resource?.let(available) != true ||
                        borrowers.any { it.reservation === existing })
            )
                return@synchronized busy()
            if (existing == null && entries.size >= CLIENT_LIMIT) return@synchronized capacity()
            val entry =
                existing
                    ?: SdkRuntimeReservation<T>(storeId, owner, deviceId, foreground = false).also {
                        entries[storeId] = it
                    }
            AppResult.Success(
                SdkRuntimeBorrow(entry, creating = existing == null).also(borrowers::add)
            )
        }

    /** A late native client is still attached to its reservation so its full drain stays owned. */
    fun attach(reservation: SdkRuntimeReservation<T>, resource: T): Boolean =
        synchronized(monitor) {
            check(entries[reservation.storeId] === reservation && reservation.resource == null)
            reservation.resource = resource
            if (reservation.retired) requestStop(resource)
            changed()
            !reservation.retired
        }

    fun publish(borrow: SdkRuntimeBorrow<T>, publish: (T) -> AppResult<Unit>): AppResult<Unit> =
        synchronized(monitor) {
            borrow.failure.value?.let {
                return@synchronized AppResult.Failed(it)
            }
            val entry = borrow.reservation
            if (
                borrow !in borrowers ||
                    entries[entry.storeId] !== entry ||
                    entry.retired ||
                    entry.space != null ||
                    !retainsParent(entry.owner)
            )
                return@synchronized stopped()
            val resource = entry.resource ?: return@synchronized busy()
            if (!borrow.creating || borrow.published)
                return@synchronized if (available(resource)) AppResult.Success(Unit) else stopped()
            publish(resource).also { if (it is AppResult.Success) borrow.published = true }
        }

    /** Successful foreground entry promotes an already published background-created client. */
    fun retainForeground(owner: CollaborationOwner, deviceId: String): T? =
        synchronized(monitor) {
            val entry = findEntry(owner, deviceId) ?: return@synchronized null
            val resource = entry.resource ?: return@synchronized null
            if (!available(resource)) return@synchronized null
            val decision = retention
            if (decision != null && (!retainsParent(owner) || decision.foreground != owner))
                return@synchronized null
            entry.foreground = true
            resource
        }

    fun find(owner: CollaborationOwner, deviceId: String): T? =
        synchronized(monitor) { findEntry(owner, deviceId)?.resource }

    fun resources(): List<T> = synchronized(monitor) { entries.values.mapNotNull { it.resource } }

    fun accepts(reservation: SdkRuntimeReservation<T>): Boolean =
        synchronized(monitor) {
            entries[reservation.storeId] === reservation && !reservation.retired
        }

    /** Excludes a borrower for the whole suspending history-reopen decision, not just its check. */
    fun beginHistoryReopen(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<SdkRuntimeReservation<T>?> =
        synchronized(monitor) {
            val entry =
                entries.values.singleOrNull {
                    it.space == null && it.owner == owner && it.deviceId == deviceId
                } ?: return@synchronized AppResult.Success(null)
            if (entry.resource == null) return@synchronized busy()
            if (entry.historyReopening || borrowers.any { it.reservation === entry })
                return@synchronized registryFailure("client.collaboration_runtime_busy")
            entry.historyReopening = true
            AppResult.Success(entry)
        }

    fun endHistoryReopen(entry: SdkRuntimeReservation<T>) =
        synchronized(monitor) {
            if (entries[entry.storeId] === entry) entry.historyReopening = false
        }

    fun update(next: CollaborationRuntimeRetention): List<T> =
        synchronized(monitor) {
            val foreground = next.foreground
            require(foreground == null || next.parent?.owner(foreground.companyId) == foreground)
            retention = next
            handovers.values.forEach { gate ->
                val parent = gate.space?.owner?.parent ?: gate.existingStoreParent
                if (if (parent != null) next.parent != parent else !retainsParent(gate.owner))
                    gate.invalidated = true
            }
            entries.values.forEach { entry ->
                val space = entry.space
                if (space != null) {
                    if (next.parent != space.owner.parent) retire(entry)
                } else if (!retainsParent(entry.owner)) retire(entry)
                else {
                    if (next.foreground != entry.owner) entry.foreground = false
                    retireIfIdle(entry)
                }
            }
            closingResources()
        }

    /** Explicit legacy drain invalidates current reservations, not future standalone entries. */
    fun closeOtherOwners(retained: CollaborationOwner?): List<T> =
        synchronized(monitor) {
            entries.values.filter { it.space == null && it.owner != retained }.forEach(::retire)
            closingResources()
        }

    /**
     * Excludes the original physical namespace while a currently admitted space adopts it. The
     * caller still owns durable target/credential/private-key validation under the store lease.
     */
    fun beginSpaceAdoption(
        storeId: String,
        originalOwner: CollaborationOwner,
        originalDeviceId: String,
        space: SdkSpaceRuntimeIdentity,
    ): AppResult<SdkRuntimeHandoverGate> =
        synchronized(monitor) {
            val parent = space.owner.parent
            if (
                retention?.parent != parent ||
                    originalOwner.setupBinding() !=
                        parent.owner(originalOwner.companyId).setupBinding()
            )
                return@synchronized stopped()
            if (storeId in handovers) return@synchronized handoverPending()
            if (handovers.size >= CLIENT_LIMIT) return@synchronized capacity()
            if (
                entries.values.any { it.storeId != storeId && it.space?.owner == space.owner } ||
                    handovers.values.any { it.storeId != storeId && it.space?.owner == space.owner }
            )
                return@synchronized busy()
            val entry = entries[storeId]
            if (
                entry != null &&
                    (entry.owner.setupBinding() != originalOwner.setupBinding() ||
                        entry.deviceId != originalDeviceId ||
                        entry.space?.let {
                            it.owner != space.owner || it.deviceId != space.deviceId
                        } == true)
            )
                return@synchronized busy()
            val gate =
                SdkRuntimeHandoverGate(
                    storeId,
                    originalOwner,
                    originalDeviceId,
                    replacingCredentials = false,
                    space = space,
                )
            handovers[storeId] = gate
            entry?.let(::retire)
            changed()
            AppResult.Success(gate)
        }

    /** Invalidation is permanent for this attempt; cleanup still belongs to the original gate. */
    fun spaceHandoverCurrent(gate: SdkRuntimeHandoverGate): Boolean =
        synchronized(monitor) {
            val space = gate.space
            space != null &&
                handovers[gate.storeId] === gate &&
                !gate.invalidated &&
                retention?.parent == space.owner.parent
        }

    /** Gate-to-reservation transfer is atomic, so no legacy opener can slip into the store. */
    fun reserveSpaceAdoption(gate: SdkRuntimeHandoverGate): AppResult<SdkRuntimeReservation<T>> =
        synchronized(monitor) {
            val space = gate.space ?: return@synchronized handoverPending()
            if (
                handovers[gate.storeId] !== gate ||
                    gate.invalidated ||
                    retention?.parent != space.owner.parent
            )
                return@synchronized stopped()
            if (gate.storeId in entries) return@synchronized busy()
            if (entries.size >= CLIENT_LIMIT) return@synchronized capacity()
            val next =
                SdkRuntimeReservation<T>(
                    gate.storeId,
                    gate.owner,
                    gate.deviceId,
                    foreground = true,
                    space = space,
                )
            entries[gate.storeId] = next
            handovers.remove(gate.storeId)
            changed()
            AppResult.Success(next)
        }

    /** Exact logical identity only; physical legacy owner lookups never expose these resources. */
    fun findSpace(space: SdkSpaceRuntimeIdentity): T? =
        synchronized(monitor) {
            if (retention?.parent != space.owner.parent) return@synchronized null
            entries.values
                .singleOrNull { it.space == space && !it.retired }
                ?.resource
                ?.takeIf(available)
        }

    /** Current-space denial/removal stops its resources without changing original stored facts. */
    fun closeSpace(owner: CollaborationSpaceOwner): List<T> =
        synchronized(monitor) {
            handovers.values.filter { it.space?.owner == owner }.forEach { it.invalidated = true }
            entries.values.filter { it.space?.owner == owner }.forEach(::retire)
            closingResources()
        }

    /** A temporary exact-store exclusion; its owner persists the durable gate after full drain. */
    fun beginHandover(
        storeId: String,
        owner: CollaborationOwner,
        deviceId: String,
        expectedResource: T?,
    ): AppResult<SdkRuntimeHandoverGate> =
        beginExclusiveHandover(
            storeId,
            owner,
            deviceId,
            expectedResource,
            replacingCredentials = false,
        )

    /** Exclude an account-owned original store for local inspection/staging, never SDK restore. */
    fun beginExistingHandover(
        storeId: String,
        originalOwner: CollaborationOwner,
        deviceId: String,
        currentParent: CollaborationNativeParent,
    ): AppResult<SdkRuntimeHandoverGate> =
        synchronized(monitor) {
            if (
                retention?.parent != currentParent ||
                    originalOwner.audience != currentParent.audience ||
                    originalOwner.accountId != currentParent.accountId
            )
                return@synchronized stopped()
            if (storeId in handovers) return@synchronized handoverPending()
            if (handovers.size >= CLIENT_LIMIT) return@synchronized capacity()
            val entry = entries[storeId]
            if (
                entry != null &&
                    (entry.space != null ||
                        entry.owner.setupBinding() != originalOwner.setupBinding() ||
                        entry.deviceId != deviceId)
            )
                return@synchronized busy()
            val gate =
                SdkRuntimeHandoverGate(
                    storeId,
                    originalOwner,
                    deviceId,
                    replacingCredentials = false,
                    existingStoreParent = currentParent,
                )
            handovers[storeId] = gate
            entry?.let(::retire)
            changed()
            AppResult.Success(gate)
        }

    /** Recheck the captured process owner around I/O; an invalidated gate still owns cleanup. */
    fun existingHandoverCurrent(
        gate: SdkRuntimeHandoverGate,
        currentParent: CollaborationNativeParent,
    ): Boolean =
        synchronized(monitor) {
            handovers[gate.storeId] === gate &&
                !gate.invalidated &&
                gate.space == null &&
                gate.existingStoreParent == currentParent &&
                retention?.parent == currentParent
        }

    /** The repository validates the confirmed newer bootstrap before retiring this exact client. */
    fun beginCredentialReplacement(
        storeId: String,
        owner: CollaborationOwner,
        deviceId: String,
        expectedResource: T,
    ): AppResult<SdkRuntimeHandoverGate> =
        beginExclusiveHandover(
            storeId,
            owner,
            deviceId,
            expectedResource,
            replacingCredentials = true,
        )

    private fun beginExclusiveHandover(
        storeId: String,
        owner: CollaborationOwner,
        deviceId: String,
        expectedResource: T?,
        replacingCredentials: Boolean,
    ): AppResult<SdkRuntimeHandoverGate> =
        synchronized(monitor) {
            if (storeId in handovers) return@synchronized handoverPending()
            if (handovers.size >= CLIENT_LIMIT) return@synchronized capacity()
            if (retention != null && !retainsParent(owner)) return@synchronized stopped()
            if (replacingCredentials && retention != null && retention?.foreground != owner)
                return@synchronized stopped()
            val entry = entries[storeId]
            if (
                entry != null &&
                    (entry.space != null || entry.owner != owner || entry.deviceId != deviceId)
            )
                return@synchronized busy()
            if (entry?.resource !== expectedResource) return@synchronized busy()
            if (
                expectedResource != null &&
                    (entry?.retired != false ||
                        (!replacingCredentials && !available(expectedResource)))
            )
                return@synchronized busy()
            val gate = SdkRuntimeHandoverGate(storeId, owner, deviceId, replacingCredentials)
            handovers[storeId] = gate
            entry?.let(::retire)
            changed()
            AppResult.Success(gate)
        }

    /** No background opener can slip between full native drain and replacement reservation. */
    fun reserveCredentialReplacement(
        gate: SdkRuntimeHandoverGate
    ): AppResult<SdkRuntimeReservation<T>> =
        synchronized(monitor) {
            if (handovers[gate.storeId] !== gate || gate.invalidated || !gate.replacingCredentials)
                return@synchronized handoverPending()
            val decision = retention
            if (
                decision != null &&
                    (!retainsParent(gate.owner) || decision.foreground != gate.owner)
            )
                return@synchronized stopped()
            if (gate.storeId in entries) return@synchronized busy()
            if (entries.size >= CLIENT_LIMIT) return@synchronized capacity()
            val next =
                SdkRuntimeReservation<T>(gate.storeId, gate.owner, gate.deviceId, foreground = true)
            entries[gate.storeId] = next
            handovers.remove(gate.storeId)
            changed()
            AppResult.Success(next)
        }

    fun handoverResource(gate: SdkRuntimeHandoverGate): T? =
        synchronized(monitor) {
            check(handovers[gate.storeId] === gate)
            entries[gate.storeId]?.resource
        }

    fun handoverDrained(gate: SdkRuntimeHandoverGate): Boolean =
        synchronized(monitor) {
            check(handovers[gate.storeId] === gate)
            gate.storeId !in entries
        }

    fun endHandover(gate: SdkRuntimeHandoverGate) =
        synchronized(monitor) {
            if (handovers[gate.storeId] === gate) {
                handovers.remove(gate.storeId)
                changed()
            }
        }

    /** Expiry/caller cancellation releases only this reference; foreground ownership survives. */
    fun releaseBorrow(borrow: SdkRuntimeBorrow<T>, failure: Failure): T? =
        synchronized(monitor) {
            borrow.failure.compareAndSet(null, failure)
            borrowers.remove(borrow)
            val entry = borrow.reservation
            if (entries[entry.storeId] !== entry) return@synchronized null
            retireIfIdle(entry)
            entry.resource.takeIf { entry.retired }
        }

    /** Only the acquisition cleanup or fully drained native owner may release its reservation. */
    fun released(reservation: SdkRuntimeReservation<T>) =
        synchronized(monitor) {
            if (entries[reservation.storeId] !== reservation) return@synchronized
            entries.remove(reservation.storeId)
            borrowers
                .filter { it.reservation === reservation }
                .forEach { it.failure.compareAndSet(null, stopped().failure) }
            changed()
        }

    private fun changed() {
        changes.value = if (changes.value == Long.MAX_VALUE) 0 else changes.value + 1
    }

    private fun findEntry(owner: CollaborationOwner, deviceId: String) =
        entries.values.singleOrNull {
            it.space == null && it.owner == owner && it.deviceId == deviceId && !it.retired
        }

    private fun retainsParent(owner: CollaborationOwner): Boolean {
        val decision = retention ?: return false
        return decision.parent?.owner(owner.companyId) == owner &&
            decision.deniedCompanyId != owner.companyId
    }

    private fun retireIfIdle(entry: SdkRuntimeReservation<T>) {
        if (
            !entry.foreground &&
                borrowers.none { it.reservation === entry && it.failure.value == null }
        )
            retire(entry)
    }

    private fun retire(entry: SdkRuntimeReservation<T>) {
        if (!entry.retired) {
            entry.retired = true
            borrowers
                .filter { it.reservation === entry }
                .forEach { it.failure.compareAndSet(null, stopped().failure) }
            entry.resource?.let(requestStop)
        }
    }

    private fun closingResources() = entries.values.filter { it.retired }.mapNotNull { it.resource }

    private fun stopped() = registryFailure("client.collaboration_runtime_not_retained")

    private fun busy() = registryFailure("client.collaboration_runtime_opening")

    private fun handoverPending() = registryFailure("client.collaboration_handover_pending")

    private fun capacity() = registryFailure("client.collaboration_runtime_capacity")

    private fun registryFailure(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat runtime is unavailable.", code = code)
        )

    private companion object {
        const val CLIENT_LIMIT = 5
        const val BORROW_LIMIT = 2
    }
}

internal class SdkRuntimeHandoverGate
internal constructor(
    val storeId: String,
    internal val owner: CollaborationOwner,
    internal val deviceId: String,
    internal val replacingCredentials: Boolean,
    internal val existingStoreParent: CollaborationNativeParent? = null,
    internal val space: SdkSpaceRuntimeIdentity? = null,
) {
    internal var invalidated = false

    override fun toString() = "SdkRuntimeHandoverGate([redacted])"
}

internal class SdkRuntimeReservation<T : Any>(
    val storeId: String,
    val owner: CollaborationOwner,
    val deviceId: String,
    internal var foreground: Boolean,
    internal val space: SdkSpaceRuntimeIdentity? = null,
) {
    internal var resource: T? = null
    internal var retired = false
    internal var historyReopening = false
}

internal class SdkRuntimeBorrow<T : Any>(
    val reservation: SdkRuntimeReservation<T>,
    internal val creating: Boolean,
) {
    internal val failure = MutableStateFlow<Failure?>(null)
    internal var published = false
    val failures = failure.asStateFlow()
}
