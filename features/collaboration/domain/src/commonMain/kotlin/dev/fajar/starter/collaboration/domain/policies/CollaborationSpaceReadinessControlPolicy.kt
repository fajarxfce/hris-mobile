package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceReadinessControl
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceReadinessScope

const val COLLABORATION_SPACE_READINESS_CONTROL_TYPE = "dev.fajar.hris.space_recipient_ready"
private const val MAX_SPACE_PUBLICATION_MILLIS = 253_402_214_399_999L
private const val ZERO_SPACE_READINESS_ID = "00000000-0000-0000-0000-000000000000"
private val SPACE_READINESS_FIELDS =
    setOf(
        "schema_version",
        "event_id",
        "space_id",
        "account_id",
        "native_session_id",
        "device_id",
        "published_at",
        "expires_at",
        "publication_revision",
    )

/** Wire shape only. Current native/runtime ownership and durable deduplication remain separate. */
fun parseCollaborationSpaceReadinessControl(
    fields: Map<String, String>
): CollaborationSpaceReadinessControl? {
    if (
        fields.keys != SPACE_READINESS_FIELDS ||
            fields["schema_version"] != "1" ||
            fields.values.any { it.length > 36 }
    )
        return null
    val revision = fields.getValue("publication_revision").toLongOrNull() ?: return null
    val published = fields.getValue("published_at").toLongOrNull() ?: return null
    if (
        fields["publication_revision"] != revision.toString() ||
            fields["published_at"] != published.toString() ||
            published !in 1..MAX_SPACE_PUBLICATION_MILLIS ||
            fields["expires_at"] != (published + 86_400_000L).toString()
    )
        return null
    return CollaborationSpaceReadinessControl(
            fields.getValue("event_id"),
            fields.getValue("space_id"),
            fields.getValue("account_id"),
            fields.getValue("native_session_id"),
            fields.getValue("device_id"),
            revision,
            published,
        )
        .takeIf(::validCollaborationSpaceReadinessControl)
}

fun validCollaborationSpaceReadinessControl(control: CollaborationSpaceReadinessControl): Boolean =
    control.publicationRevision > 0 &&
        control.publishedAtEpochMillis in 1..MAX_SPACE_PUBLICATION_MILLIS &&
        listOf(
                control.eventId,
                control.spaceId,
                control.accountId,
                control.nativeSessionId,
                control.deviceId,
            )
            .all { it != ZERO_SPACE_READINESS_ID && validCollaborationMessageId(it) }

/** Local storage coordinates only; no selected HR company or remote freshness requirement. */
fun validCollaborationSpaceReadinessScope(scope: CollaborationSpaceReadinessScope): Boolean =
    scope.parent.sessionId.length in 1..128 &&
        scope.parent.sessionId.all { it.code in 33..126 } &&
        scope.parent.audience.length in 1..2048 &&
        scope.parent.audience.all { it.code in 33..126 } &&
        listOf(scope.parent.accountId, scope.parent.nativeSessionId, scope.spaceId, scope.deviceId)
            .all { it != ZERO_SPACE_READINESS_ID && validCollaborationMessageId(it) }
