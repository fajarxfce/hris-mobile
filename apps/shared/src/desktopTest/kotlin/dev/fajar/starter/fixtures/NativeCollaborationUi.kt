package dev.fajar.starter.fixtures

import dev.fajar.starter.collaboration.data.dto.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlin.time.Clock
import kotlinx.serialization.json.Json

/** Opt-in control-plane wire fixture. Native delivery has a separate owned fake SDK. */
class NativeCollaborationUi {
    var enabled = false
    var activeDevice = true
    var failureCode: String? = null
    var loseRoomReply = false
    var holdProvisioning = false
    val roomRequests = mutableListOf<Pair<String, String>>()
    val reads = mutableListOf<String>()
    val rooms = mutableMapOf<String, String>()
    val versions = mutableMapOf<String, Long>()
    val ids = (1..3).map { "aaaaaaaa-0000-0000-0000-" + it.toString().padStart(12, '0') }
    var listedIds = ids
    val deviceId = "bbbbbbbb-0000-0000-0000-000000000001"
    val bootstrapId = "bbbbbbbb-0000-0000-0000-000000000002"
    val accountId = "11111111-1111-4111-8111-111111111111"
    val peerId = "cccccccc-0000-0000-0000-000000000001"
    private val createdAt = Clock.System.now().toString()

    fun roomId(id: String) = "!room-$id:example.test"

    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (
            !enabled ||
                !path.startsWith("/api/v1/companies/${NativeUiServer.COMPANY_ONE}/collaboration/")
        )
            return null
        reads += path
        failureCode?.let {
            return """{"code":"$it"}""" to HttpStatusCode.ServiceUnavailable
        }
        if (path.endsWith("/native-device")) {
            check(request.method == HttpMethod.Get) { "No automatic enrollment or bootstrap" }
            return wire(
                CollaborationSetupDto(
                    if (activeDevice)
                        CollaborationDeviceDto(deviceId, 0, "ACTIVE", "ANDROID", null, null)
                    else null
                )
            )
        }
        val base =
            "/api/v1/companies/${NativeUiServer.COMPANY_ONE}/collaboration/direct-conversations"
        if (path == "$base/summary-by-room") {
            check(request.method == HttpMethod.Get)
            val id =
                ids.singleOrNull { roomId(it) == request.url.parameters["roomId"] }
                    ?: return """{"code":"collaboration_conversation_not_found"}""" to
                        HttpStatusCode.NotFound
            return wire(summary(id))
        }
        if (path == "$base/summaries") {
            val after = request.url.parameters["after"]
            val limit = request.url.parameters["limit"]?.toInt() ?: 30
            check(limit in 1..50)
            val remaining = listedIds.filter { after == null || it > after }
            val page = remaining.take(limit)
            return wire(
                CollaborationPageDto(
                    page.map(::summary),
                    page.lastOrNull()?.takeIf { remaining.size > limit },
                )
            )
        }
        val suffix = path.removePrefix("$base/")
        val id = suffix.substringBefore('/')
        check(id in ids) { "Unknown fixture conversation" }
        return when {
            suffix.endsWith("/summary") -> wire(summary(id))
            suffix.endsWith("/room") -> {
                if (request.method == HttpMethod.Post) {
                    val operation = checkNotNull(request.headers["Idempotency-Key"])
                    val old = roomRequests.any { it.first == id && it.second == operation }
                    roomRequests += id to operation
                    if (!old) {
                        versions[id] = (versions[id] ?: 0) + 1
                        rooms[id] = if (holdProvisioning) "QUEUED" else "READY"
                    }
                    if (loseRoomReply) {
                        loseRoomReply = false
                        throw java.io.IOException("Fixture lost room response")
                    }
                    wire(CollaborationReceiptDto(id, checkNotNull(versions[id])))
                } else wire(room(id))
            }
            suffix == id -> wire(direct(id))
            else -> error("Unexpected collaboration request")
        }
    }

    private fun direct(id: String) =
        CollaborationDirectDto(
            id,
            NativeUiServer.COMPANY_ONE,
            accountId,
            peerId,
            accountId,
            createdAt,
            0,
        )

    private fun summary(id: String) =
        CollaborationDirectSummaryDto(
            direct(id),
            CollaborationParticipantDto(peerId, "Fictional colleague"),
        )

    private fun room(id: String): CollaborationDirectRoomDto {
        val state = rooms[id] ?: "READY"
        return CollaborationDirectRoomDto(
            id,
            if (state == "NOT_REQUESTED") null else versions[id] ?: 0,
            state,
            if (state == "READY") roomId(id) else null,
            if (state == "NOT_REQUESTED") null else accountId,
            false,
            null,
        )
    }

    private inline fun <reified T> wire(value: T) = Json.encodeToString(value) to HttpStatusCode.OK
}
