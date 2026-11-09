package dev.fajar.starter.collaboration.data.datasources

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.Room

/** Raw room acquisition. The admitted caller owns the client and every returned native handle. */
internal class AndroidCollaborationRoomSource(private val client: Client) {
    suspend fun joined(roomId: String): Room {
        currentCoroutineContext().ensureActive()
        var acquired: Room? = null
        try {
            acquired = client.getRoom(roomId)
            currentCoroutineContext().ensureActive()
            if (acquired?.membership() != Membership.JOINED) {
                val previous = acquired
                acquired = null
                previous?.close()
                currentCoroutineContext().ensureActive()
                acquired = client.joinRoomById(roomId)
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(acquired).also { acquired = null }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }
                .exceptionOrNull()
                ?.takeUnless { it === failure }
                ?.let(failure::addSuppressed)
            throw failure
        }
    }
}
