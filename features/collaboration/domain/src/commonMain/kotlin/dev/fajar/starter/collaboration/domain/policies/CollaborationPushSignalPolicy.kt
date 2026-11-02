package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

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
    val hint =
        CollaborationPushHint(
            fields.getValue("eventId"),
            fields.getValue("companyId"),
            fields.getValue("deviceId"),
            pushNumber(fields.getValue("revision")) ?: return null,
            pushNumber(fields.getValue("issuedAt")) ?: return null,
            pushNumber(fields.getValue("expiresAt")) ?: return null,
            fields["matrixRoomId"],
            fields["matrixEventId"],
        )
    if (!validCollaborationPushHint(hint)) return null
    // Late/duplicate hints may still request recovery; neither their time nor room grants display.
    return CollaborationPushSignal(
        fields.getValue("accountId"),
        fields.getValue("nativeSessionId"),
        hint,
    )
}

private fun pushNumber(value: String): Long? =
    value.toLongOrNull()?.takeIf { it.toString() == value }
