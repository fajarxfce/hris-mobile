package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

const val COLLABORATION_NOTIFICATION_INPUT_CAPACITY = 64

/** Syntax only; the signed descriptor still requires current account/audience authentication. */
fun validCollaborationUnverifiedNotificationReply(
    value: CollaborationUnverifiedNotificationReply
): Boolean =
    validCollaborationText(value.text) &&
        validCollaborationNotificationTokenShape(value.token) &&
        (value.token.encoded.length - 3) % 4 == 0 &&
        inputToken.matches(value.token.encoded)

fun validCollaborationNotificationInputReference(
    value: CollaborationNotificationInputReference
): Boolean =
    validCollaborationNotificationInputId(value.id) && validCollaborationMessageId(value.generation)

fun validCollaborationNotificationInputId(value: String): Boolean = inputId.matches(value)

fun validCollaborationNotificationInput(value: CollaborationNotificationInput): Boolean =
    validCollaborationNotificationInputReference(value.reference) &&
        validCollaborationUnverifiedNotificationReply(value.reply)

private val inputToken = Regex("v[12]\\.[A-Za-z0-9_-]+={0,2}")
private val inputId = Regex("q-[0-9a-f]{64}")
