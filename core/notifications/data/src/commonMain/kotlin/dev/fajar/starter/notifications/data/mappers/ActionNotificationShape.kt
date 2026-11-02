package dev.fajar.starter.notifications.data.mappers

import dev.fajar.starter.notifications.data.dto.*

/** Bounded transport shape, not cryptographic authentication or an authorization decision. */
fun validActionNotificationReference(value: ActionNotificationReference) =
    opaqueId.matches(value.id) && opaqueId.matches(value.renderId)

fun validActionNotificationPayload(value: ActionNotificationPayload) =
    validActionNotificationReference(value.reference) &&
        nativeText(value.title, 160) &&
        nativeText(value.body, 400) &&
        value.envelopes.keys == ActionNotificationKind.entries.toSet() &&
        value.envelopes.values.all(::validActionNotificationEnvelope)

fun validActionNotificationEnvelope(value: String) =
    value.length in 1..4096 && value.all { it.code in 33..126 }

private fun nativeText(value: String, limit: Int): Boolean {
    if (value.length > limit) return false
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.code < 32 || char.code in 127..159) return false
        if (char.isHighSurrogate()) {
            if (index >= value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

private val opaqueId = Regex("[a-zA-Z0-9_-]{1,128}")
