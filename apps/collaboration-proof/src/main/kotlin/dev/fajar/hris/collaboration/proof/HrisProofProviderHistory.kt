package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Actual admitted acquisition and encrypted reference recovery through the database's owner. */
internal class HrisProofProviderHistory(
    private val runtime: AndroidCollaborationRuntimeLease,
    private val roomId: String,
    private val acquire:
        suspend (
            HrisProofRuntimeAccess, CollaborationHistoryPageOrigin,
        ) -> CollaborationProviderHistoryAcquisition,
    private val stored: suspend () -> CollaborationHistoryReferences,
    private val reopen: suspend () -> Unit,
    private val reconcile: suspend () -> CollaborationHistoryReconciliation,
) {
    suspend fun verify(expectedEvents: Set<String>) {
        val access = HrisProofRuntimeAccess(runtime)
        val first = acquire(access, CollaborationHistoryPageOrigin.SavedPosition)
        val page = requireNotNull(access.providerPage)
        validate(page, null)
        check(first.references == page.events.size && first.exhausted == (page.next == null))
        check(page.events.map { it.eventId }.containsAll(expectedEvents))
        val persisted = stored()
        check(
            persisted.progress?.next == page.next &&
                persisted.progress?.references == page.events.size.toLong()
        )
        check(persisted.events.map { it.event } == page.events && persisted.after == null)
        reopen()
        check(stored() == persisted)
        val replayed = success(runtime.readProviderHistory(roomId, page.start))
        validate(replayed, page.start)
        check(replayed.events == page.events)
        val second = acquire(access, CollaborationHistoryPageOrigin.SavedPosition)
        if (page.next == null) {
            check(
                access.providerReads == 1 &&
                    second == CollaborationProviderHistoryAcquisition(0, true)
            )
            check(stored() == persisted)
        } else {
            val older = requireNotNull(access.providerPage)
            validate(older, page.next)
            check(
                access.providerReads == 2 &&
                    second.references == older.events.size &&
                    second.exhausted == (older.next == null)
            )
            val retained = stored()
            val previous = requireNotNull(persisted.progress)
            val progress = requireNotNull(retained.progress)
            check(
                progress.generation == previous.generation &&
                    progress.revision == previous.revision + 1
            )
            check(progress.next == older.next && retained.after == null)
            check(
                retained.events.map { it.event.eventId }.toSet() ==
                    (page.events + older.events).map { it.eventId }.toSet()
            )
        }
        val before = stored()
        val progress = requireNotNull(before.progress)
        val pending = before.events.filter { it.event.eventId !in expectedEvents }
        check(before.events.size - pending.size == expectedEvents.size && before.after == null)
        check(
            reconcile() ==
                CollaborationHistoryReconciliation(expectedEvents.size, pending.size, null)
        )
        val resolved = stored()
        check(
            resolved ==
                CollaborationHistoryReferences(
                    progress.copy(revision = progress.revision + 1),
                    pending,
                    null,
                )
        )
        reopen()
        check(stored() == resolved)
        check(reconcile() == CollaborationHistoryReconciliation(0, pending.size, null))
        check(stored() == resolved)
        verifyRestart(access, resolved)
    }

    private suspend fun verifyRestart(
        access: HrisProofRuntimeAccess,
        before: CollaborationHistoryReferences,
    ) {
        val reads = access.providerReads
        val result = acquire(access, CollaborationHistoryPageOrigin.Latest)
        val page = requireNotNull(access.providerPage)
        validate(page, null)
        check(access.providerReads == reads + 1)
        check(
            result == CollaborationProviderHistoryAcquisition(page.events.size, page.next == null)
        )
        val previous = requireNotNull(before.progress)
        val retained = stored()
        check(
            retained.progress == previous.copy(revision = previous.revision + 1, next = page.next)
        )
        check(retained.events == before.events && retained.after == before.after)
        reopen()
        check(stored() == retained)
        check(reconcile() == CollaborationHistoryReconciliation(0, retained.events.size, null))
        check(stored() == retained)
    }

    private fun validate(page: CollaborationProviderHistoryPage, position: String?) {
        check(
            page.owner == runtime.owner &&
                page.deviceId == runtime.deviceId &&
                page.roomId == roomId &&
                page.requestedPosition == position &&
                page.events.size <= 20
        )
    }

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
