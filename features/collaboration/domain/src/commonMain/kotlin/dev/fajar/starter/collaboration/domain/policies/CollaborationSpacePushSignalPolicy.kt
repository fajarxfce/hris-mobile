package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpacePushSignal

/** Strict V1 group envelope. Late hints may wake owned work; packet age never expires it. */
fun parseCollaborationSpacePushSignal(fields: Map<String, String>): CollaborationSpacePushSignal? {
    val base =
        setOf(
            "schemaVersion",
            "eventCode",
            "eventId",
            "accountId",
            "spaceId",
            "nativeSessionId",
            "deviceId",
            "revision",
            "issuedAt",
            "expiresAt",
        )
    val locators = setOf("matrixRoomId", "matrixEventId")
    if (fields.size !in setOf(10, 12) || (fields.keys != base && fields.keys != base + locators))
        return null
    if (
        fields.values.any { it.length > 255 } ||
            fields["schemaVersion"] != "1" ||
            fields["eventCode"] != "collaboration.space_chat_available"
    )
        return null
    if (
        listOf("eventId", "accountId", "spaceId", "nativeSessionId", "deviceId").any {
            !validCollaborationMessageId(fields.getValue(it))
        }
    )
        return null
    val numbers =
        listOf("revision", "issuedAt", "expiresAt").map { key ->
            val value = fields.getValue(key)
            value.toLongOrNull()?.takeIf { it.toString() == value } ?: return null
        }
    val (revision, issuedAt, expiresAt) = numbers
    if (
        revision <= 0 ||
            issuedAt !in 0..253_402_300_799L ||
            expiresAt !in 0..253_402_300_799L ||
            expiresAt - issuedAt !in 1..86_400L
    )
        return null
    val room = fields["matrixRoomId"]
    val event = fields["matrixEventId"]
    if (
        room != null &&
            (room.length !in 2..255 ||
                !room.startsWith('!') ||
                room.any { it.code !in 33..126 } ||
                event == null ||
                !validCollaborationEventId(event) ||
                event.any { it.code !in 33..126 })
    )
        return null
    return CollaborationSpacePushSignal(
        fields.getValue("eventId"),
        fields.getValue("accountId"),
        fields.getValue("spaceId"),
        fields.getValue("nativeSessionId"),
        fields.getValue("deviceId"),
        revision,
        issuedAt,
        expiresAt,
        room,
        event,
    )
}
