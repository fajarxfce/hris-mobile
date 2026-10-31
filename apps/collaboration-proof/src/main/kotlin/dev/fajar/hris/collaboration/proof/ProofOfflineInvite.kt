package dev.fajar.hris.collaboration.proof

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

internal data class ProofOfflineInviteResult(
    val roomId: String,
    val queued: ProofQueuedMessageResult,
)

/** The web peer has keys from the first room, but stops before this room is created. */
internal class ProofOfflineInvite(private val client: Client, private val directory: File) {
    suspend fun send(peer: String, body: String, logicalId: String): ProofOfflineInviteResult {
        require(peer == "@nativeweb:proof.invalid")
        withTimeout(30_000) {
            val signal = File(directory, "offline-ready.json")
            while (!signal.exists()) delay(100)
            check(signal.length() in 1..1024)
            check(JSONObject(signal.readText()).getBoolean("ready"))
        }
        val id =
            client.createRoom(
                CreateRoomParameters(
                    name = null,
                    isEncrypted = true,
                    isDirect = true,
                    visibility = RoomVisibility.Private,
                    preset = RoomPreset.PRIVATE_CHAT,
                    invite = listOf(peer),
                    historyVisibilityOverride = RoomHistoryVisibility.Invited,
                )
            )
        val room = awaitRoom(id)
        try {
            withTimeout(15_000) { while (!ready(room)) delay(100) }
            return ProofOfflineInviteResult(
                id,
                ProofQueuedMessage(client, room).send(body, logicalId),
            )
        } finally {
            room.close()
        }
    }

    private suspend fun awaitRoom(id: String): Room {
        var acquired: Room? = null
        return try {
            withTimeout(15_000) {
                while (acquired == null) {
                    acquired = client.getRoom(id)
                    if (acquired == null) delay(100)
                }
            }
            checkNotNull(acquired)
        } catch (failure: Throwable) {
            acquired?.close()
            throw failure
        }
    }

    private suspend fun ready(room: Room): Boolean {
        if (!room.isEncrypted()) return false
        val info = room.roomInfo()
        return try {
            info.roomVersion == "12" &&
                info.historyVisibility == RoomHistoryVisibility.Invited &&
                room.joinedMembersCount() == 1uL &&
                room.invitedMembersCount() == 1uL
        } finally {
            info.destroy()
        }
    }
}
