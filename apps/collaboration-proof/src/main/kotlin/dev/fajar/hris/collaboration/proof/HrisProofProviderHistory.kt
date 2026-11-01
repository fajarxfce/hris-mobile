package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Actual scoped metadata traversal. This fixture does not acknowledge or persist page progress. */
internal class HrisProofProviderHistory(
    private val runtime: AndroidCollaborationRuntimeLease,
    private val application: HrisProofApplicationAccess,
    private val conversationId: String,
    private val roomId: String,
) {
    suspend fun verify(expectedEvents: Set<String>) {
        admit()
        val first = read(null)
        check(first.events.map { it.eventId }.containsAll(expectedEvents))
        val replayed = read(first.start)
        check(replayed.events == first.events)
        first.next?.let { read(it) }
        admit()
    }

    private suspend fun read(position: String?): CollaborationProviderHistoryPage {
        val page = success(runtime.readProviderHistory(roomId, position))
        check(
            page.owner == runtime.owner &&
                page.deviceId == runtime.deviceId &&
                page.roomId == roomId &&
                page.requestedPosition == position
        )
        check(
            page.events.size <= 20 &&
                page.events.map { it.eventId }.distinct().size == page.events.size
        )
        return page
    }

    private suspend fun admit() {
        val owner = runtime.owner
        val device = requireNotNull(success(application.setup.read(owner)))
        check(device.id == runtime.deviceId && device.state == CollaborationDeviceState.ACTIVE)
        val direct = success(application.conversations.get(owner, conversationId))
        check(direct.id == conversationId && direct.companyId == owner.companyId)
        check(owner.accountId in listOf(direct.firstAccountId, direct.secondAccountId))
        val room = success(application.conversations.room(owner, conversationId))
        check(
            room.conversationId == conversationId &&
                room.matrixRoomId == roomId &&
                room.state == CollaborationDirectRoomState.READY
        )
    }

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
