@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.domain.push.policies

import dev.fajar.starter.inbox.domain.push.entities.InboxPushSignal
import kotlin.uuid.Uuid

fun parseInboxPushSignal(fields: Map<String, String>): InboxPushSignal? {
    val names = setOf("schemaVersion", "eventCode", "eventId", "accountId", "companyId", "inboxId")
    if (fields.keys != names || fields.values.any { it.length > 256 }) return null
    if (fields["schemaVersion"] != "1" || fields["eventCode"] != "communications.inbox_available")
        return null
    val identifiers =
        listOf("eventId", "accountId", "companyId", "inboxId").map { fields.getValue(it) }
    if (identifiers.any { !canonicalInboxPushId(it) }) return null
    return InboxPushSignal(identifiers[0], identifiers[1], identifiers[2], identifiers[3])
}

fun canonicalInboxPushId(value: String): Boolean =
    value.length == 36 &&
        try {
            Uuid.parse(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }
