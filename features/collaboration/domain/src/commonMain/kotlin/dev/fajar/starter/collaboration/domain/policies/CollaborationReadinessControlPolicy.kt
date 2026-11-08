package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

const val COLLABORATION_READINESS_CONTROL_TYPE = "dev.fajar.hris.recipient_ready"
private const val MAX_PUBLICATION_MILLIS = 253_402_214_399_999L
private val READINESS_FIELDS =
    setOf(
        "schema_version",
        "event_id",
        "company_id",
        "account_id",
        "native_session_id",
        "device_id",
        "published_at",
        "expires_at",
        "publication_revision",
    )

fun parseCollaborationReadinessControl(
    fields: Map<String, String>
): CollaborationReadinessControl? {
    if (
        fields.keys != READINESS_FIELDS ||
            fields["schema_version"] != "2" ||
            fields.values.any { it.length > 36 }
    )
        return null
    if (
        listOf("event_id", "company_id", "account_id", "native_session_id", "device_id").any {
            !validCollaborationMessageId(fields.getValue(it))
        }
    )
        return null
    val revision = fields.getValue("publication_revision").toLongOrNull() ?: return null
    val published = fields.getValue("published_at").toLongOrNull() ?: return null
    if (
        revision <= 0 ||
            fields["publication_revision"] != revision.toString() ||
            published !in 1..MAX_PUBLICATION_MILLIS ||
            fields["published_at"] != published.toString() ||
            fields["expires_at"] != (published + 86_400_000L).toString()
    )
        return null
    return CollaborationReadinessControl(
        fields.getValue("event_id"),
        fields.getValue("company_id"),
        fields.getValue("account_id"),
        fields.getValue("native_session_id"),
        fields.getValue("device_id"),
        revision,
        published,
    )
}

/**
 * Live ownership still needs the session repository guard. Short token age never admits content.
 */
fun collaborationReadinessScope(
    signal: CollaborationReadinessSignal,
    parent: CollaborationNativeParent,
): CollaborationReadinessScope? {
    val source = signal.sourceOwner
    val control = signal.control
    if (
        source.sessionId != parent.sessionId ||
            source.audience != parent.audience ||
            source.accountId != parent.accountId ||
            source.nativeSessionId != parent.nativeSessionId ||
            control.accountId != parent.accountId ||
            control.nativeSessionId != parent.nativeSessionId ||
            control.companyId != source.companyId ||
            control.deviceId != signal.sourceDeviceId ||
            !validCollaborationMessageId(control.companyId) ||
            !validCollaborationMessageId(control.deviceId) ||
            control.publicationRevision <= 0
    )
        return null
    return CollaborationReadinessScope(parent, control.companyId, control.deviceId)
}

fun validCollaborationReadinessProgress(value: CollaborationReadinessProgress): Boolean =
    value.requestedRevision >= 0 &&
        value.pendingRevision > 0 &&
        value.requestedRevision <= value.pendingRevision

/**
 * A higher commit revision subsumes lower publications, regardless of timestamp or arrival order.
 */
fun retainCollaborationReadinessRevision(
    before: CollaborationReadinessProgress?,
    revision: Long,
): CollaborationReadinessProgress? {
    require(revision > 0 && (before == null || validCollaborationReadinessProgress(before)))
    if (
        before != null &&
            revision <= before.requestedRevision &&
            before.pendingRevision == before.requestedRevision
    )
        return null
    return CollaborationReadinessProgress(
        before?.requestedRevision ?: 0,
        maxOf(before?.pendingRevision ?: 0, revision),
    )
}

/** A late completion cannot acknowledge a newer pending revision it never scheduled. */
fun acknowledgeCollaborationReadinessRevision(
    current: CollaborationReadinessProgress,
    scheduledRevision: Long,
): CollaborationReadinessProgress {
    require(
        validCollaborationReadinessProgress(current) &&
            scheduledRevision in 1..current.pendingRevision
    )
    return current.copy(requestedRevision = maxOf(current.requestedRevision, scheduledRevision))
}
