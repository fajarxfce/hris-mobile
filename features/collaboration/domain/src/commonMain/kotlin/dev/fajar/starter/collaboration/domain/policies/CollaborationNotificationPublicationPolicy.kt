package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.io.encoding.Base64

const val COLLABORATION_NOTIFICATION_PUBLICATION_CAPACITY = 64

fun CollaborationNotificationPublicationState.isActivePublication() =
    this == CollaborationNotificationPublicationState.Prepared ||
        this == CollaborationNotificationPublicationState.Published

fun validCollaborationNotificationPublicationId(value: String) = publicationId.matches(value)

fun validCollaborationNotificationPublication(
    value: CollaborationNotificationPublication
): Boolean =
    validCollaborationNotificationPublicationId(value.id) &&
        validCollaborationMessageId(value.version.generation) &&
        value.version.revision in 0 until 9_007_199_254_740_991L &&
        validCollaborationNotificationPublicationBody(value.body)

fun validCollaborationNotificationPublicationBody(
    value: CollaborationNotificationPublicationBody
): Boolean {
    val parent = value.parent
    val target = value.target
    if (
        parent.sessionId.length !in 1..128 ||
            parent.sessionId.any { it.code !in 33..126 } ||
            parent.audience.length !in 1..2048 ||
            !validCollaborationMessageId(parent.accountId) ||
            !validCollaborationMessageId(parent.nativeSessionId) ||
            !validCollaborationNotificationTarget(target) ||
            !publicationHash.matches(value.contentHash) ||
            value.timestamp !in 0..9_007_199_254_740_991L
    )
        return false
    if (!value.state.isActivePublication()) return value.message == null && value.actions == null
    val message = value.message ?: return false
    val actions = value.actions ?: return false
    if (
        message.access !=
            CollaborationLocalAccess(
                parent.sessionId,
                parent.audience,
                parent.accountId,
                target.companyId,
            ) ||
            message.conversationId != target.conversationId ||
            message.roomId != target.roomId ||
            message.eventId != target.eventId ||
            message.contentHash != value.contentHash ||
            !validCollaborationNotificationRevision(message.settingsVersion)
    )
        return false
    val read = message.readVersion
    if (
        read != null &&
            (!validCollaborationMessageId(read.generation) ||
                read.revision !in 0 until 9_007_199_254_740_991L)
    )
        return false
    val preview =
        when (message.preview) {
            CollaborationNotificationPreview.Hidden ->
                message.senderName == null && message.body == null
            CollaborationNotificationPreview.SenderOnly ->
                validPreview(message.senderName, 160) && message.body == null
            CollaborationNotificationPreview.Message ->
                validPreview(message.senderName, 160) && validPreview(message.body, 400)
        }
    return preview &&
        listOf(actions.reply, actions.markRead, actions.mute, actions.dismiss)
            .all(::validPublicationToken)
}

/** Canonical encoding only, including issuer padding; this does not authenticate the envelope. */
private fun validPublicationToken(value: CollaborationNotificationActionToken): Boolean {
    if (value.encoded.length !in 4..4096 || !value.encoded.startsWith("v1.")) return false
    val encoded = value.encoded.drop(3)
    return runCatching {
            val bytes = Base64.UrlSafe.decode(encoded)
            try {
                Base64.UrlSafe.encode(bytes) == encoded
            } finally {
                bytes.fill(0)
            }
        }
        .getOrDefault(false)
}

private fun validPreview(value: String?, limit: Int) =
    value != null &&
        value.length <= limit &&
        collaborationNotificationPreviewText(value, limit) == value &&
        (value.isEmpty() || validCollaborationText(value))

private val publicationId = Regex("p-[0-9a-f]{64}")
private val publicationHash = Regex("[0-9a-f]{64}")
