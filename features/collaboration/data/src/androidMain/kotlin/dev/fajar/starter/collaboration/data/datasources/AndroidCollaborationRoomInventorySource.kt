package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room

/**
 * Local enumeration never joins, synchronizes or opens another client. Selection retains at most
 * limit+1 candidates. SDK8 itself returns ALL room handles; this does not bound its FFI allocation.
 */
internal class AndroidCollaborationRoomInventorySource(private val client: Client) :
    CollaborationRoomInventorySource {
    override suspend fun read(afterRoomId: String?, limit: Int): CollaborationSdkRoomInventoryDto {
        currentCoroutineContext().ensureActive()
        require(limit in 1..64)
        require(
            afterRoomId == null ||
                (afterRoomId.startsWith('!') &&
                    afterRoomId.length in 2..255 &&
                    afterRoomId.none { it.isWhitespace() || it.isISOControl() })
        )
        val acquired = client.rooms()
        var primary: Throwable? = null
        val result =
            try {
                val selected = mutableListOf<Pair<String, Room>>()
                for (room in acquired) {
                    currentCoroutineContext().ensureActive()
                    val id = room.id()
                    if (afterRoomId != null && id <= afterRoomId) continue
                    val found = selected.binarySearchBy(id) { it.first }
                    check(found < 0) { "Duplicate SDK room identity" }
                    val offset = -found - 1
                    if (offset <= limit) {
                        selected.add(offset, id to room)
                        if (selected.size > limit + 1) selected.removeAt(selected.lastIndex)
                    }
                }
                val page =
                    selected.take(limit).map { (id, room) ->
                        currentCoroutineContext().ensureActive()
                        val membership =
                            when (room.membership()) {
                                Membership.INVITED -> CollaborationSdkInventoryMembership.INVITED
                                Membership.JOINED -> CollaborationSdkInventoryMembership.JOINED
                                Membership.LEFT -> CollaborationSdkInventoryMembership.LEFT
                                Membership.KNOCKED -> CollaborationSdkInventoryMembership.KNOCKED
                                Membership.BANNED -> CollaborationSdkInventoryMembership.BANNED
                            }
                        CollaborationSdkInventoryRoomDto(id, membership)
                    }
                currentCoroutineContext().ensureActive()
                CollaborationSdkRoomInventoryDto(
                    afterRoomId,
                    page,
                    if (selected.size > limit) page.last().roomId else null,
                )
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                var disposal = primary
                for (room in acquired) {
                    try {
                        room.close()
                    } catch (failure: Throwable) {
                        if (disposal == null) disposal = failure
                        else if (disposal !== failure) disposal.addSuppressed(failure)
                    }
                }
                if (primary == null) disposal?.let { throw it }
            }
        currentCoroutineContext().ensureActive()
        return result
    }
}
