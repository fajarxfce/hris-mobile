package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageRecord
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageState

/**
 * A missing ACK needs the current head. An unrelated older confirmation may lie outside the recent
 * original window and must not keep that recovery pinned to old history. Once every delivery is
 * confirmed, an explicit original anchor can complete historical retirement.
 */
fun collaborationReceiptRecoveryAnchor(records: List<CollaborationMessageRecord>): String? =
    if (
        records.any {
            it.state is CollaborationMessageState.Staging ||
                it.state is CollaborationMessageState.Staged
        }
    )
        null
    else records.firstNotNullOfOrNull { (it.state as? CollaborationMessageState.Sent)?.eventId }
