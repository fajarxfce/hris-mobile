package dev.fajar.hris.collaboration.proof

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.MembershipState
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
        progress("waiting_for_offline_peer")
        withTimeout(30_000) {
            val signal = File(directory, "offline-ready.json")
            while (!signal.exists()) delay(100)
            check(signal.length() in 1..1024)
            check(JSONObject(signal.readText()).getBoolean("ready"))
        }
        progress("creating_offline_room")
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
        progress("acquiring_offline_room")
        val room = awaitRoom(id)
        try {
            progress("waiting_for_offline_room_state")
            withTimeout(15_000) { while (!ready(room)) delay(100) }
            progress("checking_offline_members")
            withTimeout(15_000) { verifyMembers(room, peer) }
            progress("sending_offline_message")
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
        val encrypted = room.isEncrypted()
        val info = room.roomInfo()
        return try {
            val state =
                mapOf(
                    "encrypted" to encrypted,
                    "expectedVersion" to (info.roomVersion == "12"),
                    "invitedHistory" to (info.historyVisibility == RoomHistoryVisibility.Invited),
                    "oneJoined" to (room.joinedMembersCount() == 1uL),
                    "oneInvited" to (room.invitedMembersCount() == 1uL),
                )
            progress("waiting_for_offline_room_state", state)
            // Classic sync may omit optional summary counts. Membership is checked
            // independently against the complete SDK member list before sending.
            encrypted && state.getValue("expectedVersion") && state.getValue("invitedHistory")
        } finally {
            info.destroy()
        }
    }

    private suspend fun verifyMembers(room: Room, peer: String) {
        room.members().use { members ->
            val chunk = checkNotNull(members.nextChunk(3u))
            check(chunk.size == 2 && members.nextChunk(1u).isNullOrEmpty())
            check(chunk.single { it.userId == client.userId() }.membership is MembershipState.Join)
            check(chunk.single { it.userId == peer }.membership is MembershipState.Invite)
        }
    }

    private fun progress(stage: String, state: Map<String, Boolean> = emptyMap()) {
        val value = JSONObject().put("stage", stage)
        state.forEach { (key, ready) -> value.put(key, ready) }
        val next = File(directory, "progress.next")
        next.writeText(value.toString())
        check(next.renameTo(File(directory, "progress.json")))
    }
}
