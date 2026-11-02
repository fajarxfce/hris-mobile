package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationPushSignal

fun parseCollaborationPushSignal(fields: Map<String, String>): CollaborationPushSignal? {
    val base =
        setOf(
            "schemaVersion",
            "eventCode",
            "eventId",
            "accountId",
            "companyId",
            "nativeSessionId",
            "deviceId",
            "revision",
            "issuedAt",
            "expiresAt",
        )
    val event = setOf("matrixRoomId", "matrixEventId")
    if (fields.size !in setOf(10, 12) || (fields.keys != base && fields.keys != base + event))
        return null
    if (fields.values.any { it.length > 255 }) return null
    if (fields["schemaVersion"] != "1" || fields["eventCode"] != "collaboration.chat_available")
        return null
    if (
        listOf("eventId", "accountId", "companyId", "nativeSessionId", "deviceId").any {
            !validCollaborationMessageId(fields.getValue(it))
        }
    )
        return null
    val revision = pushNumber(fields.getValue("revision")) ?: return null
    val issued = pushNumber(fields.getValue("issuedAt")) ?: return null
    val expiry = pushNumber(fields.getValue("expiresAt")) ?: return null
    if (
        revision < 1 ||
            issued !in 0..253_402_300_799L ||
            expiry !in 0..253_402_300_799L ||
            expiry - issued !in 1..86_400L
    )
        return null
    if (fields.size == 12) {
        val room = fields.getValue("matrixRoomId")
        if (
            !room.startsWith('!') ||
                room.length !in 2..255 ||
                room.any { it.isWhitespace() || it.isISOControl() }
        )
            return null
        if (!validCollaborationEventId(fields.getValue("matrixEventId"))) return null
    }
    // Late/duplicate hints may still request recovery; neither their time nor room grants display.
    return CollaborationPushSignal(fields.getValue("accountId"), fields.getValue("nativeSessionId"))
}

private fun pushNumber(value: String): Long? =
    value.toLongOrNull()?.takeIf { it.toString() == value }
